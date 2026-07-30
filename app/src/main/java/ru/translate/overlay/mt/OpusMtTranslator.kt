package ru.translate.overlay.mt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.translate.overlay.core.SourceLang
import ru.translate.overlay.models.ModelKeys
import ru.translate.overlay.models.ModelStore
import java.io.File

/**
 * Бэкенд перевода на компактных билингвальных моделях Opus-MT в ONNX.
 *
 * Смысл в том, что языков у нас три, а не двести, поэтому модель на ~75M
 * параметров под конкретную пару даёт и точность, и скорость лучше, чем
 * универсальный NLLB-600M. Главный выигрыш — ja→ru напрямую, без английского
 * посредника, который теряет смысл.
 *
 * Для китайского прямой модели у Helsinki-NLP нет, поэтому он идёт двумя шагами
 * zh→en→ru. Два прохода по компактной модели всё равно дешевле одного прохода
 * NLLB-600M, и рантайм остаётся один.
 */
class OpusMtTranslator private constructor(
    private val stages: List<MarianOnnx>,
) : Translator {

    override suspend fun prepare(onProgress: (String, Int) -> Unit) = Unit

    override suspend fun translate(text: String): MtResult = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        var current = text
        for (stage in stages) {
            current = stage.translate(current)
            if (current.isBlank()) break
        }
        MtResult(current.trim(), (System.nanoTime() - started) / 1_000_000)
    }

    override fun release() {
        stages.forEach { runCatching { it.close() } }
    }

    companion object {

        /**
         * Скачивает и поднимает все нужные ступени.
         *
         * Бросает исключение, если моделей нет в релизе: вызывающая сторона
         * откатывается на быстрый бэкенд, а не падает.
         */
        suspend fun create(lang: SourceLang, store: ModelStore): OpusMtTranslator {
            val chains = lang.opusMtChains
            require(chains.isNotEmpty()) { "Для ${lang.title} перевод не нужен" }

            // Берём первую цепочку, для которой в манифесте есть все файлы.
            // Так прямая zh→ru используется, когда она собрана, и происходит
            // откат на пивот через английский, когда её нет.
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
                }
            }
            if (pairs == null) {
                throw lastError ?: IllegalStateException("Модели Opus-MT недоступны")
            }

            store.ensure(entries)
            val byKey = entries.associateBy { it.key }

            val stages = pairs.map { pair ->
                fun path(key: String) = store.localPath(byKey.getValue(key))
                withContext(Dispatchers.IO) {
                    MarianOnnx.load(
                        encoderPath = path(ModelKeys.mtEncoder(pair)),
                        decoderPath = path(ModelKeys.mtDecoder(pair)),
                        sourceSpmJson = File(path(ModelKeys.mtSource(pair))).readText(),
                        vocabJson = File(path(ModelKeys.mtTarget(pair))).readText(),
                        metaJson = File(path(ModelKeys.mtMeta(pair))).readText(),
                    )
                }
            }
            return OpusMtTranslator(stages)
        }

        private fun keysFor(pair: String): List<String> = listOf(
            ModelKeys.mtEncoder(pair),
            ModelKeys.mtDecoder(pair),
            ModelKeys.mtSource(pair),
            ModelKeys.mtTarget(pair),
            ModelKeys.mtMeta(pair),
        )
    }
}
