package com.filemanager.app.data.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberFormatTest {

    private fun shown(code: String, value: Double, date1904: Boolean = false) = NumberFormat.of(code).number(value, date1904)

    private fun builtIn(id: Int, value: Double) = shown(NumberFormat.builtIn(id), value)

    @Test
    fun `General rounds to fit eleven characters, and goes scientific at twelve digits`() {
        assertEquals("0", general(0.0))
        assertEquals("0.3", general(0.1 + 0.2))
        assertEquals("0.333333333", general(1.0 / 3))
        assertEquals("-0.33333333", general(-1.0 / 3))
        assertEquals("123456.789", general(123456.789))
        assertEquals("1234567.891", general(1234567.891234))
        assertEquals("12345678901", general(12345678901.0))
        assertEquals("1.23457E+11", general(123456789012.0))
        assertEquals("1E-10", general(1e-10))
        assertEquals("1.23E-09", general(1.23e-9))
        assertEquals("0.000123457", general(0.000123456789))
        assertEquals("1E+11", general(1e11))
        assertEquals("10000000000", general(1e10))
        assertEquals("42", general(42.0))
        assertEquals("-7.5", general(-7.5))
    }

    @Test
    fun `General rounds to fit a narrower column, as Excel does, going scientific when it must`() {
        assertEquals("0.333333", general(1.0 / 3, 8))
        assertEquals("85.66667", general(85.6666666667, 8))
        assertEquals("3.141593", general(Math.PI, 8))
        assertEquals("1.23E+08", general(123456789.0, 8))
        assertEquals("1E+06", general(1234567.0, 5))
        assertEquals("12", general(12.0, 2))
    }

    @Test
    fun `digit places round half up and pad with zeros`() {
        assertEquals("3", shown("0", 2.5))
        assertEquals("-3", shown("0", -2.5))
        assertEquals("2.68", shown("0.00", 2.675))
        assertEquals("1.00", shown("0.00", 1.0))
        assertEquals("00123", shown("00000", 123.0))
        assertEquals("123-45-6789", shown("000-00-0000", 123456789.0))
        assertEquals(".5", shown("#.##", 0.5))
        assertEquals("1.5", shown("0.0#", 1.5))
        assertEquals("1.25", shown("0.0#", 1.25))
    }

    @Test
    fun `thousands are grouped by a comma between places and scaled by one after them`() {
        assertEquals("1,234,567", shown("#,##0", 1234567.4))
        assertEquals("0", shown("#,##0", 0.0))
        assertEquals("-1,234", shown("#,##0", -1234.0))
        assertEquals("1,234.50", shown("#,##0.00", 1234.5))
        assertEquals("1,235K", shown("#,##0,\"K\"", 1234567.0))
        assertEquals("1.2M", shown("0.0,,\"M\"", 1234567.0))
    }

    @Test
    fun `percentages and scientific notation`() {
        assertEquals("26%", shown("0%", 0.256))
        assertEquals("25.6%", shown("0.0%", 0.256))
        assertEquals("1.23E+04", shown("0.00E+00", 12345.0))
        assertEquals("1.23E-04", shown("0.00E+00", 0.000123))
        assertEquals("12.3E+3", shown("##0.0E+0", 12345.0))
        assertEquals("1.00E+01", shown("0.00E+00", 9.999))
    }

    @Test
    fun `negative and zero sections replace the sign, text and currency are shown as written`() {
        assertEquals("(1,234.50)", shown("#,##0.00;(#,##0.00)", -1234.5))
        assertEquals("1,234.50", shown("#,##0.00;(#,##0.00)", 1234.5))
        assertEquals("zero", shown("0;-0;\"zero\"", 0.0))
        assertEquals("\$5.00", shown("\"$\"#,##0.00", 5.0))
        assertEquals("-\$5.00", shown("\"$\"#,##0.00", -5.0))
        assertEquals("€ 5.00", shown("[\$€-407] #,##0.00", 5.0))
        assertEquals("5.0 kg", shown("0.0 \"kg\"", 5.0))
        assertEquals("12 m²", shown("0\\ \"m²\"", 12.0))
        // Colours and conditions are read past.
        assertEquals("-3.00", shown("[Red]0.00", -3.0))
        assertEquals("-3.00", shown("[Blue]0.00;[Red]-0.00", -3.0))
        assertEquals("", shown(";;;", 5.0))
    }

    @Test
    fun `accounting formats line up with spaces and show zero as a dash`() {
        val accounting = "_-* #,##0.00_-;-* #,##0.00_-;_-* \"-\"??_-;_-@_-"
        assertEquals(" 1,234.50 ", shown(accounting, 1234.5))
        assertEquals("-1,234.50 ", shown(accounting, -1234.5))
        assertEquals("-", shown(accounting, 0.0).trim())
    }

    @Test
    fun `dates count days from 1900 with Excel's leap day, or from 1904`() {
        assertEquals("15 Mar 2023", builtIn(14, 45000.0))
        assertEquals("2023-03-15", shown("yyyy-mm-dd", 45000.0))
        assertEquals("15/03/2023 12:00", shown("dd/mm/yyyy hh:mm", 45000.5))
        assertEquals("Wednesday, March 15, 2023", shown("dddd, mmmm d, yyyy", 45000.0))
        assertEquals("Mar-23", builtIn(17, 45000.0))
        assertEquals("15-Mar", builtIn(16, 45000.0))
        assertEquals("1900-01-01", shown("yyyy-mm-dd", 1.0))
        assertEquals("1900-02-28", shown("yyyy-mm-dd", 59.0))
        assertEquals("1900-03-01", shown("yyyy-mm-dd", 61.0))
        assertEquals("1 Jan 1904", shown(NumberFormat.builtIn(14), 0.0, date1904 = true))
        // System long date, which the file says by a code: the pattern after it stands in.
        assertEquals("Wednesday, March 15, 2023", shown("[\$-F800]dddd, mmmm dd, yyyy", 45000.0))
    }

    @Test
    fun `times, twelve hour clocks, elapsed time and fractions of a second`() {
        assertEquals("6:00 PM", shown("h:mm AM/PM", 0.75))
        assertEquals("6:00 AM", shown("h:mm AM/PM", 0.25))
        assertEquals("12:00 AM", shown("h:mm AM/PM", 0.0))
        assertEquals("6:00 p", shown("h:mm a/p", 0.75))
        assertEquals("36:00:00", shown("[h]:mm:ss", 1.5))
        assertEquals("30:00", shown("[mm]:ss", 0.5 / 24))
        assertEquals("01:01.3", shown("mm:ss.0", 61.3 / 86400))
        // 23:59:59.7 rounds into the next day.
        assertEquals("0:00:00", shown("h:mm:ss", 0.999999))
        // An m after hours is minutes; anywhere else it is the month.
        assertEquals("3/15 13:05", shown("m/d h:mm", 45000 + (13 * 60 + 5) / 1440.0))
    }

    @Test
    fun `text has its own section, which can hide it`() {
        assertEquals("abc", NumberFormat.of("@").text("abc"))
        assertEquals("abc", NumberFormat.of("General").text("abc"))
        assertEquals("abc", NumberFormat.of("0.00").text("abc"))
        assertEquals("", NumberFormat.of(";;;").text("abc"))
        assertEquals("Note: x", NumberFormat.of("0.00;-0.00;0;\"Note: \"@").text("x"))
        // A number in a text-formatted cell is shown as General shows it.
        assertEquals("3.5", shown("@", 3.5))
    }

    @Test
    fun `built-in formats by number`() {
        assertEquals("1,234", builtIn(3, 1234.0))
        assertEquals("50%", builtIn(9, 0.5))
        assertEquals("50.00%", builtIn(10, 0.5))
        assertEquals("13:05", builtIn(20, (13 * 60 + 5) / 1440.0))
        assertEquals("12.5", builtIn(0, 12.5))
        assertEquals("12.5", builtIn(999, 12.5))
    }
}
