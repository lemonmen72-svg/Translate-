package ru.translate.overlay.mt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * Инференс модели перевода «энкодер-декодер» через onnxruntime.
 *
 * Подходит и для Marian (Opus-MT), и для T5 — у обоих после экспорта через
 * optimum одинаковая форма графа: энкодер берёт `input_ids` и `attention_mask`,
 * декодер берёт `input_ids`, `encoder_attention_mask` и `encoder_hidden_states`.
 * Различия вынесены в метаданные: стартовый токен декодера и префикс задачи
 * (у T5 это «translate to ru: »).
 *
 * **Beam search, а не жадное декодирование.** Это существенно: обе модели
 * обучались с `num_beams = 4`, и жадный поиск заметно теряет качество — как раз
 * на согласованиях и на выборе правильного значения многозначного слова. В
 * первой версии стоял жадный поиск, и это была одна из причин плохого перевода.
 *
 * KV-кеша нет: на каждом шаге декодеру подаётся вся последовательность заново.
 * Зато все луки считаются одним батчем, поэтому beam search обходится не в
 * четыре раза дороже, а примерно в полтора.
 */
class Seq2SeqOnnx private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val tokenizer: SentencePieceUnigram,
    private val pieceToId: Map<String, Int>,
    private val idToPiece: Array<String>,
    private val meta: Meta,
    private val beams: Int,
) : Closeable {

    data class Meta(
        val padId: Int,
        val eosId: Int,
        val unkId: Int,
        val decoderStartId: Int,
        val maxSourceTokens: Int,
        val maxTargetTokens: Int,
        /** Префикс задачи для T5. Для Marian пустой. */
        val sourcePrefix: String,
        /** Добавлять ли eos в конец входа. Marian — да, T5 — да. */
        val appendEosToSource: Boolean,
    )

    fun translate(text: String): String {
        val sourceIds = encodeSource(text)
        if (sourceIds.isEmpty()) return ""

        val sourceLen = sourceIds.size
        val inputIds = tensor(sourceIds, 1, sourceLen.toLong())
        val attention = tensor(LongArray(sourceLen) { 1L }, 1, sourceLen.toLong())

        var encoderResult: OrtSession.Result? = null
        try {
            encoderResult = encoder.run(
                mapOf("input_ids" to inputIds, "attention_mask" to attention)
            )
            val hidden = encoderResult.get(0) as OnnxTensor
            val hiddenShape = hidden.info.shape
            val model = hiddenShape[2].toInt()
            val hiddenFlat = FloatArray(sourceLen * model)
            hidden.floatBuffer.get(hiddenFlat)
            return beamSearch(hiddenFlat, sourceLen, model)
        } finally {
            runCatching { encoderResult?.close() }
            runCatching { attention.close() }
            runCatching { inputIds.close() }
        }
    }

    private class Hypothesis(
        val tokens: MutableList<Int>,
        var logProb: Double,
        var finished: Boolean,
    ) {
        /**
         * Нормировка на длину. Без неё beam search систематически предпочитает
         * короткие переводы и обрубает концы фраз.
         */
        fun score(): Double = logProb / tokens.size.coerceAtLeast(1)
    }

    private fun beamSearch(hiddenFlat: FloatArray, sourceLen: Int, model: Int): String {
        var live = mutableListOf(
            Hypothesis(mutableListOf(meta.decoderStartId), 0.0, false)
        )
        val done = ArrayList<Hypothesis>(beams)

        for (step in 0 until meta.maxTargetTokens) {
            if (live.isEmpty()) break

            val batch = live.size
            val stepLen = live[0].tokens.size
            val decoderIds = LongArray(batch * stepLen)
            for (b in 0 until batch) {
                val tokens = live[b].tokens
                for (t in 0 until stepLen) {
                    decoderIds[b * stepLen + t] = tokens[t].toLong()
                }
            }

            val hiddenBatch = tileHidden(hiddenFlat, sourceLen, model, batch)
            val maskBatch = tensor(
                LongArray(batch * sourceLen) { 1L },
                batch.toLong(), sourceLen.toLong(),
            )
            val decoderInput = tensor(decoderIds, batch.toLong(), stepLen.toLong())

            val candidates = ArrayList<Triple<Int, Int, Double>>(batch * beams)
            try {
                decoder.run(
                    mapOf(
                        "input_ids" to decoderInput,
                        "encoder_attention_mask" to maskBatch,
                        "encoder_hidden_states" to hiddenBatch,
                    )
                ).use { out ->
                    val logits = out.get(0) as OnnxTensor
                    val shape = logits.info.shape
                    val vocab = shape[shape.size - 1].toInt()
                    val buffer = logits.floatBuffer
                    for (b in 0 until batch) {
                        val offset = (b * stepLen + stepLen - 1) * vocab
                        val top = topKLogProbs(buffer, offset, vocab, beams)
                        for ((tokenId, logProb) in top) {
                            candidates.add(Triple(b, tokenId, live[b].logProb + logProb))
                        }
                    }
                }
            } finally {
                runCatching { decoderInput.close() }
                runCatching { maskBatch.close() }
                runCatching { hiddenBatch.close() }
            }

            candidates.sortByDescending { it.third }
            val next = ArrayList<Hypothesis>(beams)
            for ((beamIndex, tokenId, logProb) in candidates) {
                if (next.size >= beams) break

                // pad-токен запрещён к порождению, а не завершает гипотезу.
                // У Marian decoder_start_token_id совпадает с pad, и в
                // generation_config модели pad стоит в bad_words_ids — эталон его
                // подавляет и берёт следующий по вероятности токен. Прежний код
                // считал pad концом перевода, из-за чего перевод мог обрываться
                // на середине. На тестовых фразах это не срабатывало, но дефект
                // настоящий: достаточно, чтобы pad один раз оказался в топе.
                if (tokenId == meta.padId) continue

                val tokens = ArrayList(live[beamIndex].tokens)
                tokens.add(tokenId)
                val hypothesis = Hypothesis(tokens, logProb, false)
                if (tokenId == meta.eosId) {
                    hypothesis.finished = true
                    done.add(hypothesis)
                } else {
                    next.add(hypothesis)
                }
            }

            // Все оставшиеся варианты хуже уже готовых — дальше искать нечего.
            if (done.size >= beams) {
                val bestDone = done.maxOf { it.score() }
                if (next.isEmpty() || next.maxOf { it.score() } <= bestDone) break
            }
            live = next
        }

        val best = (done + live)
            .filter { it.tokens.size > 1 }
            .maxByOrNull { it.score() }
            ?: return ""

        // Первый токен — стартовый, он не часть перевода.
        val pieces = best.tokens.drop(1)
            .filter { it != meta.eosId && it != meta.padId }
            .map { idToPiece.getOrElse(it) { "" } }
        return tokenizer.decodePieces(pieces)
    }

    /**
     * Логарифмы вероятностей для лучших [k] токенов. Считаем log-softmax, а не
     * берём сырые логиты: складывать между шагами можно только сравнимые
     * величины, иначе beam search сравнивает несравнимое.
     */
    private fun topKLogProbs(
        buffer: FloatBuffer,
        offset: Int,
        vocab: Int,
        k: Int,
    ): List<Pair<Int, Double>> {
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until vocab) {
            val value = buffer.get(offset + i)
            if (value > max) max = value
        }
        var sum = 0.0
        for (i in 0 until vocab) {
            sum += exp((buffer.get(offset + i) - max).toDouble())
        }
        val logSum = ln(sum) + max

        // Частичный отбор k лучших: полная сортировка словаря на 60 тысяч токенов
        // на каждом шаге каждого лука обошлась бы дороже самого инференса.
        val bestIds = IntArray(k) { -1 }
        val bestValues = DoubleArray(k) { Double.NEGATIVE_INFINITY }
        for (i in 0 until vocab) {
            val value = buffer.get(offset + i).toDouble()
            if (value <= bestValues[k - 1]) continue
            var pos = k - 1
            while (pos > 0 && bestValues[pos - 1] < value) {
                bestValues[pos] = bestValues[pos - 1]
                bestIds[pos] = bestIds[pos - 1]
                pos--
            }
            bestValues[pos] = value
            bestIds[pos] = i
        }

        val out = ArrayList<Pair<Int, Double>>(k)
        for (i in 0 until k) {
            if (bestIds[i] < 0) continue
            out.add(bestIds[i] to (bestValues[i] - logSum))
        }
        return out
    }

    /** Повторяет скрытые состояния энкодера на все луки одним батчем. */
    private fun tileHidden(
        hiddenFlat: FloatArray,
        sourceLen: Int,
        model: Int,
        batch: Int,
    ): OnnxTensor {
        val perItem = sourceLen * model
        val data = FloatArray(batch * perItem)
        for (b in 0 until batch) {
            System.arraycopy(hiddenFlat, 0, data, b * perItem, perItem)
        }
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(batch.toLong(), sourceLen.toLong(), model.toLong()),
        )
    }

    private fun encodeSource(text: String): LongArray {
        val prepared = meta.sourcePrefix + text
        val pieces = tokenizer.encode(prepared)
        if (pieces.isEmpty()) return LongArray(0)
        val limit = meta.maxSourceTokens - 1
        val ids = ArrayList<Long>(minOf(pieces.size, limit) + 1)
        for (piece in pieces) {
            if (ids.size >= limit) break
            ids.add((pieceToId[piece] ?: meta.unkId).toLong())
        }
        if (meta.appendEosToSource) ids.add(meta.eosId.toLong())
        return ids.toLongArray()
    }

    private fun tensor(data: LongArray, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    override fun close() {
        runCatching { decoder.close() }
        runCatching { encoder.close() }
    }

    companion object {

        fun load(
            encoderPath: String,
            decoderPath: String,
            sourceSpmJson: String,
            vocabJson: String,
            metaJson: String,
            numThreads: Int = 2,
            beams: Int = 4,
        ): Seq2SeqOnnx {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(numThreads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val vocab = JSONObject(vocabJson)
            val pieceToId = HashMap<String, Int>(vocab.length() * 2)
            var maxId = 0
            for (key in vocab.keys()) {
                val id = vocab.getInt(key)
                pieceToId[key] = id
                if (id > maxId) maxId = id
            }
            val idToPiece = Array(maxId + 1) { "" }
            for ((piece, id) in pieceToId) idToPiece[id] = piece

            val metaObj = JSONObject(metaJson)
            val padId = metaObj.optInt("pad_token_id", pieceToId["<pad>"] ?: 0)
            val meta = Meta(
                padId = padId,
                eosId = metaObj.optInt("eos_token_id", pieceToId["</s>"] ?: 0),
                unkId = metaObj.optInt("unk_token_id", pieceToId["<unk>"] ?: 1),
                decoderStartId = metaObj.optInt("decoder_start_token_id", padId),
                maxSourceTokens = metaObj.optInt("max_source_tokens", 256),
                maxTargetTokens = metaObj.optInt("max_target_tokens", 200),
                sourcePrefix = metaObj.optString("source_prefix", ""),
                appendEosToSource = metaObj.optBoolean("append_eos_to_source", true),
            )

            return Seq2SeqOnnx(
                env = env,
                encoder = env.createSession(encoderPath, options),
                decoder = env.createSession(decoderPath, options),
                tokenizer = SentencePieceUnigram.fromJson(sourceSpmJson),
                pieceToId = pieceToId,
                idToPiece = idToPiece,
                meta = meta,
                beams = beams.coerceIn(1, 8),
            )
        }
    }
}
