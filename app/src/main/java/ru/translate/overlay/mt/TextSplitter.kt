package ru.translate.overlay.mt

/**
 * Разбивка текста на предложения перед переводом.
 *
 * Это не косметика, а одна из главных причин плохого качества в первой версии.
 * Opus-MT и подобные модели обучены на **отдельных предложениях**; когда им
 * подают блоб из четырёх предложений подряд, перевод заметно деградирует —
 * теряются согласования и целые куски смысла. На реальном примере из ролика
 * пришло сразу четыре предложения одной строкой, и результат был соответствующий.
 *
 * Второй эффект: переводя по предложению, субтитры можно показывать по мере
 * готовности, а не ждать всю реплику.
 */
object TextSplitter {

    /** Символы конца предложения, включая китайские и японские. */
    private const val ENDINGS = ".!?…。！？"

    /** Закрывающие кавычки и скобки, которые могут стоять после точки. */
    private const val CLOSERS = "\"'»)]”’"

    /**
     * Слишком длинное предложение без знаков всё равно надо резать: и модель
     * перевода деградирует на длинном входе, и субтитры застревают.
     */
    private const val HARD_LIMIT = 180

    /** Не резать на огрызки: короче этого куски склеиваем с соседними. */
    private const val MIN_PIECE = 12

    /**
     * Возвращает позицию сразу за последним завершённым предложением или 0,
     * если завершённых предложений нет. Используется потоковым распознаванием,
     * чтобы отдавать в перевод готовое, не дожидаясь конца реплики.
     */
    fun lastSentenceBoundary(text: String): Int {
        var boundary = 0
        var i = 0
        while (i < text.length) {
            if (text[i] in ENDINGS) {
                var end = i + 1
                // Проглатываем подряд идущие знаки и закрывающие кавычки: «?!»,
                // «…", «.)» — это всё ещё одна граница.
                while (end < text.length &&
                    (text[end] in ENDINGS || text[end] in CLOSERS)
                ) {
                    end++
                }
                // Граница настоящая, только если дальше пробел или конец строки:
                // иначе это сокращение или число вида 3.14.
                if (end >= text.length || text[end].isWhitespace()) {
                    if (end >= MIN_PIECE) boundary = end
                }
                i = end
            } else {
                i++
            }
        }
        return boundary
    }

    /**
     * Делит текст на предложения. Слишком длинные куски без знаков режет по
     * запятым, а если и их нет — по словам, чтобы не подавать модели перевода
     * простыню.
     */
    fun split(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rough = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < trimmed.length) {
            if (trimmed[i] in ENDINGS) {
                var end = i + 1
                while (end < trimmed.length &&
                    (trimmed[end] in ENDINGS || trimmed[end] in CLOSERS)
                ) {
                    end++
                }
                if (end >= trimmed.length || trimmed[end].isWhitespace()) {
                    rough.add(trimmed.substring(start, end).trim())
                    start = end
                }
                i = end
            } else {
                i++
            }
        }
        if (start < trimmed.length) {
            rough.add(trimmed.substring(start).trim())
        }

        val result = ArrayList<String>(rough.size)
        for (piece in rough) {
            if (piece.isEmpty()) continue
            if (piece.length <= HARD_LIMIT) {
                result.add(piece)
            } else {
                result.addAll(splitLong(piece))
            }
        }

        return mergeTinyPieces(result)
    }

    private fun splitLong(piece: String): List<String> {
        // Сначала пробуем запятые и тире — естественные границы клауз.
        val byComma = splitKeepingDelimiters(piece, ",;:—–")
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        for (part in byComma) {
            if (buffer.isEmpty()) {
                buffer.append(part)
            } else if (buffer.length + part.length <= HARD_LIMIT) {
                buffer.append(part)
            } else {
                out.add(buffer.toString().trim())
                buffer.setLength(0)
                buffer.append(part)
            }
        }
        if (buffer.isNotEmpty()) out.add(buffer.toString().trim())

        // Если и после этого куски огромные — режем по словам.
        val final = ArrayList<String>()
        for (part in out) {
            if (part.length <= HARD_LIMIT) {
                final.add(part)
            } else {
                final.addAll(splitByWords(part))
            }
        }
        return final.filter { it.isNotBlank() }
    }

    private fun splitKeepingDelimiters(text: String, delimiters: String): List<String> {
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        for (ch in text) {
            buffer.append(ch)
            if (ch in delimiters) {
                out.add(buffer.toString())
                buffer.setLength(0)
            }
        }
        if (buffer.isNotEmpty()) out.add(buffer.toString())
        return out
    }

    private fun splitByWords(text: String): List<String> {
        val words = text.split(' ')
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        for (word in words) {
            if (buffer.isNotEmpty() && buffer.length + word.length + 1 > HARD_LIMIT) {
                out.add(buffer.toString().trim())
                buffer.setLength(0)
            }
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(word)
        }
        if (buffer.isNotEmpty()) out.add(buffer.toString().trim())
        return out
    }

    /**
     * Склеивает огрызки с предыдущим куском: «Да.» отдельным запросом к модели
     * перевода — потеря контекста без всякой пользы.
     */
    private fun mergeTinyPieces(pieces: List<String>): List<String> {
        if (pieces.size < 2) return pieces
        val out = ArrayList<String>(pieces.size)
        for (piece in pieces) {
            val last = out.lastOrNull()
            if (last != null &&
                piece.length < MIN_PIECE &&
                last.length + piece.length <= HARD_LIMIT
            ) {
                out[out.size - 1] = "$last $piece"
            } else {
                out.add(piece)
            }
        }
        return out
    }
}
