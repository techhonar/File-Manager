package com.filemanager.app.data.viewer

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Working out a formula that came with no result.
 *
 * Excel keeps each formula's result beside it, and a workbook is shown from
 * those. A workbook written by a program has the formulas and no results -
 * Excel works them out as it opens the file - so its totals were blank here.
 *
 * This works out the formulas such a workbook has: arithmetic, comparisons
 * and the everyday functions, over cells and ranges of the same sheet.
 * Anything else - a lookup, another sheet, a name, a whole column - is not
 * guessed at: [Formulas.evaluate] gives null, and the cell shows its formula
 * rather than a number that might be wrong.
 */

/** What a cell holds, or a formula comes to. */
internal sealed interface CellValue {
    data class Number(val value: Double) : CellValue
    data class Text(val text: String) : CellValue
    data class Logical(val value: Boolean) : CellValue

    /** #DIV/0!, #VALUE! and the like. */
    data class Error(val code: String) : CellValue
    data object Empty : CellValue
}

/** The cells of one sheet, as a formula sees them. Rows and columns count from 1. */
internal interface FormulaCells {
    /** The last row and column with anything in them: a range is read no further. */
    val lastRow: Int
    val lastColumn: Int

    /**
     * Rows and columns past these were never read: a sheet too long to show
     * whole is read only so far. A formula that reaches into them is not
     * worked out - the total of part of a column is not its total.
     */
    val readThroughRow: Int get() = Int.MAX_VALUE
    val readThroughColumn: Int get() = Int.MAX_VALUE

    /**
     * What the cell holds - for a formula with no result, what it comes to.
     * Throws [NotWorkedOut] if that can't be said.
     */
    fun at(row: Int, column: Int): CellValue

    /** Every cell with something in it from [firstRow] to [lastRow], in those columns. */
    fun within(firstRow: Int, firstColumn: Int, lastRow: Int, lastColumn: Int, each: (CellValue) -> Unit)

    /**
     * Another sheet of the same workbook, by its name whatever its case - a
     * report's formulas count and add up what is on its sheet of data. Null
     * if there is no such sheet, or it can't be read whole.
     */
    fun sheet(name: String): FormulaCells? = null
}

/** A formula that uses something not worked out here, or more than is worked out for one sheet. */
internal class NotWorkedOut(val reason: String = "") : RuntimeException() {
    // Thrown often, and only ever caught: where from is of no interest.
    override fun fillInStackTrace(): Throwable = this
}

internal class Formulas(private val cells: FormulaCells) {

    /** The value of [formula] - with or without its = - or null if it uses something not worked out here. */
    fun evaluate(formula: String): CellValue? = try {
        Parser(formula.trim().removePrefix("=")).whole()
    } catch (e: NotWorkedOut) {
        // One left for later is still left for later.
        if (e.reason == LATER) throw e
        null
    }

    /** A value, or the cells a reference names. */
    private sealed interface Term
    private class Value(val value: CellValue) : Term
    private class Area(val sheet: FormulaCells, val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int) : Term {
        val single: Boolean get() = firstRow == lastRow && firstColumn == lastColumn
        val rows: Int get() = lastRow - firstRow + 1
        val columns: Int get() = lastColumn - firstColumn + 1
    }

    /** The cells from one corner to the other on [sheet] - unless they reach into rows or columns that were never read. */
    private fun area(sheet: FormulaCells, firstRow: Int, firstColumn: Int, lastRow: Int, lastColumn: Int): Area {
        if (lastRow > sheet.readThroughRow || lastColumn > sheet.readThroughColumn) throw NotWorkedOut()
        return Area(sheet, firstRow, firstColumn, lastRow, lastColumn)
    }

