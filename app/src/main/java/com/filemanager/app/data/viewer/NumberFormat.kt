package com.filemanager.app.data.viewer

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/*
 * How Excel shows a number: its number formats, such as "#,##0.00", "0%" and
 * "d mmm yyyy". A format has up to four sections split by ";" - positive
 * numbers, negative ones, zero and text - each a pattern of digit places,
 * parts of a date, and text shown as it is.
 *
 * Colours ("[Red]") and conditions ("[>100]") are read past: the viewer
 * shows the number, not the colour, and the sections are taken in their
 * usual order.
 */

/** A number format, read once and used for every cell that has it. */
internal class NumberFormat private constructor(private val sections: List<List<Token>>) {

    /** [value] as this format shows it, in a workbook that counts days from 1904 if [date1904]. */
    fun number(value: Double, date1904: Boolean): String {
        if (value.isNaN() || value.isInfinite()) return "#NUM!"
        // With a section of its own, a negative number is shown by it, sign
        // and all: (12) or -12, as the format says.
        val (section, signed) = when {
            value < 0 && sections.size >= 2 -> sections[1] to false
            value == 0.0 && sections.size >= 3 -> sections[2] to false
            else -> sections[0] to (value < 0)
        }
        val sign = if (signed) "-" else ""
        return when {
            section.any { it is Token.Part || it is Token.Elapsed || it is Token.Meridiem } ->
                if (value < 0) general(value) else date(value, section, date1904)
            section.any { it == Token.General } -> sign + render(section) { general(abs(value)) }
            section.any { it is Token.Digit } -> sign + digits(abs(value), section)
            // Text only, or "@" alone, which shows a number as General does.
            section.any { it == Token.At } -> general(value)
            else -> sign + render(section) { "" }
        }
    }

    /** [text] as this format shows text: as it is, unless the format has a section for text. */
    fun text(text: String): String {
        val section = when {
            sections.size >= 4 -> sections[3]
            sections.size == 1 && sections[0].any { it == Token.At } -> sections[0]
            else -> return text
        }
        return buildString {
            for (token in section) {
                when (token) {
                    is Token.Text -> append(token.text)
                    Token.At -> append(text)
                    else -> Unit
                }
            }
        }
    }

    private sealed interface Token {
        /** Shown as it is: quoted, escaped, or a character with no meaning of its own. */
        data class Text(val text: String) : Token
        /** A digit place: 0 always shows a digit, # only a significant one, ? a space for an insignificant one. */
        data class Digit(val char: Char) : Token
        data object Point : Token
        data object Comma : Token
        data object Percent : Token
        data class Exponent(val plus: Boolean) : Token
        data object General : Token
        data object At : Token
        /** Part of a date or time: y, M for month, d, h, m for minute, s. */
        data class Part(val unit: Char, val length: Int) : Token
        /** Time gone by, in all: [h], [mm], [ss]. */
        data class Elapsed(val unit: Char, val length: Int) : Token
        /** Fractions of a second, after the seconds: ss.00. */
        data class Fraction(val digits: Int) : Token
        /** AM/PM or A/P, as written. */
        data class Meridiem(val text: String) : Token
    }

    /** [tokens] as text, with General shown by [general]. */
    private fun render(tokens: List<Token>, general: () -> String): String = buildString {
        for (token in tokens) {
            when (token) {
                is Token.Text -> append(token.text)
                Token.General -> append(general())
                Token.Percent -> append('%')
                else -> Unit
            }
        }
    }

