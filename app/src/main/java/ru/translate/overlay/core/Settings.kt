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
        get() = enumOr(KEY_PROFILE, Profile.SPEED)
        set(value) = prefs.edit().putString(KEY_PROFILE, value.name).apply()

    var mtBackend: MtBackend
        get() = enumOr(KEY_MT, MtBackend.ML_KIT)
        set(value) = prefs.edit().putString(KEY_MT, value.name).apply()

    var denoise: DenoiseMode
        get() = enumOr(KEY_DENOISE, DenoiseMode.OFF)
        set(value) = prefs.edit().putString(KEY_DENOISE, value.name).apply()

    /** Восстановление пунктуации после ASR. Работает только для en/zh. */
    var punctuation: Boolean
        get() = prefs.getBoolean(KEY_PUNCT, false)
        set(value) = prefs.edit().putBoolean(KEY_PUNCT, value).apply()

    /** Озвучка перевода. По умолчанию выключена — см. docs/01-research.md §7. */
    var tts: Boolean
        get() = prefs.getBoolean(KEY_TTS, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS, value).apply()

    /**
     * Склеивать короткие обрывки фраз перед переводом.
     *
     * ТЗ просит учитывать контекст предыдущих фраз для связности. Ни ML Kit, ни
     * обычный Opus-MT не принимают контекст отдельным параметром, поэтому
     * единственный честный способ — склеить обрывок со следующей фразой и
     * перевести их вместе. Цена: обрывок ждёт продолжения, задержка растёт.
     * Поэтому по умолчанию выключено.
     */
    var mergeFragments: Boolean
        get() = prefs.getBoolean(KEY_MERGE, false)
        set(value) = prefs.edit().putBoolean(KEY_MERGE, value).apply()

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
        const val KEY_OPACITY = "overlay_opacity"
        const val KEY_FONT = "overlay_font_sp"
        const val KEY_SHOW_SOURCE = "show_source_text"
        const val KEY_THERMAL = "thermal_throttle"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
    }
}
