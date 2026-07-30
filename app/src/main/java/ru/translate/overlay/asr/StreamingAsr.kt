package ru.translate.overlay.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import ru.translate.overlay.capture.AudioCapture
import ru.translate.overlay.mt.TextSplitter

/**
 * Потоковое распознавание: текст появляется по ходу речи, а не после её конца.
 *
 * Это ответ на главную претензию к первой версии — «пока видео не остановить,
 * перевод не начнётся». Прежняя схема ждала паузы в речи (VAD), потом целиком
 * прогоняла Whisper по всему сегменту. На непрерывном закадровом тексте, где
 * пауз почти нет, сегмент разрастался до предела, и первый перевод появлялся
 * через десяток секунд.
 *
 * Здесь модель обрабатывает поток чанками по 160 мс и после каждого отдаёт
 * текущую гипотезу. Как только гипотеза дорастает до законченного предложения
 * или срабатывает детектор конца фразы, предложение уходит в перевод. Ждать
 * тишины больше не нужно.
 */
class StreamingAsr(
    encoderPath: String,
    decoderPath: String,
    joinerPath: String,
    tokensPath: String,
    numThreads: Int,
    /**
     * Сколько тишины считать концом фразы. Меньше — быстрее реакция, но чаще
     * рвутся фразы на середине.
     */
    endpointSilenceSec: Float = 0.8f,
) {

    /** Что делать с текущей гипотезой. */
    sealed interface Update {
        /** Гипотеза ещё уточняется: можно показать как предварительный текст. */
        data class Partial(val text: String) : Update

        /**
         * Текст можно переводить.
         *
         * [forced] — кусок отрезан не по знаку препинания, а по длине или времени,
         * то есть это заведомо середина фразы. Такой кусок нельзя придерживать в
         * ожидании продолжения: он отрезан именно ради того, чтобы показать перевод
         * прямо сейчас.
         */
        data class Final(val text: String, val forced: Boolean = false) : Update

        data object Nothing : Update
    }

    private val recognizer = OnlineRecognizer(
        assetManager = null,
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = AudioCapture.SAMPLE_RATE,
                featureDim = 80,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    joiner = joinerPath,
                ),
                tokens = tokensPath,
                numThreads = numThreads,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            endpointConfig = EndpointConfig(
                // rule1: тишина без речи вообще — длинная пауза между репликами.
                rule1 = EndpointRule(false, endpointSilenceSec * 2f, 0f),
                // rule2: тишина после речи — основной признак конца фразы.
                rule2 = EndpointRule(true, endpointSilenceSec, 0f),
                // rule3: страховка от бесконечной фразы в непрерывном монологе.
                rule3 = EndpointRule(false, 0f, 12f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )

    private val stream: OnlineStream = recognizer.createStream()

    /**
     * Сколько символов текущей гипотезы уже отдано в перевод.
     *
     * Именно счётчик символов, а не сохранённая строка-префикс: в промежуточных
     * вариантах пробелы по краям обрезаются, и сравнение строк давало съезжающие
     * смещения — часть текста уходила в перевод дважды.
     */
    private var consumedChars = 0

    /** Когда последний раз что-то ушло в перевод. Нужен для нарезки по времени. */
    private var lastEmitAtMs = System.currentTimeMillis()

    /**
     * Скармливает кадр и возвращает, что делать дальше.
     *
     * Вызывать из фонового потока: внутри JNI и инференс.
     */
    fun push(frame: FloatArray): Update {
        stream.acceptWaveform(frame, AudioCapture.SAMPLE_RATE)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }

        val text = recognizer.getResult(stream).text

        // Гипотеза может укоротиться: распознавание уточняет её задним числом.
        // Тогда прежнее смещение бессмысленно.
        if (consumedChars > text.length) consumedChars = 0

        if (recognizer.isEndpoint(stream)) {
            recognizer.reset(stream)
            val tail = text.substring(consumedChars).trim()
            consumedChars = 0
            lastEmitAtMs = System.currentTimeMillis()
            return if (tail.isEmpty()) Update.Nothing else Update.Final(tail)
        }

        val rest = text.substring(consumedChars)
        if (rest.isBlank()) return Update.Nothing

        // Внутри реплики отдаём в перевод законченные предложения, не дожидаясь
        // её конца: так субтитры идут ровным потоком, а не абзацем.
        val cut = TextSplitter.lastSentenceBoundary(rest)
        if (cut > 0) {
            val ready = rest.substring(0, cut).trim()
            consumedChars += cut
            if (ready.isNotEmpty()) {
                lastEmitAtMs = System.currentTimeMillis()
                return Update.Final(ready)
            }
        }

        // Принудительная нарезка — исправление настоящего дефекта.
        //
        // Раньше кусок отдавался в перевод ТОЛЬКО по знаку препинания. Если модель
        // их не ставит — а этого никто не гарантирует, — на сплошном разговоре не
        // отдавалось ничего до самой паузы. Пользователь видел ровно это: перевод
        // появлялся, только когда речь останавливалась, и то сразу тремя кусками
        // подряд, потому что накопленная реплика разом резалась на предложения.
        //
        // Теперь кусок уходит и без знаков: когда накопилось много текста или
        // прошло много времени. Режем по последнему пробелу, чтобы не разрывать
        // слово, и оставляем текущее недоговорённое слово следующему разу.
        val forcedCut = forcedBoundary(rest)
        if (forcedCut > 0) {
            val ready = rest.substring(0, forcedCut).trim()
            consumedChars += forcedCut
            if (ready.isNotEmpty()) {
                lastEmitAtMs = System.currentTimeMillis()
                return Update.Final(ready, forced = true)
            }
        }

        return Update.Partial(rest.trim())
    }

    /**
     * Где резать кусок, если знаков препинания нет. 0 — резать рано.
     *
     * Два условия, и оба нужны. По длине — чтобы на быстрой речи текст шёл
     * потоком. По времени — чтобы на медленной речи не приходилось ждать, пока
     * наберётся длина.
     */
    private fun forcedBoundary(rest: String): Int {
        val longEnough = rest.length >= FORCE_CHARS
        val waitedLong = System.currentTimeMillis() - lastEmitAtMs >= FORCE_MS &&
            rest.length >= FORCE_MIN_CHARS
        if (!longEnough && !waitedLong) return 0

        // Последнее слово ещё договаривается и может быть уточнено задним числом,
        // поэтому отдаём всё до последнего пробела, а его оставляем.
        val lastSpace = rest.trimEnd().lastIndexOf(' ')
        return if (lastSpace > 0) lastSpace else 0
    }

    /** Досасывает хвост при остановке сессии. */
    fun finish(): String {
        stream.inputFinished()
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        val text = recognizer.getResult(stream).text
        val tail = if (consumedChars <= text.length) {
            text.substring(consumedChars)
        } else {
            text
        }
        consumedChars = 0
        lastEmitAtMs = System.currentTimeMillis()
        return tail.trim()
    }

    fun release() {
        runCatching { stream.release() }
        runCatching { recognizer.release() }
    }

    private companion object {
        /** Столько накопленного текста уже стоит показать, не дожидаясь точки. */
        const val FORCE_CHARS = 70

        /** Столько ждём, прежде чем показать даже короткий кусок. */
        const val FORCE_MS = 2500L

        /** Короче этого не режем даже по таймауту: получится огрызок. */
        const val FORCE_MIN_CHARS = 25
    }
}
