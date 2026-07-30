package ru.translate.overlay.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import ru.translate.overlay.capture.AudioCapture

/**
 * Шумоподавление перед ASR — GTCRN.
 *
 * По умолчанию выключено, и это осознанно. В ТЗ против музыкального фона
 * предлагался RNNoise, но он рассчитан на стационарный шум, а музыка
 * нестационарна и спектрально перекрывается с речью. GTCRN при сопоставимой
 * стоимости (48.2K параметров) работает лучше RNNoise, но у обоих качество
 * падает при низком SNR — то есть именно там, где музыка громкая.
 *
 * Отдельный риск: Whisper обучался на «грязном» вебе, и предобработка может
 * внести артефакты, которых он не видел, сделав хуже. Поэтому это опция, а не
 * стадия по умолчанию — включать после сравнения на своём контенте.
 */
class Denoiser(modelPath: String) {

    private val denoiser = OfflineSpeechDenoiser(
        assetManager = null,
        config = OfflineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = modelPath),
                numThreads = 1,
                provider = "cpu",
            ),
        ),
    )

    /** Возвращает очищенный сегмент; при любой ошибке — исходный. */
    fun process(samples: FloatArray): FloatArray = try {
        val out = denoiser.run(samples, AudioCapture.SAMPLE_RATE)
        out.samples
    } catch (t: Throwable) {
        Log.w(TAG, "Денойз не сработал, беру исходный сегмент", t)
        samples
    }

    fun release() = denoiser.release()

    private companion object {
        const val TAG = "Denoiser"
    }
}
