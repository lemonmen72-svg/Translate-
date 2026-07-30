package ru.translate.overlay.core

/**
 * Исходные языки. Список фиксированный, автоопределения нет — это осознанное
 * решение ТЗ: убирает шаг LID из пайплайна.
 */
enum class SourceLang(
    val title: String,
    val whisperCode: String,
) {
    RU("Русский", "ru"),
    EN("Английский", "en"),
    ZH("Китайский", "zh"),
    JA("Японский", "ja"),
    ;

    /** Для русского перевод не нужен: целевой язык всегда русский. */
    val needsTranslation: Boolean get() = this != RU

    /**
     * Модель потокового распознавания.
     *
     * Для английского и китайского берём zh-en с восстановлением пунктуации:
     * знаки препинания напрямую влияют на качество перевода, потому что по ним
     * текст режется на предложения. Для японского и русского — многоязычную
     * модель, пунктуации в ней нет, но других потоковых вариантов под эти языки
     * в наборе нет.
     */
    val streamingModel: StreamingModel get() = when (this) {
        EN, ZH -> StreamingModel.ZH_EN_PUNCT
        JA, RU -> StreamingModel.MULTILINGUAL
    }

    /**
     * Есть ли модель восстановления пунктуации для этого языка.
     * ct-transformer в sherpa-onnx обучен только на китайском и английском.
     */
    val supportsPunctuationModel: Boolean get() = this == EN || this == ZH

    /**
     * Варианты цепочек перевода для бэкенда Opus-MT, от лучшего к худшему.
     *
     * Проверяются по манифесту моделей: берётся первая цепочка, для которой все
     * файлы доступны. Для китайского прямой модели zh→ru не существует, поэтому
     * он идёт через английский.
     */
    val opusMtChains: List<List<String>> get() = when (this) {
        EN -> listOf(listOf("en-ru"))
        JA -> listOf(listOf("ja-ru"))
        ZH -> listOf(listOf("zh-ru"), listOf("zh-en", "en-ru"))
        RU -> emptyList()
    }
}

/** Модели потокового распознавания. */
enum class StreamingModel(val id: String, val approxMb: Int, val hasPunctuation: Boolean) {
    ZH_EN_PUNCT("stream-zh-en-punct", 134, true),
    MULTILINGUAL("stream-multi", 259, false),
}

/** Как распознавать речь. */
enum class AsrMode { STREAMING, OFFLINE }

/**
 * Профиль работы.
 *
 * Главное изменение после проверки на устройстве: по умолчанию теперь потоковое
 * распознавание. Прежняя схема ждала паузы в речи и только потом прогоняла
 * Whisper по всему сегменту — на непрерывном закадровом тексте, где пауз почти
 * нет, первый перевод появлялся через десяток секунд, и это выглядело как
 * «перевод не начнётся, пока не остановишь видео».
 */
enum class Profile(
    val title: String,
    val subtitle: String,
    val asrMode: AsrMode,
    /** Только для офлайн-режима. */
    val asrModel: AsrModel,
    /** Сколько тишины считать концом фразы. */
    val endpointSilenceSec: Float,
    /** Максимальная длина сегмента в офлайн-режиме. */
    val maxSpeechSec: Float,
    val asrThreads: Int,
) {
    SYNC(
        title = "Синхронный",
        subtitle = "Текст идёт по ходу речи, ждать паузы не нужно",
        asrMode = AsrMode.STREAMING,
        asrModel = AsrModel.WHISPER_TINY,
        endpointSilenceSec = 0.6f,
        maxSpeechSec = 8f,
        asrThreads = 3,
    ),
    ACCURATE(
        title = "Точный",
        subtitle = "Whisper small, ждёт конца фразы. Медленнее, но точнее на шуме",
        asrMode = AsrMode.OFFLINE,
        asrModel = AsrModel.WHISPER_SMALL,
        endpointSilenceSec = 0.5f,
        maxSpeechSec = 6f,
        asrThreads = 4,
    ),
    LIGHT(
        title = "Экономный",
        subtitle = "Whisper tiny, меньше всех греет и меньше всех точен",
        asrMode = AsrMode.OFFLINE,
        asrModel = AsrModel.WHISPER_TINY,
        endpointSilenceSec = 0.4f,
        maxSpeechSec = 5f,
        asrThreads = 3,
    ),
}

/** Модели офлайн-распознавания. */
enum class AsrModel(
    val id: String,
    val title: String,
    /** Фактический размер по манифесту релиза моделей. */
    val approxMb: Int,
) {
    WHISPER_TINY("whisper-tiny", "Whisper tiny (int8)", 104),
    WHISPER_BASE("whisper-base", "Whisper base (int8)", 161),
    WHISPER_SMALL("whisper-small", "Whisper small (int8)", 376),
}

/**
 * Бэкенд машинного перевода.
 *
 * По умолчанию теперь Opus-MT, а не ML Kit — и это главная правка по качеству.
 * Проверка на живых фразах из ролика показала, что почти все замеченные ошибки
 * были ошибками ML Kit, а не пайплайна: он терял род («монахином, которому»,
 * «Карла никогда не видел») и целые куски смысла («и было ужасно» вместо
 * «была в ужасе от этого вида»). Opus-MT на тех же фразах даёт правильный род и
 * сохраняет смысл.
 */
enum class MtBackend(val title: String, val subtitle: String) {
    OPUS_MT(
        title = "Точный",
        subtitle = "Opus-MT, beam search. Правильный род и падежи, ja→ru напрямую",
    ),
    ML_KIT(
        title = "Быстрый",
        subtitle = "ML Kit, десятки миллисекунд, но заметно грубее",
    ),
}

/**
 * Русский голос озвучки.
 *
 * Системный TTS звучит механически, поэтому по умолчанию теперь голос Piper —
 * он заметно живее. Системный оставлен как запасной вариант: он ничего не качает
 * и работает там, где модель почему-то не поднялась.
 */
enum class Voice(
    val title: String,
    val subtitle: String,
    /** Идентификатор набора файлов в манифесте, или null для системного TTS. */
    val modelId: String?,
    val approxMb: Int,
) {
    IRINA("Ирина", "Женский, Piper. Самый живой из доступных", "piper-ru-irina", 30),
    DMITRI("Дмитрий", "Мужской, Piper", "piper-ru-dmitri", 30),
    RUSLAN("Руслан", "Мужской, Piper, ниже тембром", "piper-ru-ruslan", 30),
    SYSTEM("Системный", "Голос Android. Ничего не качает, но звучит механически", null, 0),
}

/**
 * Что делать с фоновой музыкой.
 *
 * По умолчанию выключено: лёгкие модели рассчитаны на стационарный шум, а на
 * музыке могут внести артефакты, которых распознаватель не видел при обучении,
 * и сделать хуже.
 */
enum class DenoiseMode(val title: String) {
    OFF("Выключено"),
    GTCRN("GTCRN (эксперимент)"),
}