    private inner class Parser(private val text: String) {
        private var at = 0

        /** Brackets within brackets: Excel takes 64, and a formula of thousands is made to run the reader out of room. */
        private var nesting = 0

        private inline fun <T> nested(inner: () -> T): T {
            if (++nesting > MAX_NESTING) unsupported()
            return inner().also { nesting-- }
        }

        fun whole(): CellValue {
            val value = scalar(comparison())
            spaces()
            if (at != text.length) unsupported()
            return when {
                // A reference to an empty cell comes to nothing, which Excel shows as 0.
                value == CellValue.Empty -> CellValue.Number(0.0)
                // Past the largest number there is: no number at all.
                value is CellValue.Number && !value.value.isFinite() -> CellValue.Error("#NUM!")
                else -> value
            }
        }

        private fun unsupported(): Nothing = throw NotWorkedOut()

        private fun spaces() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(): Char = if (at < text.length) text[at] else '\u0000'

        private fun take(symbol: String): Boolean {
            spaces()
            if (!text.startsWith(symbol, at)) return false
            at += symbol.length
            return true
        }

        // Loosest first: = <> < > <= >=, then &, then + -, * /, ^, %, and a sign.

        private fun comparison(): Term {
            var left = concatenation()
            while (true) {
                spaces()
                val operator = listOf("<>", "<=", ">=", "=", "<", ">").firstOrNull { text.startsWith(it, at) } ?: return left
                at += operator.length
                val a = scalar(left)
                val b = scalar(concatenation())
                left = Value(
                    when {
                        a is CellValue.Error -> a
                        b is CellValue.Error -> b
                        else -> {
                            val order = compare(a, b)
                            CellValue.Logical(
                                when (operator) {
                                    "=" -> order == 0
                                    "<>" -> order != 0
                                    "<" -> order < 0
                                    ">" -> order > 0
                                    "<=" -> order <= 0
                                    else -> order >= 0
                                },
                            )
                        }
                    },
                )
            }
        }

        private fun concatenation(): Term {
            var left = sum()
            while (take("&")) {
                val a = scalar(left)
                val b = scalar(sum())
                left = Value(
                    when {
                        a is CellValue.Error -> a
                        b is CellValue.Error -> b
                        else -> CellValue.Text(written(a) + written(b))
                    },
                )
            }
            return left
        }

        private fun sum(): Term {
            var left = product()
            while (true) {
                spaces()
                val operator = peek()
                if (operator != '+' && operator != '-') return left
                at++
                val right = product()
                left = arithmetic(left, right) { a, b -> if (operator == '+') a + b else a - b }
            }
        }

        private fun product(): Term {
            var left = power()
            while (true) {
                spaces()
                val operator = peek()
                if (operator != '*' && operator != '/') return left
                at++
                val right = power()
                left = if (operator == '*') {
                    arithmetic(left, right) { a, b -> a * b }
                } else {
                    val b = number(scalar(right))
                    if (b is CellValue.Number && b.value == 0.0 && number(scalar(left)) !is CellValue.Error) {
                        Value(CellValue.Error("#DIV/0!"))
                    } else {
                        arithmetic(left, right) { x, y -> x / y }
                    }
                }
            }
        }

        private fun power(): Term {
            var left = percent()
            while (take("^")) {
                val right = percent()
                left = arithmetic(left, right) { a, b -> a.pow(b) }
            }
            return left
        }

        private fun percent(): Term {
            var value = signed()
            while (take("%")) value = arithmetic(value, Value(CellValue.Number(100.0))) { a, b -> a / b }
            return value
        }

        private fun signed(): Term {
            var negative = false
            while (true) {
                spaces()
                when (peek()) {
                    '-' -> negative = !negative
                    '+' -> Unit
                    else -> break
                }
                at++
            }
            val value = primary()
            return if (negative) arithmetic(Value(CellValue.Number(0.0)), value) { a, b -> a - b } else value
        }

        private fun arithmetic(left: Term, right: Term, apply: (Double, Double) -> Double): Term {
            val a = number(scalar(left))
            val b = number(scalar(right))
            if (a is CellValue.Error) return Value(a)
            if (b is CellValue.Error) return Value(b)
            val result = apply((a as CellValue.Number).value, (b as CellValue.Number).value)
            return Value(if (result.isNaN() || result.isInfinite()) CellValue.Error("#NUM!") else CellValue.Number(result))
        }

        private fun primary(): Term {
            spaces()
            val char = peek()
            return when {
                char == '(' -> {
                    at++
                    val inner = nested { comparison() }
                    if (!take(")")) unsupported()
                    // In brackets a reference is still a reference: SUM((A1:A3)).
                    inner
                }
                char == '"' -> Value(CellValue.Text(quoted()))
                char.isDigit() || char == '.' -> Value(CellValue.Number(literal()))
                // 'Sales 2024'!A1: cells of another sheet, its name in quotes.
                char == '\'' -> reference(sheetNamed(quotedSheet())) ?: unsupported()
                char == '$' || char.isLetter() -> named()
                else -> unsupported()
            }
        }

        /** A sheet's name in quotes, in which a quote is written twice, and the ! that follows it. */
        private fun quotedSheet(): String {
            at++
            val name = StringBuilder()
            while (true) {
                if (at >= text.length) unsupported()
                val char = text[at++]
                if (char != '\'') {
                    name.append(char)
                } else if (peek() == '\'') {
                    name.append('\'')
                    at++
                } else {
                    break
                }
            }
            if (peek() != '!') unsupported()
            at++
            return name.toString()
        }

        /** The sheet called [name]; one in another workbook, or several sheets at once, is not worked out here. */
        private fun sheetNamed(name: String): FormulaCells {
            if ('[' in name || ':' in name) unsupported()
            return cells.sheet(name) ?: unsupported()
        }

        /** A cell or a range of them on [sheet] where the text is now; null, and nothing read, if that isn't one. */
        private fun reference(sheet: FormulaCells): Area? {
            val first = cell() ?: return null
            val afterFirst = at
            if (take(":")) {
                spaces()
                val second = cell() ?: unsupported()
                return area(sheet, minOf(first.first, second.first), minOf(first.second, second.second), maxOf(first.first, second.first), maxOf(first.second, second.second))
            }
            at = afterFirst
            return area(sheet, first.first, first.second, first.first, first.second)
        }

        /** Text in quotes, in which a quote is written twice. */
        private fun quoted(): String {
            at++
            val out = StringBuilder()
            while (true) {
                if (at >= text.length) unsupported()
                val char = text[at++]
                if (char != '"') {
                    out.append(char)
                } else if (peek() == '"') {
                    out.append('"')
                    at++
                } else {
                    return out.toString()
                }
            }
        }

        private fun literal(): Double {
            val start = at
            while (peek().isDigit()) at++
            if (peek() == '.') {
                at++
                while (peek().isDigit()) at++
            }
            if (peek() == 'E' || peek() == 'e') {
                var end = at + 1
                if (end < text.length && (text[end] == '+' || text[end] == '-')) end++
                if (end < text.length && text[end].isDigit()) {
                    while (end < text.length && text[end].isDigit()) end++
                    at = end
                }
            }
            // 1E999 is written like a number and is none.
            return text.substring(start, at).toDoubleOrNull()?.takeIf { it.isFinite() } ?: unsupported()
        }

        /** A cell, a range of them, TRUE or FALSE, or a function with what it is given. */
        private fun named(): Term {
            val start = at
            reference(cells)?.let { return it }
            at = start
            while (peek().isLetterOrDigit() || peek() == '.' || peek() == '_') at++
            if (at == start) unsupported()
            // Data!A1: cells of another sheet, by its name.
            if (peek() == '!') {
                val sheet = sheetNamed(text.substring(start, at))
                at++
                return reference(sheet) ?: unsupported()
            }
            val name = text.substring(start, at).uppercase()
            if (peek() != '(') {
                return when (name) {
                    "TRUE" -> Value(CellValue.Logical(true))
                    "FALSE" -> Value(CellValue.Logical(false))
                    // A name, a whole column: not worked out here.
                    else -> unsupported()
                }
            }
            at++
            val given = ArrayList<Term>()
            spaces()
            if (peek() == ')') {
                at++
            } else {
                nested {
                    while (true) {
                        given += comparison()
                        if (take(")")) break
                        if (!take(",") || given.size >= MAX_ARGUMENTS) unsupported()
                    }
                }
            }
            return Value(function(name, given))
        }

        /** A cell's row and column where the text is now - A1, $B$12 - or null, and nothing read, if that isn't one. */
        private fun cell(): Pair<Int, Int>? {
            val start = at
            var i = at
            if (i < text.length && text[i] == '$') i++
            val letters = i
            while (i < text.length && text[i].isLetter() && text[i].code < 128) i++
            if (i == letters || i - letters > 3) return null
            val column = columnOf(text.substring(letters, i)) ?: return null
            if (i < text.length && text[i] == '$') i++
            val digits = i
            while (i < text.length && text[i].isDigit()) i++
            if (i == digits || i - digits > 7) return null
            // Part of something longer - LOG10(, a name such as TAX2024, Sheet1!A1 - is not a cell.
            val next = if (i < text.length) text[i] else ' '
            if (next.isLetterOrDigit() || next == '(' || next == '!' || next == '_' || next == '.' || next == '[') return null
            val row = text.substring(digits, i).toIntOrNull()?.takeIf { it >= 1 } ?: return null
            if (column > MAX_COLUMN || row > MAX_ROW) return null
            at = i
            return row to column
        }

    }