    /** [value], not negative, in a section of digit places. */
    private fun digits(value: Double, tokens: List<Token>): String {
        val exponentAt = tokens.indexOfFirst { it is Token.Exponent }
        val end = if (exponentAt < 0) tokens.size else exponentAt
        val pointAt = (0 until end).firstOrNull { tokens[it] == Token.Point } ?: end
        // A fraction, "# ?/?": shown as a plain number instead.
        if ((1 until end - 1).any { tokens[it] == Token.Text("/") && tokens[it - 1] is Token.Digit && tokens[it + 1] is Token.Digit }) {
            return general(value)
        }
        val integerSlots = (0 until pointAt).filter { tokens[it] is Token.Digit }
        val decimalSlots = (pointAt + 1 until end).filter { tokens[it] is Token.Digit }
        val lastInteger = integerSlots.lastOrNull() ?: -1
        val lastDecimal = decimalSlots.lastOrNull() ?: pointAt
        // A comma between digit places groups thousands; after the last one,
        // each comma divides by a thousand: "0.0,," shows millions.
        val grouping = integerSlots.size >= 2 &&
            (integerSlots.first() until lastInteger).any { tokens[it] == Token.Comma }
        val thousands = (lastInteger + 1 until pointAt).count { tokens[it] == Token.Comma } +
            (lastDecimal + 1 until end).count { tokens[it] == Token.Comma }

        var number = value
        repeat(tokens.count { it == Token.Percent }) { number *= 100 }
        repeat(thousands) { number /= 1000 }

        var exponentText = ""
        if (exponentAt >= 0) {
            val places = integerSlots.size.coerceAtLeast(1)
            // "##0.0E+0" keeps the exponent a multiple of three; "0.00E+00" has one digit before the point.
            val engineering = places > 1 && tokens[integerSlots.first()] == Token.Digit('#')
            var exponent = 0
            if (number != 0.0) {
                val magnitude = floor(log10(number)).toInt()
                exponent = if (engineering) Math.floorDiv(magnitude, places) * places else magnitude - (places - 1)
                var mantissa = decimal(number).movePointLeft(exponent).setScale(decimalSlots.size, RoundingMode.HALF_UP)
                if (mantissa >= BigDecimal.TEN.pow(places)) {
                    exponent += if (engineering) places else 1
                    mantissa = decimal(number).movePointLeft(exponent).setScale(decimalSlots.size, RoundingMode.HALF_UP)
                }
                number = mantissa.toDouble()
            }
            val plus = (tokens[exponentAt] as Token.Exponent).plus
            val zeros = (exponentAt + 1 until tokens.size).count { tokens[it] == Token.Digit('0') }.coerceAtLeast(1)
            exponentText = "E" + (if (exponent < 0) "-" else if (plus) "+" else "") +
                abs(exponent).toString().padStart(zeros, '0')
        }

        val rounded = decimal(number).setScale(decimalSlots.size, RoundingMode.HALF_UP)
        val integer = rounded.toBigInteger().toString().let { if (it == "0") "" else it }
        val fraction = rounded.toPlainString().substringAfter('.', "").padEnd(decimalSlots.size, '0')

        val shown = arrayOfNulls<String>(tokens.size)
        if (grouping) {
            val zeros = integerSlots.count { tokens[it] == Token.Digit('0') }
            shown[integerSlots.first()] = group(integer.padStart(zeros, '0'))
        } else {
            // Right to left, a digit a place; the leftmost place takes any left over.
            var left = integer.length
            for (k in integerSlots.indices.reversed()) {
                val slot = integerSlots[k]
                shown[slot] = when {
                    left > 0 && k == 0 -> integer.substring(0, left).also { left = 0 }
                    left > 0 -> integer[--left].toString()
                    tokens[slot] == Token.Digit('0') -> "0"
                    tokens[slot] == Token.Digit('?') -> " "
                    else -> ""
                }
            }
        }
        for ((k, slot) in decimalSlots.withIndex()) {
            val restZero = (k until fraction.length).all { fraction[it] == '0' }
            shown[slot] = when {
                !restZero -> fraction[k].toString()
                tokens[slot] == Token.Digit('0') -> "0"
                tokens[slot] == Token.Digit('?') -> " "
                else -> ""
            }
        }

        return buildString {
            for ((index, token) in tokens.withIndex()) {
                when (token) {
                    is Token.Text -> append(token.text)
                    is Token.Digit -> append(shown[index].orEmpty())
                    // ".00" with no place for the whole number still shows it.
                    Token.Point -> append(if (integerSlots.isEmpty()) "$integer." else ".")
                    Token.Percent -> append('%')
                    is Token.Exponent -> append(exponentText)
                    else -> Unit
                }
            }
        }
    }

