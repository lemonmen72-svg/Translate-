package ru.translate.overlay.tts

/**
 * Озвучка перевода. Две реализации: голос Piper (живее) и системный TTS
 * (ничего не качает, но звучит механически).
 */
interface Speaker {

    /** Идёт ли проигрывание прямо сейчас — для индикатора состояния. */
    val isSpeaking: Boolean

    /**
     * Проигрывает текст. Для Piper вызов блокирующий, для системного — нет,
     * поэтому пайплайн не должен полагаться на возврат как на признак конца.
     */
    fun speak(text: String, continuePhrase: Boolean)

    /** Отменяет проигрывание: устаревший перевод озвучивать незачем. */
    fun stop()

    fun release()
}

/** Обёртка над голосом Piper. */
class PiperSpeaker(private val tts: PiperTts) : Speaker {
    override val isSpeaking: Boolean get() = tts.speaking.get()

    override fun speak(text: String, continuePhrase: Boolean) {
        if (!continuePhrase) tts.stop()
        tts.speak(text)
    }

    override fun stop() = tts.stop()
    override fun release() = tts.release()
}

/** Обёртка над системным TTS. */
class SystemSpeaker(private val tts: RussianTts) : Speaker {
    override val isSpeaking: Boolean get() = tts.speaking.get()

    override fun speak(text: String, continuePhrase: Boolean) =
        tts.speak(text, continuePhrase)

    override fun stop() = tts.stop()
    override fun release() = tts.release()
}