    /** One value for [term]: a value as it is, a single cell's own. A range has no one value. */
    private fun scalar(term: Term): CellValue = when (term) {
        is Value -> term.value
        is Area -> if (term.single) term.sheet.at(term.firstRow, term.firstColumn) else throw NotWorkedOut()
    }

    /** [value] as a number, as arithmetic takes it: nothing is 0, TRUE is 1, text is the number it spells or #VALUE!. */
    private fun number(value: CellValue): CellValue = when (value) {
        is CellValue.Number, is CellValue.Error -> value
        is CellValue.Logical -> CellValue.Number(if (value.value) 1.0 else 0.0)
        CellValue.Empty -> CellValue.Number(0.0)
        is CellValue.Text -> value.text.trim().toDoubleOrNull()?.let { CellValue.Number(it) } ?: CellValue.Error("#VALUE!")
    }

    /** [value] as it is written when joined to text. */
    private fun written(value: CellValue): String = when (value) {
        is CellValue.Number -> written(value.value)
        is CellValue.Text -> value.text
        is CellValue.Logical -> if (value.value) "TRUE" else "FALSE"
        is CellValue.Error -> value.code
        CellValue.Empty -> ""
    }

    /**
     * A number as Excel writes it into text: the fifteen digits it keeps,
     * written out - 0.333333333333333 - unless that would take more digits
     * than it has, when it is 1E+15.
     */
    private fun written(value: Double): String {
        if (value == 0.0) return "0"
        if (!value.isFinite()) return "#NUM!"
        val rounded = BigDecimal(value.toString()).round(MathContext(15)).stripTrailingZeros()
        // Where its first digit stands: 0 for units, 2 for hundreds, -1 for tenths.
        val exponent = rounded.precision() - rounded.scale() - 1
        if (exponent in -10..14) return rounded.toPlainString()
        val mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString()
        return mantissa + "E" + (if (exponent < 0) "-" else "+") + abs(exponent).toString().padStart(2, '0')
    }

