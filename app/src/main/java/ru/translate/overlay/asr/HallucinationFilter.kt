package ru.translate.overlay.asr

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Фильтр галлюцинаций Whisper.
 *
 * Whisper «галлюцинирует» на тишине, музыке и шуме — а типичный контент TikTok
 * это ровно музыкальный фон. Проблема известная, и одних порогов уверенности
 * недостаточно: галлюцинации нередко приходят с высокой уверенностью.
 *
 * Важное ограничение реализации. Классические сигналы `avg_logprob` и
 * `no_speech_prob` здесь недоступны: `OfflineRecognizerResult` в sherpa-onnx их
 * не отдаёт, наружу торчат только текст, токены и таймстемпы. Поэтому фильтр
 * построен на признаках, которые считаются по самому результату:
 *
 *  1. **Коэффициент сжатия** — отношение длины текста к длине его gzip. Выше
 *     [MAX_COMPRESSION_RATIO] означает, что декодер зациклился и повторяет
 *     текст. Самый надёжный из доступных признаков.
 *  2. **Повторы n-грамм** — та же зацикленность, но заметная и на коротком
 *     тексте, где gzip ещё не набрал статистики.
 *  3. **Скорость речи** — если из полусекунды сегмента вышло тридцать слов,
 *     это не расшифровка.
 *  4. **Список типовых галлюцинаций** — Whisper на тишине выдаёт заученные
 *     фразы из титров обучающих данных.
 */
object HallucinationFilter {

    /** Решение фильтра. */
    sealed interface Verdict {
        data object Accept : Verdict
        data class Reject(val reason: String) : Verdict
    }

    fun check(text: String, segmentDurationMs: Long): Verdict {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Verdict.Reject("пусто")

        // Односимвольный результат — обычно артефакт, а не реплика.
        if (trimmed.length < 2) return Verdict.Reject("слишком коротко")

        val normalized = trimmed.lowercase().trim(*TRIM_CHARS)
        if (KNOWN_HALLUCINATIONS.any { normalized == it || normalized.startsWith(it) }) {
            return Verdict.Reject("типовая галлюцинация")
        }

        if (trimmed.length >= MIN_LEN_FOR_GZIP) {
            val ratio = compressionRatio(trimmed)
            if (ratio > MAX_COMPRESSION_RATIO) {
                return Verdict.Reject("зацикливание, сжатие %.1f".format(ratio))
            }
        }

        val repeat = maxWordRepeatFraction(trimmed)
        if (repeat > MAX_REPEAT_FRACTION) {
            return Verdict.Reject("повторы %d%%".format((repeat * 100).toInt()))
        }

        if (segmentDurationMs > 0) {
            val charsPerSec = trimmed.length * 1000.0 / segmentDurationMs
            if (charsPerSec > MAX_CHARS_PER_SEC) {
                return Verdict.Reject("нереальная скорость речи")
            }
        }

        return Verdict.Accept
    }

    /** Длина текста, делённая на длину его gzip. */
    fun compressionRatio(text: String): Double {
        val raw = text.toByteArray(Charsets.UTF_8)
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(raw) }
        val compressed = buffer.size()
        if (compressed == 0) return 0.0
        return raw.size.toDouble() / compressed
    }

    /**
     * Доля, которую занимает самое частое слово. Для нормальной речи это редко
     * больше трети; 0.7 и выше — почти наверняка зацикливание вида
     * «спасибо спасибо спасибо».
     */
    private fun maxWordRepeatFraction(text: String): Double {
        val words = text.split(WORD_SPLIT).filter { it.isNotBlank() }
        if (words.size < MIN_WORDS_FOR_REPEAT) return 0.0
        val counts = HashMap<String, Int>()
        for (w in words) {
            val key = w.lowercase()
            counts[key] = (counts[key] ?: 0) + 1
        }
        val top = counts.values.maxOrNull() ?: return 0.0
        return top.toDouble() / words.size
    }

    private val WORD_SPLIT = Regex("[\\s.,!?;:—\\-()\\[\\]\"'‘’“”]+")
    private val TRIM_CHARS = charArrayOf(' ', '.', ',', '!', '?', ';', ':', '\n')

    private const val MAX_COMPRESSION_RATIO = 2.4
    private const val MIN_LEN_FOR_GZIP = 60
    private const val MAX_REPEAT_FRACTION = 0.7
    private const val MIN_WORDS_FOR_REPEAT = 6

    /**
     * Верхняя граница скорости речи в символах в секунду. Для китайского и
     * японского символ несёт больше смысла, поэтому граница взята с запасом —
     * задача признака отсечь явную бессмыслицу, а не измерять темп.
     */
    private const val MAX_CHARS_PER_SEC = 45.0

    /**
     * Заученные фразы из титров, которые Whisper выдаёт на тишине и музыке.
     * Сравнение в нижнем регистре и без концевой пунктуации.
     */
    private val KNOWN_HALLUCINATIONS = setOf(
        "субтитры сделал dimatorzok",
        "субтитры создавал dimatorzok",
        "редактор субтитров",
        "продолжение следует",
        "спасибо за просмотр",
        "спасибо за внимание",
        "подписывайтесь на канал",
        "продолжение в следующей серии",
        "thanks for watching",
        "thank you for watching",
        "subscribe to my channel",
        "please subscribe",
        "amara.org",
        "subtitles by",
        "transcription by",
        "www.mooji.org",
        "请不吝点赞 订阅 转发 打赏支持明镜与点点栏目",
        "字幕由amara.org社区提供",
        "ご視聴ありがとうございました",
        "チャンネル登録お願いします",
    )
}