    private fun date(value: Double, tokens: List<Token>, date1904: Boolean): String {
        val fractionDigits = tokens.filterIsInstance<Token.Fraction>().maxOfOrNull { it.digits } ?: 0
        var perSecond = 1L
        repeat(fractionDigits) { perSecond *= 10 }
        // Rounded to the shown precision first, so 23:59:59.7 becomes the next day.
        val units = Math.round(value * 86_400.0 * perSecond)
        val perDay = 86_400L * perSecond
        val days = units / perDay
        val second = units % perDay / perSecond
        val fraction = units % perSecond
        val date = epoch(days, date1904).plusDays(days)
        val hour = (second / 3600).toInt()
        val minute = (second / 60 % 60).toInt()
        val sec = (second % 60).toInt()
        val twelveHour = tokens.any { it is Token.Meridiem }

        return buildString {
            for (token in tokens) {
                when (token) {
                    is Token.Text -> append(token.text)
                    is Token.Part -> append(
                        when (token.unit) {
                            'y' -> if (token.length <= 2) pad(date.year % 100) else date.year.toString()
                            'M' -> when (token.length) {
                                1 -> date.monthValue.toString()
                                2 -> pad(date.monthValue)
                                3 -> MONTHS[date.monthValue - 1].take(3)
                                4 -> MONTHS[date.monthValue - 1]
                                else -> MONTHS[date.monthValue - 1].take(1)
                            }
                            'd' -> when (token.length) {
                                1 -> date.dayOfMonth.toString()
                                2 -> pad(date.dayOfMonth)
                                3 -> DAYS[date.dayOfWeek.value - 1].take(3)
                                else -> DAYS[date.dayOfWeek.value - 1]
                            }
                            'h' -> {
                                val h = if (twelveHour) (hour % 12).let { if (it == 0) 12 else it } else hour
                                if (token.length >= 2) pad(h) else h.toString()
                            }
                            'm' -> if (token.length >= 2) pad(minute) else minute.toString()
                            's' -> if (token.length >= 2) pad(sec) else sec.toString()
                            else -> ""
                        },
                    )
                    is Token.Elapsed -> {
                        val seconds = units / perSecond
                        val total = when (token.unit) {
                            'h' -> seconds / 3600
                            'm' -> seconds / 60
                            else -> seconds
                        }
                        append(total.toString().padStart(token.length, '0'))
                    }
                    is Token.Fraction -> append('.').append(fraction.toString().padStart(fractionDigits, '0').take(token.digits))
                    is Token.Meridiem -> append(if (hour < 12) token.text.substringBefore('/') else token.text.substringAfter('/'))
                    is Token.Digit -> append(token.char)
                    Token.Point -> append('.')
                    Token.Comma -> append(',')
                    Token.Percent -> append('%')
                    else -> Unit
                }
            }
        }
    }