    /** Below, equal or above, as Excel orders them: numbers, then text whatever its case, then FALSE and TRUE. */
    private fun compare(a: CellValue, b: CellValue): Int {
        // Nothing is whatever it is compared with: 0, "" or FALSE.
        val left = if (a == CellValue.Empty) nothingAs(b) else a
        val right = if (b == CellValue.Empty) nothingAs(a) else b
        fun rank(value: CellValue) = when (value) {
            is CellValue.Number -> 0
            is CellValue.Text -> 1
            else -> 2
        }
        return when {
            left is CellValue.Number && right is CellValue.Number -> left.value.compareTo(right.value)
            left is CellValue.Text && right is CellValue.Text -> left.text.compareTo(right.text, ignoreCase = true)
            left is CellValue.Logical && right is CellValue.Logical -> left.value.compareTo(right.value)
            else -> rank(left).compareTo(rank(right))
        }
    }

    private fun nothingAs(other: CellValue): CellValue = when (other) {
        is CellValue.Text -> CellValue.Text("")
        is CellValue.Logical -> CellValue.Logical(false)
        else -> CellValue.Number(0.0)
    }

    /** The numbers [given] to a function such as SUM, or the first error among them. */
    private class Numbers {
        val values = ArrayList<Double>()
        var error: CellValue.Error? = null
    }

