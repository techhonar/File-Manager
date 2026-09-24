package com.filemanager.app.data.editor

/*
 * Small facts about text being edited: where the cursor is, how long it is,
 * and carrying indentation over to a new line.
 */

/** The line and column [offset] falls on, both counted from 1 as editors show them. */
fun lineAndColumn(text: CharSequence, offset: Int): Pair<Int, Int> {
    val end = offset.coerceIn(0, text.length)
    var line = 1
    var lineStart = 0
    for (i in 0 until end) {
        if (text[i] == '\n') {
            line++
            lineStart = i + 1
        }
    }
    return line to end - lineStart + 1
}

/** Where line [line] (from 1) starts; the last line's start for one past the end. */
fun lineStart(text: CharSequence, line: Int): Int {
    var remaining = line - 1
    if (remaining <= 0) return 0
    var last = 0
    for (i in text.indices) {
        if (text[i] == '\n') {
            last = i + 1
            if (--remaining == 0) return last
        }
    }
    return last
}

fun lineCount(text: CharSequence): Int = 1 + text.count { it == '\n' }

/** Runs of anything but whitespace. */
fun wordCount(text: CharSequence): Int {
    var words = 0
    var inWord = false
    for (c in text) {
        val space = c.isWhitespace()
        if (!space && !inWord) words++
        inWord = !space
    }
    return words
}

/**
 * When [new] is [old] with one line break typed before [cursor], the same
 * text with the broken line's indentation carried onto the new line, and
 * where the cursor goes then. Null for anything else, or no indentation.
 */
fun indentNewLine(old: String, new: String, cursor: Int): Pair<String, Int>? {
    if (new.length != old.length + 1 || cursor !in 1..new.length || new[cursor - 1] != '\n') return null
    if (!new.regionMatches(0, old, 0, cursor - 1) ||
        !new.regionMatches(cursor, old, cursor - 1, old.length - cursor + 1)
    ) {
        return null
    }
    val start = new.lastIndexOf('\n', cursor - 2) + 1
    var end = start
    while (end < cursor - 1 && (new[end] == ' ' || new[end] == '\t')) end++
    if (end == start) return null
    val indent = new.substring(start, end)
    return new.substring(0, cursor) + indent + new.substring(cursor) to cursor + indent.length
}
