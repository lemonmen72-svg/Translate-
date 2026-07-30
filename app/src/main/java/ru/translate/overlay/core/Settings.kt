package ru.translate.overlay.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки на SharedPreferences.
 *
 * Намеренно без DataStore: настроек мало, читаются они синхронно при старте
 * сессии, а лишняя зависимость — лишний риск.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sourceLang: SourceLang
        get() = enumOr(KEY_SOURCE, SourceLang.EN)
        set(value) = prefs.edit().putString(KEY_SOURCE, value.name).apply()

    var profile: Profile
        get() = enumOr(KEY_PROFILE, Profile.SYNC)
        set(value) = prefs.edit().putString(KEY_PROFILE, value.name).apply()

    /**
     * Размер чанка потокового распознавания.
     *
     * По умолчанию точный вариант: ошибка распознавания портит перевод сильнее,
     * чем лишние 0.8 секунды задержки, особенно когда озвучка и так отстаёт.
     */
    var chunkSize: ChunkSize
        get() = enumOr(KEY_CHUNK, ChunkSize.ACCURATE)
        set(value) = prefs.edit().putString(KEY_CHUNK, value.name).apply()

    var mtBackend: MtBackend
        get() = enumOr(KEY_MT, MtBackend.OPUS_MT)
        set(value) = prefs.edit().putString(KEY_MT, value.name).apply()

    /**
     * Ширина beam search при переводе.
     *
     * Модели Opus-MT обучались с num_beams = 4, и замер на живых фразах показал,
     * что жадное декодирование (beams = 1) меняет результат в четырёх фразах из
     * шести, всегда к худшему. Меньше 4 ставить есть смысл только ради скорости
     * на слабом устройстве.
     */
    var beams: Int
        get() = prefs.getInt(KEY_BEAMS, 4)
        set(value) = prefs.edit().putInt(KEY_BEAMS, value.coerceIn(1, 8)).apply()

    var voice: Voice
        get() = enumOr(KEY_VOICE, Voice.IRINA)
        set(value) = prefs.edit().putString(KEY_VOICE, value.name).apply()

    /**
     * Не слушать во время озвучки.
     *
     * По умолчанию выключено. Озвучка идёт с usage ASSISTANT, которого нет в
     * списке захватываемых, поэтому свой голос в пайплайн не попадает и глохнуть
     * незачем. Включать только если на конкретной прошивке приложение всё-таки
     * слышит само себя.
     */
    var muteWhileSpeaking: Boolean
        get() = prefs.getBoolean(KEY_MUTE_SPEAKING, false)
        set(value) = prefs.edit().putBoolean(KEY_MUTE_SPEAKING, value).apply()

    var denoise: DenoiseMode
        get() = enumOr(KEY_DENOISE, DenoiseMode.OFF)
        set(value) = prefs.edit().putString(KEY_DENOISE, value.name).apply()

    /** Восстановление пунктуации после ASR. Работает только для en/zh. */
    var punctuation: Boolean
        get() = prefs.getBoolean(KEY_PUNCT, false)
        set(value) = prefs.edit().putBoolean(KEY_PUNCT, value).apply()

    /**
     * Озвучка перевода. Включена по умолчанию.
     *
     * Раньше была выключена из-за петли обратной связи — приложение слышало свой
     * же голос. Петля закрыта: озвучка идёт с usage ASSISTANT, которого нет в
     * списке захватываемых, поэтому прятать функцию за галочкой больше незачем.
     */
    var tts: Boolean
        get() = prefs.getBoolean(KEY_TTS, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS, value).apply()

    /** Базовая скорость чтения. Очередь поднимает её, когда отстаёт. */
    var speechSpeed: Float
        get() = prefs.getFloat(KEY_SPEECH_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_SPEED, value.coerceIn(0.7f, 1.6f)).apply()

    /**
     * Сколько фраз держать в очереди озвучки.
     *
     * Больше — меньше пропусков, но сильнее отставание от видео. Меньше — звук
     * держится ближе к картинке, но чаще теряются фразы.
     */
    var speechQueueDepth: Int
        get() = prefs.getInt(KEY_SPEECH_QUEUE, 4)
        set(value) = prefs.edit().putInt(KEY_SPEECH_QUEUE, value.coerceIn(1, 12)).apply()

    /**
     * Склеивать короткие обрывки фраз перед переводом.
     *
     * ТЗ просит учитывать контекст предыдущих фраз для связности. Ни ML Kit, ни
     * обычный Opus-MT не принимают контекст отдельным параметром, поэтому
     * единственный честный способ — склеить обрывок со следующей фразой и
     * перевести их вместе.
     *
     * Включено по умолчанию: на обрывке модель перевода теряет род и связь слов —
     * это те самые ошибки, что были видны на ролике. Плата за это — задержка, но
     * она ограничена: обрывок ждёт продолжения не дольше 1,2 с, после чего
     * переводится как есть.
     */
    var mergeFragments: Boolean
        get() = prefs.getBoolean(KEY_MERGE, true)
        set(value) = prefs.edit().putBoolean(KEY_MERGE, value).apply()

    /**
     * Различать голоса и помечать реплики в субтитрах.
     *
     * В диалоге без меток две реплики подряд читаются как одна мысль одного
     * человека, хотя это вопрос и ответ. Цена — 28 МБ модели и эмбеддинг на
     * каждую реплику; включено по умолчанию, потому что пользователь просил
     * именно это.
     */
    var speakerLabels: Boolean
        get() = prefs.getBoolean(KEY_SPEAKERS, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAKERS, value).apply()

    /** Прозрачность фона оверлея, 0..1. */
    var overlayOpacity: Float
        get() = prefs.getFloat(KEY_OPACITY, 0.65f)
        set(value) = prefs.edit().putFloat(KEY_OPACITY, value).apply()

    /** Размер шрифта субтитров в sp. */
    var overlayFontSp: Float
        get() = prefs.getFloat(KEY_FONT, 18f)
        set(value) = prefs.edit().putFloat(KEY_FONT, value).apply()

    /** Показывать ли распознанный текст на исходном языке под переводом. */
    var showSourceText: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SOURCE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SOURCE, value).apply()

    /** Автоматически снижать нагрузку при нагреве. */
    var thermalThrottle: Boolean
        get() = prefs.getBoolean(KEY_THERMAL, true)
        set(value) = prefs.edit().putBoolean(KEY_THERMAL, value).apply()

    /** Запомненная позиция оверлея. */
    var overlayX: Int
        get() = prefs.getInt(KEY_OVERLAY_X, 0)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_X, value).apply()

    var overlayY: Int
        get() = prefs.getInt(KEY_OVERLAY_Y, 200)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_Y, value).apply()

    private inline fun <reified T : Enum<T>> enumOr(key: String, fallback: T): T {
        val raw = prefs.getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }

    private companion object {
        const val KEY_SOURCE = "source_lang"
        const val KEY_PROFILE = "profile"
        const val KEY_MT = "mt_backend"
        const val KEY_DENOISE = "denoise"
        const val KEY_PUNCT = "punctuation"
        const val KEY_TTS = "tts"
        const val KEY_MERGE = "merge_fragments"
        const val KEY_BEAMS = "mt_beams"
        const val KEY_VOICE = "voice"
        const val KEY_MUTE_SPEAKING = "mute_while_speaking"
        const val KEY_SPEECH_SPEED = "speech_speed"
        const val KEY_SPEECH_QUEUE = "speech_queue_depth"
        const val KEY_CHUNK = "chunk_size"
        const val KEY_SPEAKERS = "speaker_labels"
        const val KEY_OPACITY = "overlay_opacity"
        const val KEY_FONT = "overlay_font_sp"
        const val KEY_SHOW_SOURCE = "show_source_text"
        const val KEY_THERMAL = "thermal_throttle"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
    }
}