    /**
     * Numbers as SUM and its like take them. In a range, only the numbers
     * count - text, TRUE and empty cells are passed over. Given outright,
     * TRUE is 1 and text is the number it spells.
     */
    private fun numbers(given: List<Term>): Numbers {
        val found = Numbers()
        for (term in given) {
            if (term is Area && !term.single) {
                each(term) { value ->
                    when (value) {
                        is CellValue.Number -> found.values += value.value
                        is CellValue.Error -> if (found.error == null) found.error = value
                        else -> Unit
                    }
                }
            } else if (term is Area) {
                when (val value = term.sheet.at(term.firstRow, term.firstColumn)) {
                    is CellValue.Number -> found.values += value.value
                    is CellValue.Error -> if (found.error == null) found.error = value
                    else -> Unit
                }
            } else {
                when (val value = number(scalar(term))) {
                    is CellValue.Number -> found.values += value.value
                    is CellValue.Error -> if (found.error == null) found.error = value
                    else -> Unit
                }
            }
        }
        return found
    }

    private fun each(area: Area, visit: (CellValue) -> Unit) {
        val lastRow = minOf(area.lastRow, area.sheet.lastRow)
        val lastColumn = minOf(area.lastColumn, area.sheet.lastColumn)
        if (area.firstRow > lastRow || area.firstColumn > lastColumn) return
        area.sheet.within(area.firstRow, area.firstColumn, lastRow, lastColumn, visit)
    }

    private fun finite(value: Double): CellValue =
        if (value.isNaN() || value.isInfinite()) CellValue.Error("#NUM!") else CellValue.Number(value)

    /**
     * What a cell must hold to count, as COUNTIF and its like are told it: a
     * number or a text to be the same as, or a text that begins with =, <>,
     * >, <, >= or <= and goes on to say what to be set against. Text is
     * matched whatever its case, with * for any letters and ? for any one.
     */
    private class Criterion(given: CellValue, private val work: Work) {
        private val operator: String
        private val number: Double?
        private val logical: Boolean?

        /** What text is to be like, when it is text that is asked for; and that as pieces, if it has * or ? in it. */
        private val text: String
        private val pattern: List<Any>?

        init {
            var asked = ""
            when (given) {
                is CellValue.Text -> asked = given.text
                is CellValue.Number -> asked = given.value.toString()
                is CellValue.Logical -> asked = if (given.value) "TRUE" else "FALSE"
                // An empty cell asks for 0.
                CellValue.Empty -> asked = "0"
                is CellValue.Error -> asked = given.code
            }
            operator = OPERATORS.firstOrNull { asked.startsWith(it) } ?: ""
            val rest = asked.substring(operator.length)
            number = rest.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            logical = if (number != null) null else when (rest.trim().uppercase()) {
                "TRUE" -> true
                "FALSE" -> false
                else -> null
            }
            text = rest
            pattern = if (number == null && logical == null && rest.any { it == '*' || it == '?' || it == '~' }) pieces(rest) else null
        }

