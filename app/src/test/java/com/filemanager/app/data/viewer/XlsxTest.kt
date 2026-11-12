package com.filemanager.app.data.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

class XlsxTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val types = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** A .xlsx with the given sheets, by name and XML inside <worksheet>, and any of the other parts. */
    private fun xlsx(
        sheets: List<Pair<String, String>>,
        strings: List<String> = emptyList(),
        styles: String? = null,
        theme: String? = null,
        workbookExtra: String = "",
        hiddenSheet: Boolean = false,
        chartSheet: Boolean = false,
    ): File {
        val file = File(temp.root, "book${System.nanoTime()}.xlsx")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put(
                "_rels/.rels",
                """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="$types/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            )
            val sheetList = StringBuilder()
            val rels = StringBuilder()
            sheets.forEachIndexed { i, (name, _) ->
                sheetList.append("""<sheet name="$name" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
                rels.append("""<Relationship Id="rId${i + 1}" Type="$types/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
            }
            if (hiddenSheet) {
                sheetList.append("""<sheet name="Secret" sheetId="90" state="hidden" r:id="rId90"/>""")
                rels.append("""<Relationship Id="rId90" Type="$types/worksheet" Target="worksheets/secret.xml"/>""")
                put("xl/worksheets/secret.xml", """<worksheet xmlns="$main"><sheetData/></worksheet>""")
            }
            if (chartSheet) {
                sheetList.append("""<sheet name="Chart1" sheetId="91" r:id="rId91"/>""")
                rels.append("""<Relationship Id="rId91" Type="$types/chartsheet" Target="chartsheets/sheet1.xml"/>""")
                put("xl/chartsheets/sheet1.xml", """<chartsheet xmlns="$main"/>""")
            }
            rels.append("""<Relationship Id="rIdS" Type="$types/sharedStrings" Target="sharedStrings.xml"/>""")
            styles?.let { rels.append("""<Relationship Id="rIdY" Type="$types/styles" Target="/xl/styles.xml"/>""") }
            theme?.let { rels.append("""<Relationship Id="rIdT" Type="$types/theme" Target="theme/theme1.xml"/>""") }
            put(
                "xl/workbook.xml",
                """<?xml version="1.0"?><workbook xmlns="$main" xmlns:r="$rel">$workbookExtra<sheets>$sheetList</sheets></workbook>""",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>""",
            )
            put(
                "xl/sharedStrings.xml",
                """<?xml version="1.0"?><sst xmlns="$main">""" + strings.joinToString("") { "<si>$it</si>" } + "</sst>",
            )
            styles?.let { put("xl/styles.xml", """<?xml version="1.0"?><styleSheet xmlns="$main">$it</styleSheet>""") }
            theme?.let { put("xl/theme/theme1.xml", it) }
            sheets.forEachIndexed { i, (_, xml) ->
                put("xl/worksheets/sheet${i + 1}.xml", """<?xml version="1.0"?><worksheet xmlns="$main" xmlns:r="$rel">$xml</worksheet>""")
            }
        }
        return file
    }

    private fun Worksheet.cell(row: Int, column: String): SheetCell? {
        val shownRow = rows.firstOrNull { it.number == row } ?: return null
        val index = columns.indexOfFirst { it.name == column }
        return shownRow.cells.firstOrNull { it.column == index }
    }

    private val styles = """
        <numFmts count="1"><numFmt numFmtId="164" formatCode="0.0%"/></numFmts>
        <fonts count="3">
            <font><sz val="11"/><name val="Calibri"/></font>
            <font><b/><sz val="11"/><name val="Calibri"/></font>
            <font><i/><u/><sz val="22"/><name val="Calibri"/></font>
        </fonts>
        <fills count="4">
            <fill><patternFill patternType="none"/></fill>
            <fill><patternFill patternType="gray125"/></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/><bgColor indexed="64"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor theme="4" tint="0.39997558519241921"/></patternFill></fill>
        </fills>
        <cellStyleXfs count="1"><xf numFmtId="0" fontId="1" fillId="2"/></cellStyleXfs>
        <cellXfs count="7">
            <xf numFmtId="0" fontId="0" fillId="0"/>
            <xf numFmtId="0" fontId="1" fillId="0" applyFont="1"/>
            <xf numFmtId="14" fontId="0" fillId="0" applyNumberFormat="1"/>
            <xf numFmtId="164" fontId="0" fillId="0" applyNumberFormat="1"/>
            <xf numFmtId="0" fontId="0" fillId="2" applyFill="1"><alignment horizontal="center" vertical="top"/></xf>
            <xf numFmtId="0" fontId="0" fillId="3" applyFill="1"><alignment wrapText="1"/></xf>
            <xf numFmtId="0" fontId="2" fillId="0" applyFont="1"/>
        </cellXfs>
        <dxfs count="1"><dxf><font><b/></font><fill><patternFill><bgColor rgb="FFFF0000"/></patternFill></fill></dxf></dxfs>"""

    private val theme = """<?xml version="1.0"?><a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office">
        <a:themeElements><a:clrScheme name="Office">
            <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
            <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
            <a:dk2><a:srgbClr val="44546A"/></a:dk2>
            <a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
            <a:accent1><a:srgbClr val="4472C4"/></a:accent1>
            <a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
            <a:accent3><a:srgbClr val="A5A5A5"/></a:accent3>
            <a:accent4><a:srgbClr val="FFC000"/></a:accent4>
            <a:accent5><a:srgbClr val="5B9BD5"/></a:accent5>
            <a:accent6><a:srgbClr val="70AD47"/></a:accent6>
            <a:hlink><a:srgbClr val="0563C1"/></a:hlink>
            <a:folHlink><a:srgbClr val="954F72"/></a:folHlink>
        </a:clrScheme></a:themeElements></a:theme>"""

    @Test
    fun `lists the shown worksheets in order, without hidden ones or chart sheets`() {
        val book = Xlsx.read(xlsx(listOf("Data" to "<sheetData/>", "Summary" to "<sheetData/>"), hiddenSheet = true, chartSheet = true))
        assertEquals(listOf("Data", "Summary"), book.sheetNames)
    }

    @Test
    fun `cells show shared, inline and formula text, numbers, dates, booleans and errors`() {
        val sheet = """<sheetData>
            <row r="1"><c r="A1" t="s" s="1"><v>0</v></c><c r="B1" t="inlineStr"><is><t>Inline</t></is></c></row>
            <row r="2"><c r="A2"><v>3.14159</v></c><c r="B2" s="2"><v>45000</v></c><c r="C2" s="3"><v>0.256</v></c></row>
            <row r="3"><c r="A3" t="b"><v>1</v></c><c r="B3" t="e"><v>#DIV/0!</v></c><c r="C3" t="str"><f>A1&amp;"!"</f><v>Name!</v></c></row>
            <row r="4"><c r="A4" t="s"><v>1</v></c><c r="B4" t="s"><v>2</v></c><c r="C4"><f>1/0</f></c></row>
        </sheetData>"""
        val strings = listOf(
            "<t>Name</t>",
            "<r><t>Rich </t></r><r><rPr><b/></rPr><t>text</t></r>",
            "<t>漢字</t><rPh sb=\"0\" eb=\"2\"><t>かんじ</t></rPh>",
        )
        val ws = Xlsx.read(xlsx(listOf("S" to sheet), strings, styles)).sheet(0)
        assertEquals("Name", ws.cell(1, "A")?.text)
        assertTrue(ws.cell(1, "A")!!.look.bold)
        assertEquals("Inline", ws.cell(1, "B")?.text)
        assertEquals("3.14159", ws.cell(2, "A")?.text)
        assertEquals("15 Mar 23", ws.cell(2, "B")?.text)
        assertEquals("25.6%", ws.cell(2, "C")?.text)
        assertEquals("TRUE", ws.cell(3, "A")?.text)
        assertEquals("#DIV/0!", ws.cell(3, "B")?.text)
        assertEquals("Name!", ws.cell(3, "C")?.text)
        assertEquals("Rich text", ws.cell(4, "A")?.text)
        // Phonetic guides are not part of what the cell shows.
        assertEquals("漢字", ws.cell(4, "B")?.text)
        // A formula never worked out shows nothing.
        assertNull(ws.cell(4, "C"))

        // Numbers to the sheet's end, text to the side it reads from, the
        // rest centred; numbers never run on.
        assertEquals(CellAlign.LEFT, ws.cell(1, "A")?.align)
        assertEquals(CellAlign.END, ws.cell(2, "A")?.align)
        assertEquals(CellAlign.CENTER, ws.cell(3, "A")?.align)
        assertTrue(ws.cell(1, "A")!!.spills)
        assertFalse(ws.cell(2, "A")!!.spills)
        assertTrue(ws.cell(2, "B")!!.numeric)
        // General numbers keep their value, to be rounded to their column; formatted ones don't.
        assertEquals(3.14159, ws.cell(2, "A")!!.number!!, 0.0)
        assertNull(ws.cell(2, "C")!!.number)
    }

    @Test
    fun `looks come from cell styles, not cell style defaults or conditional formats`() {
        val sheet = """<sheetData>
            <row r="1"><c r="A1" t="inlineStr" s="4"><is><t>Yellow</t></is></c><c r="B1" s="5"/><c r="C1" t="inlineStr" s="6"><is><t>Big</t></is></c></row>
            <row r="2"><c r="A2" t="inlineStr"><is><t>Plain</t></is></c></row>
        </sheetData>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet), styles = styles, theme = theme)).sheet(0)
        val yellow = ws.cell(1, "A")!!
        assertEquals(0xFFFFFF00.toInt(), yellow.look.fill)
        assertEquals(CellAlign.CENTER, yellow.align)
        assertEquals(CellVertical.TOP, yellow.look.vertical)
        assertFalse(yellow.look.bold)
        // An empty cell with a fill is still drawn: accent 1, 40% lighter.
        val tinted = ws.cell(1, "B")!!
        assertEquals("", tinted.text)
        assertNear(0x8EA9DB, tinted.look.fill!!)
        assertTrue(tinted.look.wrap)
        val big = ws.cell(1, "C")!!.look
        assertEquals(2f, big.scale)
        assertTrue(big.italic && big.underline)
        val plain = ws.cell(2, "A")!!.look
        assertNull(plain.fill)
        assertFalse(plain.bold)
    }

    private fun assertNear(rgb: Int, argb: Int) {
        for (shift in listOf(16, 8, 0)) {
            val expected = rgb shr shift and 0xFF
            val actual = argb shr shift and 0xFF
            assertTrue("channel ${shift / 8}: $actual, not about $expected", abs(expected - actual) <= 2)
        }
        assertEquals(0xFF, argb ushr 24)
    }

    @Test
    fun `left and right are the cell's sides, and text in general alignment goes the way it reads`() {
        val styles = """
            <fonts count="2"><font><sz val="11"/></font><font><sz val="11"/><color rgb="FFFFFFFF"/></font></fonts>
            <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FF1F4E79"/></patternFill></fill></fills>
            <cellXfs count="4">
                <xf numFmtId="0" fontId="0" fillId="0"/>
                <xf numFmtId="0" fontId="0" fillId="0"><alignment horizontal="right"/></xf>
                <xf numFmtId="0" fontId="0" fillId="0"><alignment horizontal="centerContinuous"/></xf>
                <xf numFmtId="0" fontId="1" fillId="2"/>
            </cellXfs>"""
        val sheet = """<sheetViews><sheetView rightToLeft="1" workbookViewId="0"/></sheetViews><sheetData>
            <row r="1"><c r="A1" t="inlineStr" s="1"><is><t>Total</t></is></c><c r="B1" t="inlineStr"><is><t>جمع کل</t></is></c><c r="C1" t="inlineStr"><is><t>Name</t></is></c></row>
            <row r="2"><c r="A2" t="inlineStr" s="2"><is><t>Title across</t></is></c><c r="B2" s="2"/><c r="C2" s="2"/></row>
            <row r="3"><c r="A3" t="inlineStr" s="3"><is><t>Header</t></is></c></row>
        </sheetData>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet), styles = styles)).sheet(0)
        assertEquals("explicit right stays right, even in a right-to-left sheet", CellAlign.RIGHT, ws.cell(1, "A")?.align)
        assertEquals("Persian reads from the right", CellAlign.RIGHT, ws.cell(1, "B")?.align)
        assertEquals("English from the left, whichever way the sheet reads", CellAlign.LEFT, ws.cell(1, "C")?.align)
        // Center Across Selection: the empty cells it is centred across are kept, to say how far.
        val title = ws.cell(2, "A")!!
        assertEquals(CellAlign.CENTER, title.align)
        assertTrue(title.look.centerAcross)
        assertTrue(ws.cell(2, "B")!!.look.centerAcross && ws.cell(2, "C")!!.text.isEmpty())
        // The text's own colour, for a fill.
        assertEquals(0xFFFFFFFF.toInt(), ws.cell(3, "A")!!.look.color)
        assertEquals(0xFF1F4E79.toInt(), ws.cell(3, "A")!!.look.fill)
    }

    @Test
    fun `hidden rows and columns are left out, and widths and heights are kept`() {
        val sheet = """
            <sheetFormatPr defaultRowHeight="15"/>
            <cols><col min="2" max="2" width="20" customWidth="1"/><col min="3" max="3" width="9" hidden="1"/></cols>
            <sheetData>
                <row r="1" ht="30" customHeight="1"><c r="A1"><v>1</v></c><c r="C1"><v>3</v></c><c r="D1"><v>4</v></c></row>
                <row r="2" hidden="1"><c r="A2"><v>2</v></c></row>
                <row r="4"><c r="B4"><v>5</v></c></row>
            </sheetData>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet))).sheet(0)
        assertEquals(listOf("A", "B", "D"), ws.columns.map { it.name })
        assertEquals(140f, ws.columns[1].width)
        assertEquals(64f, ws.columns[0].width)
        // Empty rows in between are shown, as Excel shows them.
        assertEquals(listOf(1, 3, 4), ws.rows.map { it.number })
        assertEquals(40f, ws.rows[0].height)
        assertEquals(20f, ws.rows[1].height)
        assertEquals("4", ws.cell(1, "D")?.text)
    }

    @Test
    fun `a row fits its text unless somebody chose its height`() {
        val sheet = """
            <sheetFormatPr defaultRowHeight="15"/>
            <sheetData>
                <row r="1"><c r="A1"><v>1</v></c></row>
                <row r="2" ht="30"><c r="A2"><v>2</v></c></row>
                <row r="3" ht="30" customHeight="1"><c r="A3"><v>3</v></c></row>
                <row r="4" customHeight="1"><c r="A4"><v>4</v></c></row>
            </sheetData>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet))).sheet(0)
        // A height Excel worked out and stored is still its own to work out.
        assertEquals(listOf(true, true, false, true), ws.rows.map { it.fits })
        assertEquals(listOf(20f, 40f, 40f, 20f), ws.rows.map { it.height })
        // A height chosen for the whole sheet is chosen for each row that gives none.
        val chosen = sheet.replace("""defaultRowHeight="15"""", """defaultRowHeight="30" customHeight="1"""")
        val set = Xlsx.read(xlsx(listOf("S" to chosen))).sheet(0)
        assertEquals(listOf(false, true, false, false), set.rows.map { it.fits })
    }

    @Test
    fun `a row numbered below one follows on from the one before`() {
        val sheet = """<sheetData><row r="2"><c><v>1</v></c></row><row r="-7" ht="9"><c><v>2</v></c></row><row r="0"><c><v>3</v></c></row></sheetData>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet))).sheet(0)
        assertEquals(listOf(1, 2, 3, 4), ws.rows.map { it.number })
        assertEquals(listOf(null, "1", "2", "3"), (1..4).map { ws.cell(it, "A")?.text })
        assertEquals(12f, ws.rows[2].height)
    }

    @Test
    fun `cells without references follow on from the one before`() {
        val sheet = """<sheetData><row><c><v>1</v></c><c><v>2</v></c><c r="E1"><v>5</v></c><c><v>6</v></c></row><row><c><v>7</v></c></row></sheetData>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet))).sheet(0)
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), ws.columns.map { it.name })
        assertEquals("2", ws.cell(1, "B")?.text)
        assertEquals("6", ws.cell(1, "F")?.text)
        assertEquals("7", ws.cell(2, "A")?.text)
    }

    @Test
    fun `a merged cell spans its columns and carries on down its rows`() {
        val sheet = """<sheetData>
            <row r="1"><c r="A1" t="inlineStr"><is><t>Merged</t></is></c><c r="B1" t="inlineStr"><is><t>Covered</t></is></c><c r="C1"><v>3</v></c></row>
            <row r="2"><c r="C2"><v>4</v></c></row>
        </sheetData><mergeCells count="1"><mergeCell ref="A1:B2"/></mergeCells>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet))).sheet(0)
        val top = ws.rows[0].cells
        assertEquals(listOf("Merged", "3"), top.map { it.text })
        assertEquals(2, top[0].span)
        assertTrue(top[0].continues)
        assertFalse(top[0].continued)
        assertFalse(top[0].spills)
        val below = ws.rows[1].cells.first()
        assertEquals(0, below.column)
        assertEquals(2, below.span)
        assertTrue(below.continued)
        assertFalse(below.continues)
    }

    @Test
    fun `strings keep Excel's escapes as the characters they stand for`() {
        assertEquals("a\r\nb", unescape("a_x000D_\nb"))
        assertEquals("plain", unescape("plain"))
        val ws = Xlsx.read(xlsx(listOf("S" to """<sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row></sheetData>"""), listOf("<t>Tab_x0009_here</t>"))).sheet(0)
        assertEquals("Tab\there", ws.cell(1, "A")?.text)
    }

    @Test
    fun `a 1904 workbook counts its dates from 1904`() {
        val sheet = """<sheetData><row r="1"><c r="A1" s="2"><v>0</v></c></row></sheetData>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet), styles = styles, workbookExtra = """<workbookPr date1904="1"/>""")).sheet(0)
        assertEquals("1 Jan 04", ws.cell(1, "A")?.text)
    }

    @Test
    fun `right to left sheets, and sheets with only drawings, say so`() {
        val rtl = Xlsx.read(xlsx(listOf("S" to """<sheetViews><sheetView rightToLeft="1" workbookViewId="0"/></sheetViews><sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData>"""))).sheet(0)
        assertTrue(rtl.rightToLeft)
        val chart = Xlsx.read(xlsx(listOf("S" to """<sheetData/><drawing r:id="rId1"/>"""))).sheet(0)
        assertTrue(chart.hasDrawings)
        assertTrue(chart.rows.isEmpty())
    }

    @Test
    fun `a sheet past the most rows is cut short and says so`() {
        val sheet = """<sheetData><row r="1"><c r="A1"><v>1</v></c></row><row r="${Xlsx.MAX_ROWS + 1}"><c r="A${Xlsx.MAX_ROWS + 1}"><v>2</v></c></row></sheetData>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet))).sheet(0)
        assertTrue(ws.truncated)
        assertEquals(listOf(1), ws.rows.map { it.number })
    }

    @Test
    fun `columns past the most are left out and the sheet says so`() {
        val far = columnName(Xlsx.MAX_COLUMNS + 1)
        val sheet = """<sheetData><row r="1"><c r="A1"><v>1</v></c><c r="${far}1"><v>2</v></c></row></sheetData>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet))).sheet(0)
        assertTrue(ws.truncated)
        assertEquals(listOf("A"), ws.columns.map { it.name })
    }

    // ---- Files made to break a reader ------------------------------------

    @Test
    fun `a cell holds no more text than Excel lets one`() {
        val long = "a".repeat(Xlsx.MAX_CELL_TEXT + 5_000)
        val sheet = """<sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>$long</t></is></c>""" +
            """<c r="C1" t="str"><v>$long</v></c></row></sheetData>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet), strings = listOf("<t>$long</t>"))).sheet(0)
        assertEquals(listOf(Xlsx.MAX_CELL_TEXT, Xlsx.MAX_CELL_TEXT, Xlsx.MAX_CELL_TEXT), listOf("A", "B", "C").map { ws.cell(1, it)?.text?.length })
    }

    @Test
    fun `one long text in every cell is read only as far as a sheet's text goes`() {
        val cells = (1..900).joinToString("") { """<row r="$it"><c r="A$it" t="s"><v>0</v></c></row>""" }
        val ws = Xlsx.read(xlsx(listOf("S" to "<sheetData>$cells</sheetData>"), strings = listOf("<t>${"a".repeat(30_000)}</t>"))).sheet(0)
        assertTrue(ws.truncated)
        assertTrue(ws.rows.size < 900)
        assertTrue(ws.rows.sumOf { row -> row.cells.sumOf { it.text.length } } <= Xlsx.MAX_SHEET_TEXT)
    }

    @Test
    fun `merged cells said again and again are kept once`() {
        val merges = """<mergeCell ref="A1:B2"/>""".repeat(2_000)
        val sheet = """<sheetData><row r="1"><c r="A1"><v>1</v></c></row><row r="3"><c r="A3"><v>3</v></c></row></sheetData><mergeCells>$merges</mergeCells>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet))).sheet(0)
        assertEquals(listOf(1, 1, 1), ws.rows.map { it.cells.size })
        assertEquals(2, ws.rows[0].cells.single().span)
    }

    @Test
    fun `merged cells over one another stop at as many as a row has columns`() {
        // Each a different one, all crossing row 1.
        val merges = (2..2_000).joinToString("") { """<mergeCell ref="A1:B$it"/>""" }
        val sheet = """<sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData><mergeCells>$merges</mergeCells>"""
        val ws = Xlsx.read(xlsx(listOf("S" to sheet))).sheet(0)
        assertTrue(ws.rows.all { it.cells.size <= Xlsx.MAX_COLUMNS })
    }

    @Test
    fun `a row said over and over ends the reading`() {
        val rows = """<row r="3"><c r="A3"><v>1</v></c></row>""".repeat(Xlsx.MAX_ROWS + 5)
        val ws = Xlsx.read(xlsx(listOf("S" to "<sheetData>$rows</sheetData>"))).sheet(0)
        assertTrue(ws.truncated)
        assertEquals(listOf(1, 2, 3), ws.rows.map { it.number })
    }

    @Test
    fun `sheets past the most there are tabs for are left off`() {
        val sheets = (1..Xlsx.MAX_SHEETS + 5).map { "S$it" to "<sheetData/>" }
        assertEquals(Xlsx.MAX_SHEETS, Xlsx.read(xlsx(sheets)).sheetNames.size)
    }

    @Test
    fun `cell references convert both ways`() {
        assertEquals(1, columnOf("A1"))
        assertEquals(28, columnOf("AB12"))
        assertEquals(16384, columnOf("XFD1048576"))
        assertEquals(3, columnOf("\$C\$7"))
        assertNull(columnOf("12"))
        assertEquals(12, rowOf("AB12"))
        assertEquals(7, rowOf("\$C\$7"))
        assertEquals("A", columnName(1))
        assertEquals("Z", columnName(26))
        assertEquals("AA", columnName(27))
        assertEquals("XFD", columnName(16384))
    }

    @Test(expected = java.io.IOException::class)
    fun `a zip that isn't a workbook fails to read`() {
        val file = File(temp.root, "not.xlsx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("hi".toByteArray())
            zip.closeEntry()
        }
        Xlsx.read(file)
    }
}