    companion object {
        val GENERAL = NumberFormat(listOf(listOf(Token.General)))

        /** The format with code [code], as a workbook's styles give it. */
        fun of(code: String): NumberFormat {
            if (code.isBlank() || code.equals("General", ignoreCase = true)) return GENERAL
            return NumberFormat(split(code).map(::tokens))
        }

        /**
         * The code of one of Excel's own formats, which a workbook uses by
         * number without writing out. Short dates are shown as 9 Nov 2026:
         * Excel shows them as the phone's region would, and the day and
         * month can't be told apart in 9/11/2026.
         */
        fun builtIn(id: Int): String = when (id) {
            1 -> "0"
            2 -> "0.00"
            3, 37 -> "#,##0"
            4, 39 -> "#,##0.00"
            5, 6 -> "#,##0;-#,##0"
            7, 8 -> "#,##0.00;-#,##0.00"
            38 -> "#,##0;(#,##0)"
            40 -> "#,##0.00;(#,##0.00)"
            9 -> "0%"
            10 -> "0.00%"
            11 -> "0.00E+00"
            12 -> "# ?/?"
            13 -> "# ??/??"
            14, in 27..31, in 34..36, in 50..58 -> "d mmm yyyy"
            15 -> "d-mmm-yy"
            16 -> "d-mmm"
            17 -> "mmm-yy"
            18 -> "h:mm AM/PM"
            19 -> "h:mm:ss AM/PM"
            20, 32 -> "h:mm"
            21, 33 -> "h:mm:ss"
            22 -> "d mmm yyyy h:mm"
            45 -> "mm:ss"
            46 -> "[h]:mm:ss"
            47 -> "mm:ss.0"
            48 -> "##0.0E+0"
            49 -> "@"
            else -> "General"
        }

        private val MONTHS = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
        private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        private fun pad(n: Int) = n.toString().padStart(2, '0')

        /**
         * Day 0 of the workbook's dates. Excel counts 1900 as a leap year,
         * as Lotus 1-2-3 did: from 1 March 1900 its days are a day ahead of
         * the calendar's.
         */
        private fun epoch(days: Long, date1904: Boolean): LocalDate = when {
            date1904 -> LocalDate.of(1904, 1, 1)
            days < 61 -> LocalDate.of(1899, 12, 31)
            else -> LocalDate.of(1899, 12, 30)
        }

        private fun decimal(value: Double) = BigDecimal(value.toString())

        private fun group(digits: String): String {
            if (digits.length <= 3) return digits
            val first = digits.length % 3
            return buildString {
                append(digits, 0, first)
                for (i in first until digits.length step 3) {
                    if (isNotEmpty()) append(',')
                    append(digits, i, i + 3)
                }
            }
        }

        /** The sections of [code], split at each ";" that isn't quoted, escaped or in brackets. */
        private fun split(code: String): List<String> {
            val sections = ArrayList<String>()
            val current = StringBuilder()
            var quoted = false
            var bracket = false
            var i = 0
            while (i < code.length) {
                val c = code[i]
                when {
                    quoted -> { current.append(c); if (c == '"') quoted = false }
                    bracket -> { current.append(c); if (c == ']') bracket = false }
                    c == '"' -> { current.append(c); quoted = true }
                    c == '[' -> { current.append(c); bracket = true }
                    c == '\\' || c == '_' || c == '*' -> {
                        current.append(c)
                        if (i + 1 < code.length) current.append(code[++i])
                    }
                    c == ';' -> { sections += current.toString(); current.setLength(0) }
                    else -> current.append(c)
                }
                i++
            }
            sections += current.toString()
            return sections
        }

        private fun tokens(section: String): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            while (i < section.length) {
                val c = section[i]
                val lower = c.lowercaseChar()
                when {
                    c == '"' -> {
                        val end = section.indexOf('"', i + 1).let { if (it < 0) section.length else it }
                        out += Token.Text(section.substring(i + 1, end))
                        i = end + 1
                    }
                    c == '\\' -> {
                        if (i + 1 < section.length) out += Token.Text(section[i + 1].toString())
                        i += 2
                    }
                    // "_)" leaves the width of a ")"; a space stands in for it.
                    c == '_' -> { out += Token.Text(" "); i += 2 }
                    // "* " fills the cell with spaces: nothing here.
                    c == '*' -> i += 2
                    c == '[' -> {
                        val end = section.indexOf(']', i + 1).let { if (it < 0) section.length else it }
                        bracket(section.substring(i + 1, end))?.let { out += it }
                        i = end + 1
                    }
                    section.startsWith("General", i, ignoreCase = true) -> { out += Token.General; i += 7 }
                    section.startsWith("AM/PM", i, ignoreCase = true) -> { out += Token.Meridiem(section.substring(i, i + 5)); i += 5 }
                    section.startsWith("A/P", i, ignoreCase = true) -> { out += Token.Meridiem(section.substring(i, i + 3)); i += 3 }
                    c == '0' || c == '#' || c == '?' -> { out += Token.Digit(c); i++ }
                    c == '.' -> {
                        val previous = out.lastOrNull { it !is Token.Text }
                        val afterSeconds = (previous is Token.Part && previous.unit == 's') ||
                            (previous is Token.Elapsed && previous.unit == 's')
                        var zeros = 0
                        while (afterSeconds && i + 1 + zeros < section.length && section[i + 1 + zeros] == '0') zeros++
                        if (zeros > 0) {
                            out += Token.Fraction(zeros)
                            i += 1 + zeros
                        } else {
                            out += Token.Point
                            i++
                        }
                    }
                    c == ',' -> { out += Token.Comma; i++ }
                    c == '%' -> { out += Token.Percent; i++ }
                    lower == 'e' && i + 1 < section.length && (section[i + 1] == '+' || section[i + 1] == '-') -> {
                        out += Token.Exponent(section[i + 1] == '+')
                        i += 2
                    }
                    lower in "ymdhs" -> {
                        var n = 1
                        while (i + n < section.length && section[i + n].lowercaseChar() == lower) n++
                        out += Token.Part(lower, n)
                        i += n
                    }
                    c == '@' -> { out += Token.At; i++ }
                    else -> { out += Token.Text(c.toString()); i++ }
                }
            }
            return minutesOrMonths(out)
        }