        fun matches(cell: CellValue): Boolean {
            // An error is counted by its name alone - "#N/A" - and so is not
            // whatever else was asked not to be: "<>0" counts it.
            if (cell is CellValue.Error) {
                val named = number == null && logical == null && cell.code.equals(text.trim(), ignoreCase = true)
                return if (operator == "<>") !named else named && (operator == "" || operator == "=")
            }
            return when {
                number != null -> {
                    // Text that spells the number is taken for it, as Excel takes it.
                    val held = when (cell) {
                        is CellValue.Number -> cell.value
                        is CellValue.Text -> cell.text.trim().toDoubleOrNull()
                        else -> null
                    }
                    when (operator) {
                        "", "=" -> held == number
                        "<>" -> held != number
                        // Only a number is more or less than a number.
                        else -> cell is CellValue.Number && ordered(cell.value.compareTo(number))
                    }
                }
                logical != null -> (cell is CellValue.Logical && cell.value == logical) != (operator == "<>")
                text.isEmpty() -> {
                    val blank = cell == CellValue.Empty || (cell is CellValue.Text && cell.text.isEmpty())
                    when (operator) {
                        "", "=" -> blank
                        "<>" -> !blank
                        else -> false
                    }
                }
                else -> when (operator) {
                    "", "=" -> cell is CellValue.Text && like(cell.text)
                    "<>" -> !(cell is CellValue.Text && like(cell.text))
                    else -> cell is CellValue.Text && ordered(cell.text.compareTo(text, ignoreCase = true))
                }
            }
        }

        private fun ordered(order: Int): Boolean = when (operator) {
            ">" -> order > 0
            "<" -> order < 0
            ">=" -> order >= 0
            else -> order <= 0
        }

        private fun like(held: String): Boolean {
            val pieces = pattern ?: return held.equals(text, ignoreCase = true)
            // Longer than Excel itself sets against a pattern.
            if (held.length > MAX_MATCHED) return false
            // Each letter in turn; at a *, as few letters as will do, and
            // back to it for one more whenever what follows doesn't fit.
            var h = 0
            var p = 0
            var starAt = -1
            var heldAtStar = 0
            while (h < held.length) {
                // A pattern can be made that goes back over every text many
                // times: so much of that is done for a sheet, and no more.
                if (++work.steps > MAX_MATCHING) throw NotWorkedOut()
                val piece = pieces.getOrNull(p)
                if (piece == ANY_ONE || (piece is Char && piece.equals(held[h], ignoreCase = true))) {
                    h++
                    p++
                } else if (piece == ANY) {
                    starAt = p++
                    heldAtStar = h
                } else if (starAt >= 0) {
                    p = starAt + 1
                    h = ++heldAtStar
                } else {
                    return false
                }
            }
            while (pieces.getOrNull(p) == ANY) p++
            return p == pieces.size
        }

        private companion object {
            val OPERATORS = listOf(">=", "<=", "<>", ">", "<", "=")
            val ANY = Any()
            val ANY_ONE = Any()

            /** A pattern as its letters, with * and ? as what they stand for - unless a ~ before one says it is itself. */
            fun pieces(pattern: String): List<Any> {
                // Longer than any condition is, and each cell's text is gone over once for every piece of it.
                if (pattern.length > MAX_PATTERN) throw NotWorkedOut()
                val out = ArrayList<Any>(pattern.length)
                var i = 0
                while (i < pattern.length) {
                    val char = pattern[i++]
                    out += when {
                        char == '~' && i < pattern.length && pattern[i] in "*?~" -> pattern[i++]
                        char == '*' -> ANY
                        char == '?' -> ANY_ONE
                        else -> char
                    }
                }
                return out
            }
        }
    }

    /** How much setting text against patterns has been done for this sheet's formulas. */
    private class Work {
        var steps = 0L
    }

    private val matching = Work()

