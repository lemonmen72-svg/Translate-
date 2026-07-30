package ru.translate.overlay.mt

import org.json.JSONArray
import java.text.Normalizer

/**
 * Токенизатор SentencePiece, модель unigram — та, что используется в Opus-MT.
 *
 * Почему свой, а не библиотечный: файл `.spm` — это protobuf, и разбирать его на
 * устройстве значит тащить protobuf-рантайм. Вместо этого куски и их логвесы
 * выгружаются в JSON на этапе конвертации моделей в CI, а здесь остаётся только
 * инференс — динамика Витерби по этим весам.
 *
 * Ограничение, о котором стоит знать: нормализация здесь приближённая. У
 * SentencePiece своя схема (NMT_NFKC) со собственными правилами для пунктуации,
 * а тут NFKC из стандартной библиотеки плюс замена пробелов. Расхождение
 * снижает качество на редких символах, но не ломает перевод.
 */
class SentencePieceUnigram private constructor(
    private val pieces: Map<String, Float>,
    private val maxPieceLength: Int,
) {

    /**
     * Разбивает текст на куски. Неизвестные символы отдаются по одному —
     * маппинг в unk делает вызывающая сторона, у неё есть словарь.
     */
    fun encode(text: String): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()

        val n = normalized.length
        // best[i] — лучший суммарный логвес для префикса длиной i.
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val backLength = IntArray(n + 1)
        best[0] = 0f

        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            val limit = minOf(maxPieceLength, n - i)
            for (len in 1..limit) {
                val piece = normalized.substring(i, i + len)
                val score = pieces[piece] ?: continue
                val candidate = best[i] + score
                if (candidate > best[i + len]) {
                    best[i + len] = candidate
                    backLength[i + len] = len
                }
            }
            // Одиночный символ как аварийный выход: иначе неизвестный символ
            // обрывает всю динамику и текст теряется целиком.
            if (best[i + 1] == Float.NEGATIVE_INFINITY) {
                best[i + 1] = best[i] + UNKNOWN_PENALTY
                backLength[i + 1] = 1
            }
        }

        val result = ArrayList<String>()
        var index = n
        while (index > 0) {
            val len = backLength[index].coerceAtLeast(1)
            result.add(normalized.substring(index - len, index))
            index -= len
        }
        result.reverse()
        return result
    }

    /** Обратная сборка: куски целевого языка в обычный текст. */
    fun decodePieces(pieces: List<String>): String =
        pieces.joinToString("")
            .replace(SPACE_MARK, ' ')
            .trim()

    private fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        if (nfkc.isEmpty()) return ""
        val collapsed = nfkc.replace(WHITESPACE, " ")
        return SPACE_MARK + collapsed.replace(' ', SPACE_MARK)
    }

    companion object {
        /** Символ U+2581, которым SentencePiece помечает начало слова. */
        const val SPACE_MARK = '▁'

        private val WHITESPACE = Regex("\\s+")

        /**
         * Штраф за неизвестный символ. Должен быть заметно хуже любого реального
         * куска, чтобы динамика не предпочитала разбор по буквам.
         */
        private const val UNKNOWN_PENALTY = -20f

        /**
         * Загружает куски из JSON вида `[["▁the", -3.2], ...]`, выгруженного
         * скриптом конвертации моделей.
         */
        fun fromJson(json: String): SentencePieceUnigram {
            val array = JSONArray(json)
            val map = HashMap<String, Float>(array.length() * 2)
            var maxLength = 1
            for (i in 0 until array.length()) {
                val entry = array.getJSONArray(i)
                val piece = entry.getString(0)
                if (piece.isEmpty()) continue
                map[piece] = entry.getDouble(1).toFloat()
                if (piece.length > maxLength) maxLength = piece.length
            }
            return SentencePieceUnigram(map, maxLength)
        }
    }
}
