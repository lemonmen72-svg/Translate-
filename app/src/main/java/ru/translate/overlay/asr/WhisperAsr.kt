package ru.translate.overlay.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import ru.translate.overlay.capture.AudioCapture
import ru.translate.overlay.core.SourceLang

/** Результат распознавания одного сегмента. */
data class AsrResult(val text: String, val elapsedMs: Long)

/**
 * ASR на Whisper через sherpa-onnx.
 *
 * Язык задаётся принудительно (`forced language`) — автоопределения нет, это
 * решение ТЗ, оно убирает из пайплайна шаг LID.
 *
 * Сознательно **не** передаём контекст предыдущих фраз в декодер. Contexting на
 * предыдущий текст — известный механизм самоподдерживающихся галлюцинаций
 * Whisper; связность длинных монологов обеспечивает стадия перевода, а не ASR
 * (см. docs/01-research.md §5).
 */
class WhisperAsr(
    encoderPath: String,
    decoderPath: String,
    tokensPath: String,
    lang: SourceLang,
    numThreads: Int,
) {

    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = AudioCapture.SAMPLE_RATE,
                // tiny/base/small используют 80 мел-каналов.
                featureDim = 80,
            ),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    language = lang.whisperCode,
                    // Только транскрипция: перевод делает отдельная стадия
                    // компактной моделью, task="translate" у Whisper умеет
                    // только в английский.
                    task = "transcribe",
                    tailPaddings = 800,
                ),
                tokens = tokensPath,
                modelType = "whisper",
                numThreads = numThreads,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    /** Распознаёт готовый сегмент. Блокирующий вызов, звать с Dispatchers.Default. */
    fun transcribe(samples: FloatArray): AsrResult {
        val started = System.nanoTime()
        val stream = recognizer.createStream()
        val text = try {
            stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return AsrResult(text.trim(), elapsed)
    }

    fun release() = recognizer.release()
}
