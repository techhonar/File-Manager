package com.filemanager.app.data.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormulasTest {

    /** A sheet of the cells given, by address: numbers, text, TRUE and FALSE, and errors as "#...". */
    private fun sheet(vararg cells: Pair<String, Any>): Formulas {
        fun held(value: Any): CellValue = when (value) {
            is Number -> CellValue.Number(value.toDouble())
            is Boolean -> CellValue.Logical(value)
            is String -> if (value.startsWith("#")) CellValue.Error(value) else CellValue.Text(value)
            else -> throw IllegalArgumentException("no such cell value")
        }
        val held: Map<Pair<Int, Int>, CellValue> =
            cells.associate { (address, value) -> (rowOf(address)!! to columnOf(address)!!) to held(value) }
        return Formulas(
            object : FormulaCells {
                override val lastRow = held.keys.maxOfOrNull { it.first } ?: 0
                override val lastColumn = held.keys.maxOfOrNull { it.second } ?: 0
                override fun at(row: Int, column: Int): CellValue = held[row to column] ?: CellValue.Empty
                override fun within(firstRow: Int, firstColumn: Int, lastRow: Int, lastColumn: Int, each: (CellValue) -> Unit) {
                    for ((where, value) in held) {
                        if (where.first in firstRow..lastRow && where.second in firstColumn..lastColumn) each(value)
                    }
                }
            },
        )
    }

    private val numbers = sheet(
        "A1" to 12, "B1" to 5, "C1" to 2, "D1" to 0.25,
        "A2" to 1250, "B2" to 830, "C2" to 1422, "D2" to 480,
        "A3" to "north", "B3" to "North", "C3" to "12", "D3" to true,
        "A4" to 0, "B4" to "#N/A",
    )

    private fun number(formula: String, cells: Formulas = numbers) = (cells.evaluate(formula) as CellValue.Number).value
    private fun text(formula: String, cells: Formulas = numbers) = (cells.evaluate(formula) as CellValue.Text).text
    private fun truth(formula: String, cells: Formulas = numbers) = (cells.evaluate(formula) as CellValue.Logical).value
    private fun error(formula: String, cells: Formulas = numbers) = (cells.evaluate(formula) as CellValue.Error).code

    @Test
    fun `arithmetic in Excel's order, with a sign before a power and powers from the left`() {
        assertEquals(17.0, number("A1+B1"), 0.0)
        assertEquals(2.0, number("=A1-B1*C1"), 0.0)
        assertEquals(8.5, number("(A1+B1)/C1"), 0.0)
        assertEquals(144.0, number("-A1^2"), 0.0)
        assertEquals(64.0, number("2^3^2"), 0.0)
        assertEquals(0.5, number("2^-1"), 0.0)
        assertEquals(0.0025, number("D1%"), 1e-12)
        assertEquals(6.0, number("50%*A1"), 0.0)
        assertEquals(5.0, number("10-2-3"), 0.0)
        assertEquals(2.0, number("100/10/5"), 0.0)
        assertEquals(12.0, number(" - - A1 "), 0.0)
        assertEquals(1001.0, number("1E3+1"), 0.0)
        assertEquals(1.0, number(".5+.5"), 0.0)
        assertEquals(12.0, number("\$A\$1"), 0.0)
    }

    @Test
    fun `what isn't a number in arithmetic - nothing is 0, TRUE is 1, text is what it spells`() {
        assertEquals(12.0, number("A1+Z9"), 0.0)
        assertEquals(13.0, number("C3+1"), 0.0)
        assertEquals(2.0, number("D3+1"), 0.0)
        assertEquals("#VALUE!", error("A3+1"))
        assertEquals("#DIV/0!", error("A1/A4"))
        assertEquals("#DIV/0!", error("A1/Z9"))
        // An error is what anything done with it comes to.
        assertEquals("#N/A", error("B4+1"))
        assertEquals("#N/A", error("1/B4"))
        assertEquals("#NUM!", error("(-8)^0.5"))
        assertEquals(0.0, number("Z9"), 0.0)
    }

    @Test
    fun `joining writes a number with the fifteen digits Excel keeps`() {
        assertEquals("ab", text("\"a\"&\"b\""))
        assertEquals("12 units", text("A1&\" units\""))
        assertEquals("12", text("1&2"))
        assertEquals("TRUEx", text("D3&\"x\""))
        assertEquals("0.3", text("(0.1+0.2)&\"\""))
        assertEquals("0.333333333333333", text("1/3&\"\""))
        assertEquals("1E+15", text("1000000000000000&\"\""))
        assertEquals("123456789012", text("123456789012&\"\""))
        assertEquals("0.000012345", text("0.000012345&\"\""))
        assertEquals("say \"hi\"", text("\"say \"\"hi\"\"\""))
        assertEquals("x", text("Z9&\"x\""))
    }

    @Test
    fun `comparing - numbers before text before TRUE, text whatever its case`() {
        assertEquals(true, truth("A1>B1"))
        assertEquals(false, truth("A1=B1"))
        assertEquals(true, truth("A1<>B1"))
        assertEquals(true, truth("A1>=12"))
        assertEquals(false, truth("A1<=11.99"))
        assertEquals(true, truth("A3=B3"))
        assertEquals(true, truth("\"a\"<\"b\""))
        assertEquals(true, truth("1<\"a\""))
        assertEquals(true, truth("TRUE>\"z\""))
        // Nothing is 0 beside a number and no text beside text.
        assertEquals(true, truth("Z9=0"))
        assertEquals(true, truth("Z9=\"\""))
        assertEquals("#N/A", error("B4=1"))
    }

    @Test
    fun `totals take the numbers of a range and pass over the rest`() {
        assertEquals(3982.0, number("SUM(A2:D2)"), 0.0)
        // Text, TRUE and an empty cell in a range don't count - nor text that spells a number.
        assertEquals(19.25, number("SUM(A1:D3)") - 3982, 1e-9)
        assertEquals(995.5, number("AVERAGE(A2:D2)"), 0.0)
        assertEquals(480.0, number("MIN(A2:D2)"), 0.0)
        assertEquals(1422.0, number("MAX(A2:D2)"), 0.0)
        assertEquals(8.0, number("COUNT(A1:D3)"), 0.0)
        assertEquals(12.0, number("COUNTA(A1:D3)"), 0.0)
        assertEquals(30.0, number("PRODUCT(A1:D1)"), 0.0)
        // Given outright, text that spells a number is one, and TRUE is 1.
        assertEquals(7.0, number("SUM(1,\"2\",3,TRUE)"), 0.0)
        assertEquals("#VALUE!", error("SUM(1,\"two\")"))
        assertEquals(19.0, number("SUM(A1,B1,C1:C1)"), 0.0)
        assertEquals(3982.0, number("SUM((D2:A2))"), 0.0)
        // No numbers at all: nothing to average, and nothing to be least.
        assertEquals("#DIV/0!", error("AVERAGE(A3:B3)"))
        assertEquals(0.0, number("MIN(A3:B3)"), 0.0)
        assertEquals(0.0, number("SUM(Y1:Z9)"), 0.0)
        // An error among them is what the total comes to; counting passes over it.
        assertEquals("#N/A", error("SUM(A4:B4)"))
        assertEquals(1.0, number("COUNT(A4:B4)"), 0.0)
        assertEquals(4.0, number("SUM(A2:D2)/COUNT(A2:D2)") - 991.5, 1e-9)
    }

    @Test
    fun `rounding goes away from zero at a half, to either side of the point`() {
        assertEquals(3.0, number("ROUND(2.5,0)"), 0.0)
        assertEquals(-3.0, number("ROUND(-2.5,0)"), 0.0)
        assertEquals(2.68, number("ROUND(2.675,2)"), 0.0)
        assertEquals(1.01, number("ROUND(1.005,2)"), 0.0)
        assertEquals(1200.0, number("ROUND(1234.567,-2)"), 0.0)
        assertEquals(1.3, number("ROUNDUP(1.21,1)"), 0.0)
        assertEquals(-2.0, number("ROUNDUP(-1.01,0)"), 0.0)
        assertEquals(-1.2, number("ROUNDDOWN(-1.29,1)"), 0.0)
        assertEquals(-2.0, number("INT(-1.5)"), 0.0)
        assertEquals(3.0, number("ABS(-3)"), 0.0)
        assertEquals(4.0, number("SQRT(16)"), 0.0)
        assertEquals("#NUM!", error("SQRT(-1)"))
        assertEquals("#VALUE!", error("ABS(A3)"))
    }

    @Test
    fun `choosing by a condition`() {
        assertEquals("more", text("IF(A1>B1,\"more\",\"less\")"))
        assertEquals(1.0, number("IF(A1>B1,1)"), 0.0)
        assertEquals(false, truth("IF(A1<B1,1)"))
        assertEquals("n", text("IF(A4,\"y\",\"n\")"))
        assertEquals("first", text("IF(A1>10,IF(B1>10,\"both\",\"first\"),\"neither\")"))
        assertEquals("#VALUE!", error("IF(A3,1,2)"))
        assertEquals(true, truth("AND(A1>1,B1>1)"))
        assertEquals(false, truth("OR(A1>100,B1>100)"))
        assertEquals(false, truth("NOT(A1>1)"))
        assertEquals(true, truth("AND(1,2,3)"))
        assertEquals("n/a", text("IFERROR(A1/A4,\"n/a\")"))
        assertEquals(2.4, number("IFERROR(A1/B1,0)"), 0.0)
        assertEquals(60.0, number("IF(AND(A1>0,B1>0),A1*B1,0)"), 0.0)
        assertEquals(true, truth("TRUE"))
        assertEquals(false, truth("false()"))
    }

    @Test
    fun `what is not worked out here is not guessed at`() {
        // A lookup, another sheet, a name, a whole column, a function not known.
        listOf(
            "VLOOKUP(A1,A1:B4,2,FALSE)", "Sheet2!A1", "'My sheet'!A1+1", "Rate*A1", "SUM(A:A)", "SUM(1:1)", "NOW()", "LEN(A3)",
            "LOG10(A1)", "Table1[Units]", "{1,2,3}", "A1:B2", "A1 B1", "#REF!+1", "A1+", "(A1", "SUM(A1", "\"open", "",
        ).forEach { assertNull(it, numbers.evaluate(it)) }
        // Brackets a thousand deep are not followed down.
        assertNull(numbers.evaluate("(".repeat(1_000) + "1" + ")".repeat(1_000)))
        assertEquals(1.0, number("(".repeat(40) + "1" + ")".repeat(40)), 0.0)
        // Nor signs a thousand long refused: they cost nothing.
        assertEquals(-1.0, number("-".repeat(1_001) + "1"), 0.0)
    }

    @Test
    fun `past the largest number there is, a result is no number`() {
        // Written like a number, and none: it used to be infinity, which nothing after it could write.
        assertNull(numbers.evaluate("1E999"))
        assertNull(numbers.evaluate("1E999&\"\""))
        val huge = sheet("A1" to 1e308, "A2" to 1e308)
        assertEquals("#NUM!", error("SUM(A1:A2)", huge))
        assertEquals("#NUM!", error("A1+A2", huge))
        assertEquals("#NUM!", error("A1*10", huge))
        assertEquals("#NUM!", error("AVERAGE(A1:A2)*3", huge))
        assertEquals(1e308, number("MAX(A1:A2)", huge), 0.0)
        assertEquals("1E+308", text("A1&\"\"", huge))
    }
}
