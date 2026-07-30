package ru.translate.overlay.mt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.translate.overlay.core.SourceLang
import ru.translate.overlay.models.ModelKeys
import ru.translate.overlay.models.ModelStore
import java.io.File

/**
 * Перевод компактными билингвальными моделями Opus-MT в ONNX.
 *
 * Это основной бэкенд. Проверка на живых фразах показала, что почти все ошибки,
 * замеченные в первой версии, были ошибками ML Kit, а не пайплайна: терялся род
 * («монахином, которому», «Карла никогда не видел») и куски смысла («и было
 * ужасно» вместо «была в ужасе от этого вида»). Opus-MT на тех же фразах даёт
 * правильный род и сохраняет смысл.
 *
 * Две вещи, без которых качество проседает:
 *  * **beam search вместо жадного декодирования.** Модели обучались с
 *    `num_beams = 4`; замер показал, что жадный поиск меняет результат в
 *    четырёх фразах из шести, и всегда к худшему — теряются уточнения вроде
 *    «все равно».
 *  * **перевод по предложениям.** Модель обучена на отдельных предложениях, а
 *    в первой версии ей подавали весь распознанный кусок целиком — на реальном
 *    примере это было четыре предложения одной строкой.
 *
 * Для китайского прямой модели zh→ru не существует, поэтому он идёт zh→en→ru.
 */
class OpusMtTranslator private constructor(
    private val stages: List<Seq2SeqOnnx>,
) : Translator {

    override suspend fun prepare(onProgress: (String, Int) -> Unit) = Unit

    override suspend fun translate(text: String): MtResult = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        val sentences = TextSplitter.split(text)
        val out = StringBuilder(text.length + 16)
        for (sentence in sentences) {
            var current = sentence
            for (stage in stages) {
                current = stage.translate(current)
                if (current.isBlank()) break
            }
            if (current.isBlank()) continue
            if (out.isNotEmpty()) out.append(' ')
            out.append(current.trim())
        }
        MtResult(out.toString().trim(), (System.nanoTime() - started) / 1_000_000)
    }

    override fun release() {
        stages.forEach { runCatching { it.close() } }
    }

    companion object {

        private const val TAG = "OpusMtTranslator"

        /** Ключи файлов одной пары. */
        fun keysFor(pair: String): List<String> = listOf(
            ModelKeys.mtEncoder(pair),
            ModelKeys.mtDecoder(pair),
            ModelKeys.mtSource(pair),
            ModelKeys.mtTarget(pair),
            ModelKeys.mtMeta(pair),
        )

        /**
         * Выбирает лучшую доступную цепочку, докачивает модели и поднимает их.
         *
         * Бросает исключение, если моделей нет: вызывающая сторона откатывается
         * на быстрый бэкенд, а не падает.
         */
        suspend fun create(
            lang: SourceLang,
            store: ModelStore,
            beams: Int,
            onProgress: (String, Int) -> Unit = { _, _ -> },
        ): OpusMtTranslator {
            val chains = lang.opusMtChains
            require(chains.isNotEmpty()) { "Для ${lang.title} перевод не нужен" }

            var pairs: List<String>? = null
            var entries: List<ModelStore.Entry> = emptyList()
            var lastError: Throwable? = null
            for (candidate in chains) {
                try {
                    entries = store.resolve(candidate.flatMap { keysFor(it) })
                    pairs = candidate
                    break
                } catch (t: Throwable) {
                    lastError = t
                    Log.i(TAG, "Цепочка $candidate недоступна: ${t.message}")
                }
            }
            if (pairs == null) {
                throw lastError ?: IllegalStateException("Модели Opus-MT недоступны")
            }

            store.ensure(entries, onProgress)
            val byKey = entries.associateBy { it.key }

            val stages = pairs.map { pair ->
                fun path(key: String) = store.localPath(byKey.getValue(key))
                withContext(Dispatchers.IO) {
                    Seq2SeqOnnx.load(
                        encoderPath = path(ModelKeys.mtEncoder(pair)),
                        decoderPath = path(ModelKeys.mtDecoder(pair)),
                        sourceSpmJson = File(path(ModelKeys.mtSource(pair))).readText(),
                        vocabJson = File(path(ModelKeys.mtTarget(pair))).readText(),
                        metaJson = File(path(ModelKeys.mtMeta(pair))).readText(),
                        beams = beams,
                    )
                }
            }
            return OpusMtTranslator(stages)
        }
    }
}
