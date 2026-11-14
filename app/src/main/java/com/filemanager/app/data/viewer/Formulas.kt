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
    private class Area(val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int) : Term {
        val single: Boolean get() = firstRow == lastRow && firstColumn == lastColumn
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
                char == '$' || char.isLetter() -> named()
                else -> unsupported()
            }
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
            cell()?.let { first ->
                val afterFirst = at
                if (take(":")) {
                    spaces()
                    val second = cell() ?: unsupported()
                    return area(first, second)
                }
                at = afterFirst
                return area(first, first)
            }
            at = start
            while (peek().isLetterOrDigit() || peek() == '.' || peek() == '_') at++
            val name = text.substring(start, at).uppercase()
            if (name.isEmpty()) unsupported()
            if (peek() != '(') {
                return when (name) {
                    "TRUE" -> Value(CellValue.Logical(true))
                    "FALSE" -> Value(CellValue.Logical(false))
                    // A name, another sheet's cells, a whole column: not worked out here.
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

        private fun area(a: Pair<Int, Int>, b: Pair<Int, Int>): Area {
            val area = Area(
                firstRow = minOf(a.first, b.first),
                firstColumn = minOf(a.second, b.second),
                lastRow = maxOf(a.first, b.first),
                lastColumn = maxOf(a.second, b.second),
            )
            // Into rows or columns that were never read: what is there is not known.
            if (area.lastRow > cells.readThroughRow || area.lastColumn > cells.readThroughColumn) unsupported()
            return area
        }
    }

    /** One value for [term]: a value as it is, a single cell's own. A range has no one value. */
    private fun scalar(term: Term): CellValue = when (term) {
        is Value -> term.value
        is Area -> if (term.single) cells.at(term.firstRow, term.firstColumn) else throw NotWorkedOut()
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
                when (val value = cells.at(term.firstRow, term.firstColumn)) {
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
        val lastRow = minOf(area.lastRow, cells.lastRow)
        val lastColumn = minOf(area.lastColumn, cells.lastColumn)
        if (area.firstRow > lastRow || area.firstColumn > lastColumn) return
        cells.within(area.firstRow, area.firstColumn, lastRow, lastColumn, visit)
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
        fun finite(value: Double): CellValue =
            if (value.isNaN() || value.isInfinite()) CellValue.Error("#NUM!") else CellValue.Number(value)
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
    }
}