        /** What a bracket holds: time gone by, a currency symbol - or a colour or condition, not shown. */
        private fun bracket(inside: String): Token? {
            val lower = inside.lowercase()
            return when {
                lower.isNotEmpty() && lower.all { it == 'h' } -> Token.Elapsed('h', lower.length)
                lower.isNotEmpty() && lower.all { it == 'm' } -> Token.Elapsed('m', lower.length)
                lower.isNotEmpty() && lower.all { it == 's' } -> Token.Elapsed('s', lower.length)
                // [$€-407]: the symbol, then the region it is written for.
                inside.startsWith("$") -> inside.drop(1).substringBefore('-').takeIf { it.isNotEmpty() }?.let { Token.Text(it) }
                else -> null
            }
        }

        /** An "m" is minutes right after hours or before seconds, else the month. */
        private fun minutesOrMonths(tokens: List<Token>): List<Token> {
            val timed = tokens.indices.filter { tokens[it] is Token.Part || tokens[it] is Token.Elapsed }
            return tokens.mapIndexed { index, token ->
                if (token !is Token.Part || token.unit != 'm') return@mapIndexed token
                val at = timed.indexOf(index)
                val before = timed.getOrNull(at - 1)?.let { tokens[it] }
                val after = timed.getOrNull(at + 1)?.let { tokens[it] }
                val minutes = before.isUnit('h') || after.isUnit('s')
                if (minutes) token else Token.Part('M', token.length)
            }
        }

        private fun Token?.isUnit(unit: Char) =
            (this is Token.Part && this.unit == unit) || (this is Token.Elapsed && this.unit == unit)
    }
}

/**
 * A number as Excel's General format shows it in [characters] - 11 in a
 * column of the usual width - written out or as 1.23457E+11, whichever keeps
 * more of its digits.
 */
internal fun general(value: Double, characters: Int = GENERAL_WIDTH): String {
    if (value == 0.0) return "0"
    if (value.isNaN() || value.isInfinite()) return "#NUM!"
    val sign = if (value < 0) "-" else ""
    val magnitude = BigDecimal(abs(value).toString())
    val width = (characters - sign.length).coerceAtLeast(1)
    val plain = plainGeneral(magnitude, width)
    val scientific = scientificGeneral(magnitude, width)
    return sign + if (plain != null && significant(plain) >= significant(scientific)) plain else scientific
}

private const val GENERAL_WIDTH = 11

/** [magnitude] written out in [width] characters at most, or null when its whole part is longer. */
private fun plainGeneral(magnitude: BigDecimal, width: Int): String? {
    val wholeDigits = magnitude.setScale(0, RoundingMode.DOWN).toPlainString().length
    if (wholeDigits > width) return null
    val decimals = (width - wholeDigits - 1).coerceAtLeast(0)
    val text = magnitude.setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    // Rounding up can carry into another digit: 99999999999.7.
    return text.takeIf { it.length <= width }
}

/** [magnitude] as 1.23457E+11, in [width] characters. */
private fun scientificGeneral(magnitude: BigDecimal, width: Int): String {
    var exponent = magnitude.precision() - magnitude.scale() - 1
    while (true) {
        val exponentText = (if (exponent < 0) "-" else "+") + abs(exponent).toString().padStart(2, '0')
        // The first digit, the point and the E take three characters.
        val decimals = (width - 3 - exponentText.length).coerceAtLeast(0)
        val mantissa = magnitude.movePointLeft(exponent).setScale(decimals, RoundingMode.HALF_UP)
        if (mantissa < BigDecimal.TEN) return mantissa.stripTrailingZeros().toPlainString() + "E" + exponentText
        exponent++
    }
}

/** How many significant digits [text] shows. */
private fun significant(text: String): Int = text.substringBefore('E').filter { it.isDigit() }.trimStart('0').length
