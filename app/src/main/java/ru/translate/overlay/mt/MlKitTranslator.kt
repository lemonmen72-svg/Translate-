package ru.translate.overlay.mt

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.translate.overlay.core.SourceLang
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Быстрый бэкенд перевода: ML Kit Translate.
 *
 * Почему он по умолчанию, хотя ТЗ предлагало NLLB и Opus-MT: по замерам из
 * docs/01-research.md §3 NLLB-600M один съедает весь бюджет задержки (около 2 с
 * на фразу). ML Kit укладывается в десятки миллисекунд, работает офлайн после
 * разовой загрузки моделей и покрывает все три нужных направления.
 *
 * Честная цена этого выбора:
 *  - модели скачиваются с серверов Google один раз (дальше офлайн);
 *  - для ja→ru и zh→ru ML Kit внутри переводит через английский как посредник —
 *    ровно та слабость, за которую ТЗ критиковало универсальные модели.
 *    Кому важнее точность, чем скорость, — бэкенд [OpusMtTranslator].
 */
class MlKitTranslator(private val source: SourceLang) : Translator {

    private val client = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(mlKitLanguage(source))
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
    )

    override suspend fun prepare(onProgress: (String, Int) -> Unit) {
        onProgress("Модель перевода ${source.title}→Русский", 0)
        // Без ограничений по сети: пользователь сам нажал «Скачать».
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        onProgress("Модель перевода готова", 100)
    }

    override suspend fun translate(text: String): MtResult {
        val started = System.nanoTime()
        val out = client.translate(text).await()
        return MtResult(out.trim(), (System.nanoTime() - started) / 1_000_000)
    }

    override fun release() = client.close()

    private companion object {

        fun mlKitLanguage(lang: SourceLang): String = when (lang) {
            SourceLang.EN -> TranslateLanguage.ENGLISH
            SourceLang.ZH -> TranslateLanguage.CHINESE
            SourceLang.JA -> TranslateLanguage.JAPANESE
            // Русский сюда не попадает: для него используется NoOpTranslator.
            SourceLang.RU -> TranslateLanguage.RUSSIAN
        }

        /**
         * Мост Task → suspend вручную, чтобы не тащить
         * kotlinx-coroutines-play-services ради одного вызова.
         */
        suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
            addOnSuccessListener { result ->
                if (cont.isActive) cont.resume(result)
            }
            addOnFailureListener { error ->
                if (cont.isActive) cont.resumeWithException(error)
            }
            addOnCanceledListener {
                cont.cancel()
            }
        }
    }
}
