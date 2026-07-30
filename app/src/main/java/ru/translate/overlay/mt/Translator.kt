package ru.translate.overlay.mt

/** Результат перевода одной фразы. */
data class MtResult(val text: String, val elapsedMs: Long)

/**
 * Перевод на русский.
 *
 * Целевой язык всегда русский, поэтому направление в интерфейсе не указывается:
 * реализация создаётся под конкретный исходный язык и живёт всю сессию.
 */
interface Translator {

    /** Готовит модели. Может качать их по сети, поэтому suspend. */
    suspend fun prepare(onProgress: (String, Int) -> Unit = { _, _ -> })

    suspend fun translate(text: String): MtResult

    fun release()
}

/**
 * Заглушка для исходного русского: целевой язык совпадает с исходным, переводить
 * нечего. Экономит и задержку, и оперативную память — модель перевода вообще не
 * загружается. В ТЗ этот случай не был выделен отдельно.
 */
object NoOpTranslator : Translator {
    override suspend fun prepare(onProgress: (String, Int) -> Unit) = Unit
    override suspend fun translate(text: String) = MtResult(text, 0)
    override fun release() = Unit
}
