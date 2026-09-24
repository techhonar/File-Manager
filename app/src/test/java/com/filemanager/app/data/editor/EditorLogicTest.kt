package com.filemanager.app.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLogicTest {

    @Test
    fun `undo takes back a burst of typing as one step, and redo puts it back`() {
        val history = EditHistory()
        var text = ""
        var time = 0L
        for (c in "hello") {
            val next = text + c
            history.record(text, next, text.length, next.length, time)
            text = next
            time += 100
        }
        assertEquals("" to 0, history.undo(text))
        assertTrue(history.canRedo)
        assertEquals("hello" to 5, history.redo(""))
        assertFalse(history.canRedo)
    }

    @Test
    fun `a pause or a new line starts a new step`() {
        val history = EditHistory()
        history.record("", "ab", 0, 2, 0)
        history.record("ab", "abc", 2, 3, 5_000)
        history.record("abc", "abc\n", 3, 4, 5_100)
        assertEquals("abc" to 3, history.undo("abc\n"))
        assertEquals("ab" to 2, history.undo("abc"))
        assertEquals("" to 0, history.undo("ab"))
        assertNull(history.undo(""))
    }

    @Test
    fun `backspacing is one step, and a new edit clears redo`() {
        val history = EditHistory()
        history.record("hello", "hell", 5, 4, 0)
        history.record("hell", "hel", 4, 3, 100)
        history.record("hel", "he", 3, 2, 200)
        assertEquals("hello" to 5, history.undo("he"))
        history.record("hello", "hello!", 5, 6, 300)
        assertFalse(history.canRedo)
    }

    @Test
    fun `undoing a replacement in the middle restores it exactly`() {
        val history = EditHistory()
        history.record("one two three", "one 2 three", 4, 5, 0)
        assertEquals("one two three" to 4, history.undo("one 2 three"))
    }

    @Test
    fun `history that no longer fits the text is dropped`() {
        val history = EditHistory()
        history.record("abc", "abcd", 3, 4, 0)
        assertNull(history.undo("xyz"))
        assertFalse(history.canUndo)
    }

    @Test
    fun `finds without overlaps, by case and by whole word`() {
        assertEquals(listOf(0..1, 2..3), TextSearch.findAll("aaaa", "aa", matchCase = true, wholeWord = false))
        assertEquals(listOf(0..2, 4..6, 8..10), TextSearch.findAll("Cat cat CAT", "cat", matchCase = false, wholeWord = false))
        assertEquals(listOf(4..6), TextSearch.findAll("Cat cat CAT", "cat", matchCase = true, wholeWord = false))
        assertEquals(listOf(0..2), TextSearch.findAll("cat catalog", "cat", matchCase = true, wholeWord = true))
        assertEquals(emptyList<IntRange>(), TextSearch.findAll("abc", "", matchCase = true, wholeWord = false))
    }

    @Test
    fun `replaces all and counts them`() {
        assertEquals("dog dogalog dog" to 3, TextSearch.replaceAll("cat catalog CAT", "cat", "dog", false, false))
        assertEquals("dog catalog dog" to 2, TextSearch.replaceAll("cat catalog CAT", "cat", "dog", false, true))
        assertEquals("a-b-c" to 2, TextSearch.replaceAll("a b c", " ", "-", true, false))
    }

    @Test
    fun `lines, columns and counts`() {
        val text = "one two\nthree\n\nfour"
        assertEquals(1 to 1, lineAndColumn(text, 0))
        assertEquals(2 to 3, lineAndColumn(text, 10))
        assertEquals(4 to 5, lineAndColumn(text, text.length))
        assertEquals(8, lineStart(text, 2))
        assertEquals(15, lineStart(text, 4))
        assertEquals(15, lineStart(text, 99))
        assertEquals(0, lineStart(text, 0))
        assertEquals(4, lineCount(text))
        assertEquals(4, wordCount(text))
    }

    @Test
    fun `a new line keeps the indentation of the line it broke`() {
        assertEquals("    a\n    b" to 10, indentNewLine("    ab", "    a\nb", 6))
        assertEquals("\tx\n\t" to 4, indentNewLine("\tx", "\tx\n", 3))
        assertNull(indentNewLine("ab", "a\nb", 2))
        assertNull(indentNewLine("    ab", "    aXb", 6))
    }

    @Test
    fun `colours code by what it is`() {
        val kotlin = "val x = \"hi\" // note\nfun f() = 42"
        val kinds = Language.C_FAMILY.tokens(kotlin).map { kotlin.substring(it.start, it.end) to it.kind }
        assertEquals(
            listOf(
                "val" to TokenKind.KEYWORD,
                "\"hi\"" to TokenKind.STRING,
                "// note" to TokenKind.COMMENT,
                "fun" to TokenKind.KEYWORD,
                "42" to TokenKind.NUMBER,
            ),
            kinds,
        )
        val json = """{"name": "x", "n": 1.5, "ok": true}"""
        assertEquals(
            listOf(TokenKind.ATTRIBUTE, TokenKind.STRING, TokenKind.ATTRIBUTE, TokenKind.NUMBER, TokenKind.ATTRIBUTE, TokenKind.KEYWORD),
            Language.JSON.tokens(json).map { it.kind },
        )
        val python = "def f():\n    \"\"\"doc\n    string\"\"\"\n    return None  # done"
        assertEquals(
            listOf(TokenKind.KEYWORD, TokenKind.STRING, TokenKind.KEYWORD, TokenKind.KEYWORD, TokenKind.COMMENT),
            Language.PYTHON.tokens(python).map { it.kind },
        )
        assertEquals(TokenKind.HEADING, Language.MARKDOWN.tokens("# Title\ntext").single().kind)
        assertEquals(Language.C_FAMILY, Language.of("Main.KT"))
        assertEquals(Language.CONFIG, Language.of("app.yaml"))
        assertNull(Language.of("notes.txt"))
    }
}