    /**
     * COUNTIFS, SUMIFS and AVERAGEIFS - and, with one range to look through,
     * COUNTIF, SUMIF and AVERAGEIF: the cells of [values], or how many
     * places there are, where each of the [ranges] holds what its criterion
     * asks. [what] is C to count, S to add up, A to average.
     */
    private fun conditional(what: Char, values: Area?, ranges: List<Area>, criteria: List<Criterion>): CellValue {
        val first = ranges.first()
        // Each the shape of the first, so that place answers to place.
        if ((ranges + listOfNotNull(values)).any { it.rows != first.rows || it.columns != first.columns }) return CellValue.Error("#VALUE!")
        // Past the last row and column that any of them has anything in, every cell is empty.
        var rows = 0
        var columns = 0
        for (area in ranges + listOfNotNull(values)) {
            rows = maxOf(rows, minOf(area.rows, area.sheet.lastRow - area.firstRow + 1))
            columns = maxOf(columns, minOf(area.columns, area.sheet.lastColumn - area.firstColumn + 1))
        }
        var count = 0L
        var sum = 0.0
        var numbers = 0L
        var error: CellValue.Error? = null
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                if (ranges.indices.any { i -> !criteria[i].matches(ranges[i].sheet.at(ranges[i].firstRow + r, ranges[i].firstColumn + c)) }) continue
                count++
                if (values == null) continue
                when (val value = values.sheet.at(values.firstRow + r, values.firstColumn + c)) {
                    is CellValue.Number -> {
                        sum += value.value
                        numbers++
                    }
                    is CellValue.Error -> if (error == null) error = value
                    else -> Unit
                }
            }
        }
        // The empty cells beyond count as well, when being empty is what was asked.
        val beyond = first.rows.toLong() * first.columns - maxOf(rows, 0).toLong() * maxOf(columns, 0)
        if (beyond > 0 && criteria.all { it.matches(CellValue.Empty) }) count += beyond
        return when (what) {
            'C' -> CellValue.Number(count.toDouble())
            'S' -> error ?: finite(sum)
            else -> error ?: if (numbers == 0L) CellValue.Error("#DIV/0!") else finite(sum / numbers)
        }
    }

    private fun function(name: String, given: List<Term>): CellValue {
        fun one(index: Int): CellValue = scalar(given.getOrNull(index) ?: throw NotWorkedOut())
        fun count(expected: IntRange) {
            if (given.size !in expected) throw NotWorkedOut()
        }
        /** A number given outright, or the error it is instead. */
        fun numeric(index: Int, then: (Double) -> CellValue): CellValue = when (val value = number(one(index))) {
            is CellValue.Number -> then(value.value)
            else -> value
        }
        /** A range given where one must be: its cells, not one value. */
        fun range(index: Int): Area = given.getOrNull(index) as? Area ?: throw NotWorkedOut()
        /** TRUE or FALSE for a condition: a number is TRUE unless 0; text is neither. */
        fun truth(value: CellValue): CellValue = when (value) {
            is CellValue.Logical, is CellValue.Error -> value
            is CellValue.Number -> CellValue.Logical(value.value != 0.0)
            CellValue.Empty -> CellValue.Logical(false)
            is CellValue.Text -> when (value.text.uppercase()) {
                "TRUE" -> CellValue.Logical(true)
                "FALSE" -> CellValue.Logical(false)
                else -> CellValue.Error("#VALUE!")
            }
        }
        fun rounded(mode: RoundingMode): CellValue {
            count(2..2)
            return numeric(0) { value ->
                numeric(1) { places ->
                    val digits = places.toInt().coerceIn(-300, 300)
                    finite(runCatching { BigDecimal(value.toString()).setScale(digits, mode).toDouble() }.getOrDefault(Double.NaN))
                }
            }
        }

        return when (name) {
            "SUM" -> numbers(given).let { it.error ?: finite(it.values.sum()) }
            "PRODUCT" -> numbers(given).let { it.error ?: finite(it.values.fold(1.0) { a, b -> a * b }) }
            "AVERAGE" -> numbers(given).let {
                it.error ?: if (it.values.isEmpty()) CellValue.Error("#DIV/0!") else finite(it.values.sum() / it.values.size)
            }
            "MIN" -> numbers(given).let { it.error ?: CellValue.Number(it.values.minOrNull() ?: 0.0) }
            "MAX" -> numbers(given).let { it.error ?: CellValue.Number(it.values.maxOrNull() ?: 0.0) }
            // Counting passes over errors as it does over text.
            "COUNT" -> CellValue.Number(numbers(given).values.size.toDouble())
            "COUNTA" -> {
                var counted = 0
                for (term in given) {
                    if (term is Area) each(term) { if (it != CellValue.Empty) counted++ } else counted++
                }
                CellValue.Number(counted.toDouble())
            }
            "ABS" -> { count(1..1); numeric(0) { finite(abs(it)) } }
            "INT" -> { count(1..1); numeric(0) { finite(floor(it)) } }
            "SQRT" -> { count(1..1); numeric(0) { finite(sqrt(it)) } }
            // Half goes away from zero: 2.5 to 3, -2.5 to -3.
            "ROUND" -> rounded(RoundingMode.HALF_UP)
            "ROUNDUP" -> rounded(RoundingMode.UP)
            "ROUNDDOWN" -> rounded(RoundingMode.DOWN)
            "IF" -> {
                count(2..3)
                when (val condition = truth(one(0))) {
                    is CellValue.Logical -> when {
                        condition.value -> one(1)
                        given.size == 3 -> one(2)
                        else -> CellValue.Logical(false)
                    }
                    else -> condition
                }
            }
            "IFERROR" -> { count(2..2); one(0).let { if (it is CellValue.Error) one(1) else it } }
            "NOT" -> { count(1..1); truth(one(0)).let { if (it is CellValue.Logical) CellValue.Logical(!it.value) else it } }
            "AND", "OR" -> {
                var result: Boolean? = null
                var error: CellValue? = null
                fun take(value: CellValue, outright: Boolean) {
                    // In a range, text and empty cells are passed over.
                    if (!outright && (value is CellValue.Text || value == CellValue.Empty)) return
                    when (val known = truth(value)) {
                        is CellValue.Logical -> result = if (result == null) known.value else if (name == "AND") result!! && known.value else result!! || known.value
                        else -> if (error == null) error = known
                    }
                }
                for (term in given) {
                    if (term is Area && !term.single) each(term) { take(it, outright = false) } else take(scalar(term), outright = term !is Area)
                }
                error ?: result?.let { CellValue.Logical(it) } ?: CellValue.Error("#VALUE!")
            }
            "TRUE" -> { count(0..0); CellValue.Logical(true) }
            "FALSE" -> { count(0..0); CellValue.Logical(false) }
            // A range and what to look for in it, as many times over as there are conditions.
            "COUNTIF", "COUNTIFS" -> {
                if (given.size < 2 || given.size % 2 != 0 || (name == "COUNTIF" && given.size != 2)) throw NotWorkedOut()
                conditional('C', null, given.indices.step(2).map { range(it) }, given.indices.step(2).map { Criterion(one(it + 1), matching) })
            }
            // What to add up first, then the conditions.
            "SUMIFS", "AVERAGEIFS" -> {
                if (given.size < 3 || given.size % 2 != 1) throw NotWorkedOut()
                val pairs = (1 until given.size).step(2)
                conditional(if (name == "SUMIFS") 'S' else 'A', range(0), pairs.map { range(it) }, pairs.map { Criterion(one(it + 1), matching) })
            }
            // One condition, and what to add up last: where it begins is all
            // that counts of it, its shape being that of the range looked through.
            "SUMIF", "AVERAGEIF" -> {
                count(2..3)
                val looked = range(0)
                val values = if (given.size < 3) looked else range(2).let {
                    area(it.sheet, it.firstRow, it.firstColumn, it.firstRow + looked.rows - 1, it.firstColumn + looked.columns - 1)
                }
                conditional(if (name == "SUMIF") 'S' else 'A', values, listOf(looked), listOf(Criterion(one(1), matching)))
            }
            else -> throw NotWorkedOut()
        }
    }

    companion object {
        /** Why a formula was not worked out, when it may yet be: it rests on others not worked out so far. */
        const val LATER = "later"

        private const val MAX_COLUMN = 16_384
        private const val MAX_ROW = 1_048_576
        private const val MAX_NESTING = 64

        /** As many as Excel lets a function be given. */
        private const val MAX_ARGUMENTS = 255

        /** The longest a condition with * or ? in it is followed, and the longest text it is set against. */
        private const val MAX_PATTERN = 64
        private const val MAX_MATCHED = 255

        /** Letters gone over in setting text against patterns, for one sheet's formulas. */
        private const val MAX_MATCHING = 50_000_000L
    }
}
