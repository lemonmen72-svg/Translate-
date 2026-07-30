package ru.translate.overlay.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Озвучка перевода одним русским голосом.
 *
 * Взят системный TTS Android, а не Silero или Piper из ТЗ. Причины:
 *  - русский голос уже есть почти на любом устройстве, ничего не качаем и не
 *    держим в оперативной памяти;
 *  - у моделей Silero лицензия CC BY-NC, что закрывает любое распространение;
 *  - Piper требует каталог espeak-ng-data — десятки мегабайт мелких файлов,
 *    которые пришлось бы тащить в APK ради необязательной функции.
 * Цена: голос не такой естественный, как у Silero, и это компонент системы, а
 * не наш собственный. Замена на Piper или Silero — понятный путь улучшения.
 *
 * Главная проблема озвучки: наш собственный русский голос играется как аудио и
 * попадает в тот же захват через AudioPlaybackCaptureConfiguration — пайплайн
 * начинает распознавать сам себя.
 *
 * Сначала это лечилось грубо: пока идёт озвучка, кадры в VAD не подавались. На
 * практике вышло хуже болезни — озвучка длинной фразы занимает десятки секунд, и
 * всё это время приложение глухое, из-за чего перевод выглядит как «начинается
 * только когда остановишь видео».
 *
 * Правильное решение: проигрывать озвучку с usage, которого нет в списке
 * захватываемых. Захват ловит USAGE_MEDIA, USAGE_GAME и USAGE_UNKNOWN, а
 * USAGE_ASSISTANT не ловит — значит свой голос в пайплайн не попадёт, и слушать
 * можно не переставая. Флаг [speaking] остаётся для индикатора и для устройств,
 * где прошивка всё равно захватывает наш поток: на них можно включить
 * «Не слушать во время озвучки».
 */
class RussianTts(context: Context) {

    private val appContext: Context = context.applicationContext

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Идёт ли проигрывание прямо сейчас: пайплайн обязан на это смотреть. */
    val speaking = AtomicBoolean(false)

    private var ready = false
    private var engine: TextToSpeech? = null

    private var counter = 0L

    suspend fun init(): Boolean = suspendCancellableCoroutine { cont ->
        val tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val result = engine?.setLanguage(Locale("ru", "RU"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "Русский голос недоступен в системном TTS")
                    ready = false
                }
            }
            if (cont.isActive) cont.resume(ready)
        }
        engine = tts
        // Ключевая настройка: наш голос не должен попадать в собственный захват.
        runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking.set(true)
            }

            override fun onDone(utteranceId: String?) {
                speaking.set(false)
            }

            @Deprecated("Требуется базовым классом")
            override fun onError(utteranceId: String?) {
                speaking.set(false)
            }
        })
        cont.invokeOnCancellation { runCatching { tts.shutdown() } }
    }

    /**
     * Проигрывает текст. Возвращает управление сразу.
     *
     * [continuePhrase] = false начинает новую фразу и сбрасывает очередь:
     * устаревший перевод озвучивать бессмысленно, субтитры к видео живут
     * считаные секунды. true дочитывает следующее предложение той же фразы.
     */
    fun speak(text: String, continuePhrase: Boolean = false) {
        val tts = engine ?: return
        if (!ready || text.isBlank()) return
        speaking.set(true)
        counter += 1
        val mode =
            if (continuePhrase) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        tts.speak(text, mode, null, "u$counter")
    }

    /** Приглушает исходную дорожку, чтобы два голоса не накладывались. */
    fun duckOriginal(duck: Boolean) {
        runCatching {
            if (duck) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    0,
                )
            } else {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    0,
                )
            }
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
        speaking.set(false)
    }

    fun release() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        speaking.set(false)
    }

    private companion object {
        const val TAG = "RussianTts"
    }
}
