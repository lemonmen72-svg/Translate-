package ru.translate.overlay.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig

/**
 * Восстановление пунктуации в распознанном тексте.
 *
 * Без знаков препинания перевод получается корявым: модель MT не видит границ
 * предложений. Whisper обычно пунктуацию ставит сам, поэтому стадия
 * необязательная — включать, если на своём контенте видно, что знаков нет.
 *
 * Ограничение модели: ct-transformer обучен на китайском и английском.
 * Для японского и русского пользы не будет, поэтому стадия применяется только
 * к en и zh.
 */
class Punctuator(modelPath: String) {

    private val punctuation = OfflinePunctuation(
        assetManager = null,
        config = OfflinePunctuationConfig(
            model = OfflinePunctuationModelConfig(
                ctTransformer = modelPath,
                numThreads = 1,
                provider = "cpu",
            ),
        ),
    )

    /** При любой ошибке возвращает исходный текст: пунктуация не стоит падения. */
    fun process(text: String): String = try {
        punctuation.addPunctuation(text)
    } catch (t: Throwable) {
        Log.w(TAG, "Пунктуация не сработала", t)
        text
    }

    fun release() = punctuation.release()

    private companion object {
        const val TAG = "Punctuator"
    }
}
