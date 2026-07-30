package ru.translate.overlay.core

/**
 * Исходные языки. Список фиксированный, автоопределения нет — это осознанное
 * решение ТЗ: убирает шаг LID из пайплайна.
 *
 * [whisperCode] — код для forced language Whisper.
 * [mlKitCode] — код языка для ML Kit Translate (BCP-47).
 */
enum class SourceLang(
    val title: String,
    val whisperCode: String,
    val mlKitCode: String,
) {
    RU("Русский", "ru", "ru"),
    EN("Английский", "en", "en"),
    ZH("Китайский", "zh", "zh"),
    JA("Японский", "ja", "ja"),
    ;

    /** Для русского перевод не нужен: целевой язык всегда русский. */
    val needsTranslation: Boolean get() = this != RU

    /**
     * Есть ли модель восстановления пунктуации для этого языка.
     * ct-transformer в sherpa-onnx обучен только на китайском и английском.
     */
    val supportsPunctuationModel: Boolean get() = this == EN || this == ZH

    /**
     * Направления перевода для бэкенда Opus-MT.
     *
     * Прямой модели zh→ru у Helsinki-NLP нет, поэтому китайский идёт через
     * английский. Это та же слабость, за которую ТЗ критиковало универсальные
     * модели, но два прохода по компактной модели (~75M параметров каждая) всё
     * равно быстрее одного прохода NLLB-600M, и архитектура остаётся одна.
     */
    val opusMtPairs: List<String> get() = when (this) {
        EN -> listOf("en-ru")
        JA -> listOf("ja-ru")
        ZH -> listOf("zh-en", "en-ru")
        RU -> emptyList()
    }
}

/**
 * Профиль «скорость vs качество».
 *
 * Это не украшение интерфейса: по замерам (docs/01-research.md §3) только
 * профиль [SPEED] укладывается в заявленные ТЗ 1.5–3 секунды. [QUALITY] честно
 * называется медленным, чтобы пользователь не решил, что приложение зависло.
 */
enum class Profile(
    val title: String,
    val subtitle: String,
    val asrModel: AsrModel,
    /** Сколько тишины ждать, прежде чем считать фразу законченной. */
    val minSilenceSec: Float,
    /** Максимальная длина сегмента: длинный монолог надо резать принудительно. */
    val maxSpeechSec: Float,
    val asrThreads: Int,
) {
    SPEED(
        title = "Скорость",
        subtitle = "Whisper tiny, задержка около 1.5–3 с",
        asrModel = AsrModel.WHISPER_TINY,
        minSilenceSec = 0.30f,
        maxSpeechSec = 8f,
        asrThreads = 4,
    ),
    BALANCED(
        title = "Баланс",
        subtitle = "Whisper base, задержка около 2–4 с",
        asrModel = AsrModel.WHISPER_BASE,
        minSilenceSec = 0.45f,
        maxSpeechSec = 10f,
        asrThreads = 4,
    ),
    QUALITY(
        title = "Качество",
        subtitle = "Whisper small, медленно: 4–6 с, нужно 6+ ГБ RAM",
        asrModel = AsrModel.WHISPER_SMALL,
        minSilenceSec = 0.60f,
        maxSpeechSec = 12f,
        asrThreads = 4,
    ),
}

/** Модели ASR. Имена файлов совпадают с ассетами релиза моделей. */
enum class AsrModel(
    val id: String,
    val title: String,
    /** Примерный размер обеих частей вместе, для показа перед загрузкой. */
    val approxMb: Int,
) {
    // Размеры — фактические по манифесту релиза моделей, а не оценочные.
    WHISPER_TINY("whisper-tiny", "Whisper tiny (int8)", 104),
    WHISPER_BASE("whisper-base", "Whisper base (int8)", 161),
    WHISPER_SMALL("whisper-small", "Whisper small (int8)", 376),
}

/** Бэкенд машинного перевода. */
enum class MtBackend(val title: String, val subtitle: String) {
    ML_KIT(
        title = "Быстрый",
        subtitle = "ML Kit, десятки миллисекунд. Модели скачиваются один раз",
    ),
    OPUS_MT(
        title = "Точный",
        subtitle = "Opus-MT в ONNX. Прямой перевод ja→ru без английского посредника",
    ),
}

/**
 * Что делать с фоновой музыкой.
 *
 * По умолчанию выключено: RNNoise-подобные лёгкие модели рассчитаны на
 * стационарный шум, а на музыке могут внести артефакты, которых Whisper не
 * видел при обучении, и сделать хуже. Включать имеет смысл только после
 * сравнения на своём контенте.
 */
enum class DenoiseMode(val title: String) {
    OFF("Выключено"),
    GTCRN("GTCRN (эксперимент)"),
}
