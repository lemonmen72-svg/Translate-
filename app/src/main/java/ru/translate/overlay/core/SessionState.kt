package ru.translate.overlay.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * Состояние сессии.
 *
 * [Skipped] и [Throttled] — не декорация. ТЗ просит индикатор, «чтобы было
 * понятно, что происходит, а не зависло ли»; именно эти два состояния иначе
 * выглядят как зависание.
 */
sealed interface Stage {
    data object Idle : Stage
    data object Loading : Stage
    data object Listening : Stage
    data object Recognizing : Stage
    data object Translating : Stage
    data object Speaking : Stage

    /** Сегмент отброшен: галлюцинация ASR или сброс по back-pressure. */
    data class Skipped(val reason: String) : Stage

    /** Нагрев: нагрузка снижена автоматически. */
    data class Throttled(val detail: String) : Stage

    data class Error(val message: String) : Stage

    val label: String
        get() = when (this) {
            Idle -> "Остановлено"
            Loading -> "Загрузка моделей"
            Listening -> "Слушаю"
            Recognizing -> "Распознаю"
            Translating -> "Перевожу"
            Speaking -> "Озвучиваю"
            is Skipped -> "Пропущено: $reason"
            is Throttled -> "Троттлинг: $detail"
            is Error -> "Ошибка: $message"
        }
}

/** Одна переведённая фраза. */
data class Phrase(
    val id: Long,
    /** Момент завершения фразы по VAD, для расчёта задержки. */
    val endedAtMs: Long,
    val sourceText: String,
    val translatedText: String,
    val timings: Timings,
    /**
     * Метка говорящего вида «Голос 2», или null, если определить не удалось:
     * реплика короткая, модель не поднялась или различение выключено.
     */
    val speaker: String? = null,
)

/** Замеры по стадиям, миллисекунды. Без них бюджет задержки настраивать нечем. */
data class Timings(
    val segmentDurationMs: Long = 0,
    val denoiseMs: Long = 0,
    val asrMs: Long = 0,
    val punctuationMs: Long = 0,
    val mtMs: Long = 0,
) {
    /** Полная задержка пайплайна от конца фразы до готового перевода. */
    val totalMs: Long get() = denoiseMs + asrMs + punctuationMs + mtMs

    fun summary(): String = buildString {
        append("итого ${totalMs} мс")
        append(" (ASR $asrMs")
        if (mtMs > 0) append(", MT $mtMs")
        if (denoiseMs > 0) append(", денойз $denoiseMs")
        if (punctuationMs > 0) append(", пунктуация $punctuationMs")
        append(")")
    }
}

/**
 * Общий на всё приложение держатель состояния: сервис пишет, UI и оверлей
 * читают. Синглтон, потому что оверлей живёт вне Activity и своего
 * ViewModel-скоупа у него нет.
 */
object SessionState {

    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private val _current = MutableStateFlow<Phrase?>(null)
    val current: StateFlow<Phrase?> = _current.asStateFlow()

    /**
     * Поток фраз для оверлея — именно канал, а не StateFlow.
     *
     * StateFlow конфлейтит: когда перевод отдаёт несколько предложений подряд
     * быстрее, чем оверлей успевает их забрать, промежуточные значения теряются
     * молча. Для индикатора это нормально, для субтитров — потеря текста. Канал с
     * запасом хранит все фразы, а при переполнении вытесняет самые старые, потому
     * что показывать пятиминутной давности перевод бессмысленно.
     */
    private val phraseEvents = Channel<Phrase>(
        capacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val phrases: Flow<Phrase> = phraseEvents.receiveAsFlow()

    private val _history = MutableStateFlow<List<Phrase>>(emptyList())
    val history: StateFlow<List<Phrase>> = _history.asStateFlow()

    private val _skipped = MutableStateFlow(0)

    /** Сколько сегментов отброшено за сессию: галлюцинации плюс back-pressure. */
    val skipped: StateFlow<Int> = _skipped.asStateFlow()

    /**
     * Что реально поднялось для озвучки: «Ирина (Piper)», «системный TTS» или
     * «не поднялась». Без этого «звука нет» неотличимо от «звук есть, но тихий», и
     * причину искать негде.
     */
    private val _voiceEngine = MutableStateFlow("")

    /** Сколько разных голосов уже различил распознаватель говорящих. */
    private val _speakerCount = MutableStateFlow(0)

    private val _speechLag = MutableStateFlow(0)

    /**
     * Сколько фраз ждёт озвучки. Показывается пользователю: озвучка неизбежно
     * отстаёт от видео, и лучше видеть насколько, чем догадываться.
     */
    val speechLag: StateFlow<Int> = _speechLag.asStateFlow()
    val voiceEngine: StateFlow<String> = _voiceEngine.asStateFlow()
    val speakerCount: StateFlow<Int> = _speakerCount.asStateFlow()

    private val _partial = MutableStateFlow("")

    /**
     * Предварительная гипотеза потокового распознавания: показывается сразу, до
     * перевода, чтобы было видно, что приложение слышит речь.
     */
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _overlaySettingsVersion = MutableStateFlow(0)

    /**
     * Счётчик правок настроек оверлея. UI его увеличивает, сервис слушает и
     * применяет изменения к живому окну — иначе ползунки шрифта и прозрачности
     * действовали бы только со следующей сессии.
     */
    val overlaySettingsVersion: StateFlow<Int> = _overlaySettingsVersion.asStateFlow()

    private val _dropped = MutableStateFlow(0)

    /** Сколько сегментов сброшено именно из-за отставания пайплайна. */
    val dropped: StateFlow<Int> = _dropped.asStateFlow()

    fun setStage(stage: Stage) {
        _stage.value = stage
    }

    fun publish(phrase: Phrase) {
        _current.value = phrase
        phraseEvents.trySend(phrase)
        _history.update { (it + phrase).takeLast(MAX_HISTORY) }
    }

    fun setVoiceEngine(what: String) {
        _voiceEngine.value = what
    }

    fun setSpeakerCount(count: Int) {
        _speakerCount.value = count.coerceAtLeast(0)
    }

    fun setSpeechLag(count: Int) {
        _speechLag.value = count.coerceAtLeast(0)
    }

    fun setPartial(text: String) {
        _partial.value = text
    }

    fun notifyOverlaySettingsChanged() {
        _overlaySettingsVersion.update { it + 1 }
    }

    fun noteSkipped() {
        _skipped.update { it + 1 }
    }

    fun noteDropped() {
        _dropped.update { it + 1 }
        _skipped.update { it + 1 }
    }

    fun reset() {
        _stage.value = Stage.Idle
        _current.value = null
        _partial.value = ""
        _speechLag.value = 0
        _voiceEngine.value = ""
        _speakerCount.value = 0
        _skipped.value = 0
        _dropped.value = 0
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    private const val MAX_HISTORY = 400
}
