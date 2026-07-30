package ru.translate.overlay.mt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.Closeable
import java.nio.LongBuffer

/**
 * Инференс одной модели Opus-MT (архитектура Marian) через onnxruntime.
 *
 * Декодирование жадное и **без KV-кеша**: на каждом шаге декодеру подаётся вся
 * последовательность заново. Это O(n²) вместо O(n), но для фразы субтитров
 * (десятки токенов) на модели ~75M параметров разница невелика, зато не нужно
 * возиться с именами past_key_values и ветвлением merged-декодера — а это
 * основной источник ошибок при работе с экспортом Marian в ONNX. KV-кеш —
 * понятная следующая оптимизация, если замеры покажут, что он нужен.
 */
class MarianOnnx private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val tokenizer: SentencePieceUnigram,
    private val pieceToId: Map<String, Int>,
    private val idToPiece: Array<String>,
    private val meta: Meta,
) : Closeable {

    data class Meta(
        val padId: Int,
        val eosId: Int,
        val unkId: Int,
        val decoderStartId: Int,
        val maxSourceTokens: Int,
        val maxTargetTokens: Int,
    )

    fun translate(text: String): String {
        val sourceIds = encodeSource(text)
        if (sourceIds.isEmpty()) return ""

        val sourceLen = sourceIds.size.toLong()
        val attention = LongArray(sourceIds.size) { 1L }

        tensor(sourceIds, 1, sourceLen).use { inputIds ->
            tensor(attention, 1, sourceLen).use { attentionMask ->
                val encoderOut = encoder.run(
                    mapOf(
                        "input_ids" to inputIds,
                        "attention_mask" to attentionMask,
                    )
                )
                encoderOut.use { out ->
                    val hidden = out.get(0) as OnnxTensor
                    return decodeGreedy(hidden, attentionMask)
                }
            }
        }
    }

    private fun decodeGreedy(
        encoderHidden: OnnxTensor,
        encoderAttention: OnnxTensor,
    ): String {
        val generated = ArrayList<Int>(meta.maxTargetTokens)
        generated.add(meta.decoderStartId)

        val pieces = ArrayList<String>(meta.maxTargetTokens)
        while (generated.size <= meta.maxTargetTokens) {
            val ids = LongArray(generated.size) { generated[it].toLong() }
            val next = tensor(ids, 1, ids.size.toLong()).use { decoderInput ->
                val result = decoder.run(
                    mapOf(
                        "input_ids" to decoderInput,
                        "encoder_attention_mask" to encoderAttention,
                        "encoder_hidden_states" to encoderHidden,
                    )
                )
                result.use { out ->
                    val logits = out.get(0) as OnnxTensor
                    argmaxLastStep(logits, ids.size)
                }
            }
            if (next == meta.eosId || next == meta.padId) break
            generated.add(next)
            pieces.add(idToPiece.getOrElse(next) { "" })
        }
        return tokenizer.decodePieces(pieces)
    }

    /** Берёт argmax по последнему шагу из logits формы [1, T, V]. */
    private fun argmaxLastStep(logits: OnnxTensor, steps: Int): Int {
        val shape = logits.info.shape
        val vocab = shape[shape.size - 1].toInt()
        val buffer = logits.floatBuffer
        val offset = (steps - 1) * vocab
        var bestId = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until vocab) {
            val value = buffer.get(offset + i)
            if (value > bestValue) {
                bestValue = value
                bestId = i
            }
        }
        return bestId
    }

    private fun encodeSource(text: String): LongArray {
        val pieces = tokenizer.encode(text)
        if (pieces.isEmpty()) return LongArray(0)
        val limit = meta.maxSourceTokens - 1
        val ids = ArrayList<Long>(minOf(pieces.size, limit) + 1)
        for (piece in pieces) {
            if (ids.size >= limit) break
            ids.add((pieceToId[piece] ?: meta.unkId).toLong())
        }
        ids.add(meta.eosId.toLong())
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
        ): MarianOnnx {
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
                // У Marian декодер стартует с pad-токена, а не с bos.
                decoderStartId = metaObj.optInt("decoder_start_token_id", padId),
                maxSourceTokens = metaObj.optInt("max_source_tokens", 256),
                maxTargetTokens = metaObj.optInt("max_target_tokens", 200),
            )

            return MarianOnnx(
                env = env,
                encoder = env.createSession(encoderPath, options),
                decoder = env.createSession(decoderPath, options),
                tokenizer = SentencePieceUnigram.fromJson(sourceSpmJson),
                pieceToId = pieceToId,
                idToPiece = idToPiece,
                meta = meta,
            )
        }
    }
}
