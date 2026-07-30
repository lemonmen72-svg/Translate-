package ru.translate.overlay.tts

import android.content.Context
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
 * Главная проблема озвучки, которой в ТЗ не было: наш собственный русский голос
 * играется как обычное media-аудио и попадает в тот же захват через
 * AudioPlaybackCaptureConfiguration — пайплайн начинает распознавать сам себя.
 * Понижение громкости оригинала это не лечит, потому что источником становимся
 * мы. Поэтому [speaking] выставляется на всё время проигрывания, а пайплайн по
 * этому флагу не подаёт кадры в VAD.
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
     * Проигрывает текст. Возвращает управление сразу — ждать окончания должен
     * пайплайн по флагу [speaking].
     */
    fun speak(text: String) {
        val tts = engine ?: return
        if (!ready || text.isBlank()) return
        speaking.set(true)
        counter += 1
        // QUEUE_FLUSH, а не ADD: устаревший перевод озвучивать бессмысленно,
        // субтитры к видео живут считаные секунды.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u$counter")
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
