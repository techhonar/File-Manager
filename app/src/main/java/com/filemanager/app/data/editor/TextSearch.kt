package com.filemanager.app.data.editor

/** Finding and replacing plain text - no patterns, so any character means itself. */
object TextSearch {

    /** Stops counting here: past it the number only says "a lot", and finding more costs time. */
    const val MAX_MATCHES = 10_000

    /**
     * Where [query] occurs in [text], without overlaps. With [wholeWord] a
     * match must not have a letter, digit or underscore either side.
     */
    fun findAll(text: String, query: String, matchCase: Boolean, wholeWord: Boolean): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val found = ArrayList<IntRange>()
        var from = 0
        while (found.size < MAX_MATCHES) {
            val at = text.indexOf(query, from, ignoreCase = !matchCase)
            if (at < 0) break
            val end = at + query.length
            if (!wholeWord || (!isWordChar(text.getOrNull(at - 1)) && !isWordChar(text.getOrNull(end)))) {
                found += at until end
                from = end
            } else {
                from = at + 1
            }
        }
        return found
    }

    /** [text] with every match of [query] replaced, and how many there were. */
    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        matchCase: Boolean,
        wholeWord: Boolean,
    ): Pair<String, Int> {
        // Not capped: "replace all" must mean all.
        if (query.isEmpty()) return text to 0
        val out = StringBuilder(text.length)
        var from = 0
        var count = 0
        while (true) {
            val at = text.indexOf(query, from, ignoreCase = !matchCase)
            if (at < 0) break
            val end = at + query.length
            if (wholeWord && (isWordChar(text.getOrNull(at - 1)) || isWordChar(text.getOrNull(end)))) {
                out.append(text, from, at + 1)
                from = at + 1
                continue
            }
            out.append(text, from, at).append(replacement)
            from = end
            count++
        }
        out.append(text, from, text.length)
        return out.toString() to count
    }

    private fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')
}
