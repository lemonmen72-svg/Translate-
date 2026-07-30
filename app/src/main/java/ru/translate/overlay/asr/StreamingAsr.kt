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

        /** Фраза закончена, текст можно переводить. */
        data class Final(val text: String) : Update

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

    private var stream: OnlineStream = recognizer.createStream()

    /** Последняя отданная в перевод часть — чтобы не переводить одно дважды. */
    private var consumedPrefix = ""

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

        val text = recognizer.getResult(stream).text.trim()

        if (recognizer.isEndpoint(stream)) {
            recognizer.reset(stream)
            val tail = remainder(text)
            consumedPrefix = ""
            return if (tail.isBlank()) Update.Nothing else Update.Final(tail)
        }

        val pending = remainder(text)
        if (pending.isBlank()) return Update.Nothing

        // Внутри фразы отдаём в перевод законченные предложения, не дожидаясь
        // конца всей реплики: так субтитры идут ровным потоком.
        val cut = TextSplitter.lastSentenceBoundary(pending)
        if (cut > 0) {
            val ready = pending.substring(0, cut).trim()
            consumedPrefix = text.substring(0, consumedPrefix.length + cut)
            return Update.Final(ready)
        }

        return Update.Partial(pending)
    }

    /** Досасывает хвост при остановке сессии. */
    fun finish(): String {
        stream.inputFinished()
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        val tail = remainder(recognizer.getResult(stream).text.trim())
        consumedPrefix = ""
        return tail
    }

    private fun remainder(full: String): String =
        if (full.startsWith(consumedPrefix)) {
            full.substring(consumedPrefix.length).trim()
        } else {
            // Гипотеза переписалась целиком — так бывает, транскрипция уточняется
            // задним числом. Тогда честнее начать с чистого листа.
            consumedPrefix = ""
            full
        }

    fun release() {
        runCatching { stream.release() }
        runCatching { recognizer.release() }
    }
}
