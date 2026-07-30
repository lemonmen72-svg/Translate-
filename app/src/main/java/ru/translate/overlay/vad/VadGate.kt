package ru.translate.overlay.vad

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import ru.translate.overlay.capture.AudioCapture

/**
 * Нарезка потока на завершённые фразы по паузам в речи.
 *
 * Главный рычаг субъективной задержки: чем меньше [minSilenceSec], тем быстрее
 * появляется перевод, но тем чаще фраза рвётся на середине. Второе назначение —
 * первая линия защиты от галлюцинаций: в ASR не должно попадать то, что не речь.
 */
class VadGate(
    modelPath: String,
    minSilenceSec: Float,
    maxSpeechSec: Float,
    threshold: Float = 0.5f,
) {

    private val vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                threshold = threshold,
                minSilenceDuration = minSilenceSec,
                // Слишком короткие всплески — это щелчки и удары бита, не речь.
                minSpeechDuration = 0.20f,
                windowSize = WINDOW,
                // Принудительная нарезка длинного монолога: без неё сегмент
                // растёт, а вместе с ним и задержка.
                maxSpeechDuration = maxSpeechSec,
            ),
            sampleRate = AudioCapture.SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    /** Идёт ли речь прямо сейчас — для индикатора состояния. */
    fun isSpeaking(): Boolean = vad.isSpeechDetected()

    /**
     * Отдаёт кадр в VAD и возвращает сегменты, которые завершились именно на
     * этом кадре. Обычно список пустой, иногда в нём один элемент.
     *
     * Кадр должен быть ровно [WINDOW] сэмплов: Silero VAD работает окном
     * фиксированного размера.
     */
    fun push(frame: FloatArray): List<FloatArray> {
        vad.acceptWaveform(frame)
        if (vad.empty()) return emptyList()
        val out = ArrayList<FloatArray>(2)
        while (!vad.empty()) {
            out += vad.front().samples
            vad.pop()
        }
        return out
    }

    /** Досасывает хвост при остановке сессии, чтобы не терять последнюю фразу. */
    fun flush(): List<FloatArray> {
        vad.flush()
        val out = ArrayList<FloatArray>(2)
        while (!vad.empty()) {
            out += vad.front().samples
            vad.pop()
        }
        return out
    }

    fun reset() {
        vad.reset()
        vad.clear()
    }

    fun release() = vad.release()

    companion object {
        /** Размер окна Silero VAD на 16 кГц. */
        const val WINDOW = 512
    }
}
