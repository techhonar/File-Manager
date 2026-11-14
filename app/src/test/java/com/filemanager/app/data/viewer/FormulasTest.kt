package com.filemanager.app.data.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormulasTest {

    /** A sheet of the cells given, by address: numbers, text, TRUE and FALSE, and errors as "#...". */
    private fun sheet(vararg cells: Pair<String, Any>): Formulas = Formulas(cells(emptyMap(), *cells))

    /** The same, with [others] as the workbook's other sheets, by their names in lower case. */
    private fun cells(others: Map<String, FormulaCells>, vararg cells: Pair<String, Any>, readThrough: Int = Int.MAX_VALUE): FormulaCells {
        fun held(value: Any): CellValue = when (value) {
            is Number -> CellValue.Number(value.toDouble())
            is Boolean -> CellValue.Logical(value)
            is String -> if (value.startsWith("#")) CellValue.Error(value) else CellValue.Text(value)
            else -> throw IllegalArgumentException("no such cell value")
        }
        val held: Map<Pair<Int, Int>, CellValue> =
            cells.associate { (address, value) -> (rowOf(address)!! to columnOf(address)!!) to held(value) }
        return object : FormulaCells {
            override val lastRow = held.keys.maxOfOrNull { it.first } ?: 0
            override val lastColumn = held.keys.maxOfOrNull { it.second } ?: 0
            override val readThroughRow = readThrough
            override fun at(row: Int, column: Int): CellValue = held[row to column] ?: CellValue.Empty
            override fun within(firstRow: Int, firstColumn: Int, lastRow: Int, lastColumn: Int, each: (CellValue) -> Unit) {
                for ((where, value) in held) {
                    if (where.first in firstRow..lastRow && where.second in firstColumn..lastColumn) each(value)
                }
            }
            override fun sheet(name: String): FormulaCells? = others[name.lowercase()]
        }
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

    /** A sheet of orders: who for, whether open, how much, how many - with a gap, text that spells a number, and an error. */
    private val orders = cells(
        emptyMap(),
        "A1" to "north", "B1" to "open", "C1" to 120, "D1" to 3,
        "A2" to "South", "B2" to "closed", "C2" to -40, "D2" to 5,
        "A3" to "NORTH", "B3" to "open", "C3" to 75.5, "D3" to 3,
        "A4" to "east", "B4" to "open", "C4" to 0, "D4" to 8,
        "A5" to "northern", "B5" to "closed", "C5" to 9, "D5" to "5",
        "B6" to "open", "C6" to 44,
        "A7" to "n?rth", "B7" to "hold", "C7" to "#N/A", "D7" to 5,
        "A8" to true, "C8" to 2,
    )
    private val report = Formulas(cells(mapOf("orders" to orders, "my orders" to orders), "A1" to "north", "A2" to ">0", "A3" to 5))

    @Test
    fun `counting the cells of another sheet that are what was asked for`() {
        fun count(asked: String, range: String = "A1:A8") = number("COUNTIF(Orders!$range,$asked)", report)
        // Whatever the case, the whole of the cell.
        assertEquals(2.0, count("\"north\""), 0.0)
        assertEquals(2.0, count("\"=NORTH\""), 0.0)
        // Any letters for *, any one for ?, and either as itself after ~.
        assertEquals(4.0, count("\"n*\""), 0.0)
        assertEquals(2.0, count("\"?orth\""), 0.0)
        assertEquals(1.0, count("\"n~?rth\""), 0.0)
        assertEquals(5.0, count("\"*th*\""), 0.0)
        // Not being it counts everything else, the empty cell and TRUE too.
        assertEquals(6.0, count("\"<>north\""), 0.0)
        assertEquals(1.0, count("\"\""), 0.0)
        assertEquals(7.0, count("\"<>\""), 0.0)
        assertEquals(1.0, count("TRUE"), 0.0)
        // Past the last cell with anything in it, they are all empty: counted, when empty is what was asked.
        assertEquals(93.0, count("\"\"", "A1:A100"), 0.0)
        assertEquals(98.0, count("\"<>north\"", "A1:A100"), 0.0)
        assertEquals(2.0, count("\"north\"", "A1:A100"), 0.0)
        // Numbers: more and less are for numbers only, and an error is no number.
        assertEquals(5.0, count("\">0\"", "C1:C8"), 0.0)
        assertEquals(1.0, count("\"<0\"", "C1:C8"), 0.0)
        assertEquals(2.0, count("\">=75.5\"", "C1:C8"), 0.0)
        assertEquals(1.0, count("0", "C1:C8"), 0.0)
        // Everything that is not 0, the error with it.
        assertEquals(7.0, count("\"<>0\"", "C1:C8"), 0.0)
        // Text that spells the number is taken for it.
        assertEquals(3.0, count("5", "D1:D8"), 0.0)
        assertEquals(3.0, count("\"5\"", "D1:D8"), 0.0)
        // Asked for by a cell of the formula's own sheet.
        assertEquals(2.0, count("A1"), 0.0)
        assertEquals(5.0, count("A2", "C1:C8"), 0.0)
        assertEquals(3.0, count("A3", "D1:D8"), 0.0)
        // An empty cell asks for 0.
        assertEquals(1.0, count("Z9", "C1:C8"), 0.0)
        // An error is counted by its name, and is not whatever else was asked not to be.
        assertEquals(1.0, count("\"#N/A\"", "C1:C8"), 0.0)
        assertEquals(7.0, count("\"<>#N/A\"", "C1:C8"), 0.0)
        assertEquals(1.0, count("Orders!C7", "C1:C8"), 0.0)
        // Over a block, not only down a column.
        assertEquals(4.0, count("\"open\"", "A1:D8"), 0.0)
    }

    @Test
    fun `counting, adding up and averaging by several conditions at once`() {
        assertEquals(2.0, number("COUNTIFS('Orders'!\$A\$1:\$A\$8,\"north\",'Orders'!\$C\$1:\$C\$8,\">0\")", report), 0.0)
        assertEquals(1.0, number("COUNTIFS(Orders!B1:B8,\"open\",Orders!D1:D8,\">=4\")", report), 0.0)
        assertEquals(195.5, number("SUMIFS('My orders'!C1:C8,'My orders'!A1:A8,\"north\",'My orders'!B1:B8,\"open\")", report), 0.0)
        assertEquals(31.0, number("ABS(SUMIFS(Orders!C1:C8,Orders!B1:B8,\"closed\"))", report), 0.0)
        assertEquals(195.5, number("SUMIF(Orders!A1:A8,\"north\",Orders!C1:C8)", report), 0.0)
        // Only where it begins counts, of what is added up.
        assertEquals(195.5, number("SUMIF(Orders!A1:A8,\"north\",Orders!C1)", report), 0.0)
        // With nothing else said, what is looked through is what is added up.
        assertEquals(248.5, number("SUMIF(Orders!C1:C6,\">0\")", report), 0.0)
        assertEquals(0.0, number("SUMIF(Orders!A1:A8,\"nobody\",Orders!C1:C8)", report), 0.0)
        assertEquals(97.75, number("AVERAGEIF(Orders!A1:A8,\"north\",Orders!C1:C8)", report), 0.0)
        assertEquals(79.83333333333333, number("AVERAGEIFS(Orders!C1:C8,Orders!B1:B8,\"open\",Orders!C1:C8,\">0\")", report), 1e-9)
        assertEquals("#DIV/0!", error("AVERAGEIF(Orders!A1:A8,\"nobody\",Orders!C1:C8)", report))
        // An error among what is added up is what the sum comes to; one that isn't, is passed by.
        assertEquals("#N/A", error("SUMIF(Orders!B1:B8,\"hold\",Orders!C1:C8)", report))
        assertEquals(239.5, number("SUMIF(Orders!B1:B8,\"open\",Orders!C1:C8)", report), 0.0)
        // Ranges that are not the same shape answer place to place for nothing.
        assertEquals("#VALUE!", error("COUNTIFS(Orders!A1:A8,\"north\",Orders!C1:C5,\">0\")", report))
        assertEquals("#VALUE!", error("SUMIFS(Orders!C1:C8,Orders!A1:A4,\"north\")", report))
    }

    @Test
    fun `cells of another sheet, by its name with or without quotes`() {
        assertEquals(121.0, number("Orders!C1+1", report), 0.0)
        assertEquals(121.0, number("'Orders'!\$C\$1+1", report), 0.0)
        assertEquals(121.0, number("ORDERS!C1+1", report), 0.0)
        assertEquals(75.5, number("'My orders'!C3", report), 0.0)
        assertEquals(210.5, number("SUM(Orders!C1:C8)-C9", Formulas(cells(mapOf("orders" to cells(emptyMap(), "C1" to 120, "C2" to 90.5))))), 0.0)
        assertEquals("north open", text("Orders!A1&\" \"&Orders!B1", report))
        // A sheet that isn't there, another workbook's, several at once, a whole column: not made up.
        listOf(
            "Nowhere!A1", "'No such'!A1", "[1]Orders!A1", "'[Book.xlsx]Orders'!A1", "Orders:Other!A1", "'Orders:Other'!A1",
            "Orders!A:A", "COUNTIF(Orders!A:A,\"north\")", "Orders!", "'Orders'", "'Orders!A1", "Orders!A1:Orders!A3",
            "COUNTIF(Orders!A1:A8)", "COUNTIF(1,1)", "COUNTIFS(Orders!A1:A8,1,Orders!B1:B8)", "SUMIFS(Orders!C1:C8,Orders!A1:A8)", "SUMIF(Orders!A1:A8)",
        ).forEach { assertNull(it, report.evaluate(it)) }
        // Nor what reaches into rows of it that were never read.
        val cut = Formulas(cells(mapOf("long" to cells(emptyMap(), "A1" to 1, "A2" to 2, readThrough = 2))))
        assertEquals(3.0, number("SUM(Long!A1:A2)", cut), 0.0)
        assertNull(cut.evaluate("SUM(Long!A1:A3)"))
        assertNull(cut.evaluate("COUNTIF(Long!A1:A3,1)"))
        assertNull(cut.evaluate("SUMIF(Long!A1:A2,1,Long!A2)"))
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
