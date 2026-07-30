package ru.translate.overlay.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Озвучка системным TTS Android.
 *
 * Запасной вариант: звучит механически, зато ничего не качает и работает там, где
 * модель Piper почему-то не поднялась.
 *
 * Голос проигрывается с usage ASSISTANT, которого нет в списке захватываемых
 * (захват ловит MEDIA, GAME и UNKNOWN). Поэтому свой голос в пайплайн не
 * попадает, и глохнуть на время озвучки не нужно.
 */
class RussianTts(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Идёт ли проигрывание прямо сейчас. */
    val speaking = AtomicBoolean(false)

    private var ready = false
    private var engine: TextToSpeech? = null

    private var counter = 0L

    /**
     * Ожидающие завершения фразы.
     *
     * Нужны потому, что TextToSpeech.speak возвращает управление сразу, а очереди
     * озвучки необходимо знать, когда фраза действительно дочитана: иначе
     * следующая наложится на текущую.
     */
    private val waiting = ConcurrentHashMap<String, Continuation<Unit>>()

    suspend fun init(): Boolean = suspendCancellableCoroutine { cont ->
        // Слушатель прогресса и атрибуты выставляются в колбэке инициализации, а
        // не сразу после конструктора: колбэк может сработать раньше, чем
        // присвоится поле engine, и тогда настройки применились бы к null.
        // В первой версии из-за этого не выставлялся русский язык, и системный
        // голос читал русский текст английским движком.
        var created: TextToSpeech? = null
        created = TextToSpeech(appContext) { status ->
            val engineNow = created
            ready = status == TextToSpeech.SUCCESS && engineNow != null
            if (ready && engineNow != null) {
                configure(engineNow)
                val result = engineNow.setLanguage(Locale("ru", "RU"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "Русский голос недоступен в системном TTS")
                    ready = false
                }
            }
            if (cont.isActive) cont.resume(ready)
        }
        engine = created
        cont.invokeOnCancellation { runCatching { created.shutdown() } }
    }

    private fun configure(tts: TextToSpeech) {
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
                finish(utteranceId)
            }

            @Deprecated("Требуется базовым классом")
            override fun onError(utteranceId: String?) {
                speaking.set(false)
                finish(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                speaking.set(false)
                finish(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                speaking.set(false)
                finish(utteranceId)
            }
        })
    }

    private fun finish(utteranceId: String?) {
        val id = utteranceId ?: return
        waiting.remove(id)?.let { runCatching { it.resume(Unit) } }
    }

    /**
     * Проигрывает текст и ждёт, пока он будет дочитан.
     *
     * [speed] — множитель скорости речи: очередь поднимает его, когда отстаёт.
     */
    suspend fun speakAndWait(text: String, speed: Float) {
        val tts = engine
        if (tts == null || !ready || text.isBlank()) return

        val id = "u${++counter}"
        runCatching { tts.setSpeechRate(speed.coerceIn(0.5f, 2.0f)) }

        suspendCancellableCoroutine<Unit> { cont ->
            waiting[id] = cont
            cont.invokeOnCancellation { waiting.remove(id) }
            speaking.set(true)
            // QUEUE_ADD, а не FLUSH: последовательность обеспечивает очередь
            // снаружи, и обрывать уже читаемую фразу нельзя — именно из-за
            // обрывов озвучка не работала совсем.
            val code = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            if (code != TextToSpeech.SUCCESS) {
                waiting.remove(id)
                speaking.set(false)
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
        speaking.set(false)
        // Пробуждаем всех ожидающих, иначе очередь озвучки повиснет навсегда.
        waiting.keys.toList().forEach { finish(it) }
    }

    fun release() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }

    private companion object {
        const val TAG = "RussianTts"
    }
}
