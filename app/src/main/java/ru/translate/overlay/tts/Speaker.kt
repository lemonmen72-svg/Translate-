package ru.translate.overlay.tts

/**
 * Озвучка перевода.
 *
 * Ключевое в этом интерфейсе — [speakAndWait]: он возвращает управление только
 * когда фраза **дочитана до конца**. Без этого невозможно построить очередь, а
 * без очереди озвучка не работает вообще.
 *
 * Почему так. В потоковом режиме реплики приходят каждые 1–3 секунды, а чтение
 * одного предложения занимает 3–5. В первой версии каждая новая реплика вызывала
 * stop() и обрывала предыдущую, не дав ей прозвучать, — на слух это выглядело как
 * «звука нет совсем». Правильное поведение: складывать фразы в очередь и читать
 * подряд, отставая от видео, но ничего не теряя.
 */
interface Speaker {

    /** Идёт ли проигрывание прямо сейчас — для индикатора состояния. */
    val isSpeaking: Boolean

    /**
     * Что это за движок — показывается в интерфейсе.
     *
     * Нужно потому, что при жалобе «звука нет» иначе не отличить неподнявшийся
     * Piper от молчащего системного TTS, а от этого зависит, где искать причину.
     */
    val name: String

    /**
     * Проигрывает текст и возвращает управление, когда он дочитан.
     *
     * [speed] — множитель скорости речи. Очередь поднимает его, когда отстаёт от
     * видео, чтобы догнать.
     */
    suspend fun speakAndWait(text: String, speed: Float)

    /** Обрывает проигрывание. Зовётся только при остановке сессии. */
    fun stop()

    fun release()
}

/** Обёртка над голосом Piper. */
class PiperSpeaker(private val tts: PiperTts) : Speaker {
    override val isSpeaking: Boolean get() = tts.speaking.get()
    override val name: String get() = "Piper"

    override suspend fun speakAndWait(text: String, speed: Float) =
        tts.speakAndWait(text, speed)

    override fun stop() = tts.stop()
    override fun release() = tts.release()
}

/** Обёртка над системным TTS. */
class SystemSpeaker(private val tts: RussianTts) : Speaker {
    override val isSpeaking: Boolean get() = tts.speaking.get()
    override val name: String get() = "системный TTS"

    override suspend fun speakAndWait(text: String, speed: Float) =
        tts.speakAndWait(text, speed)

    override fun stop() = tts.stop()
    override fun release() = tts.release()
}
