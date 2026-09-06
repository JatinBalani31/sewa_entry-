package org.sewaentry.tablet.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A minimal .xlsx writer.
 *
 * An xlsx file is a ZIP of XML parts, so writing one directly costs a few
 * hundred lines and no dependency at all. Apache POI is the usual answer on
 * the JVM but is a poor fit on Android — it is large, drags in java.awt
 * classes the platform does not have, and pushes the app against the dex
 * method limit. This produces a genuine spreadsheet that Excel, LibreOffice
 * and Google Sheets all open.
 *
 * Supported deliberately narrowly: multiple sheets, string and number cells,
 * a bold header row, and frozen panes with an auto-filter. That is everything
 * the attendance reports need.
 */
class XlsxWriter {

    data class Sheet(
        val name: String,
        val headers: List<String>,
        val rows: List<List<Cell>>,
        val columnWidths: List<Int> = emptyList(),
    )

    sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Number(val value: Double) : Cell
        data object Blank : Cell
    }

    fun write(out: OutputStream, sheets: List<Sheet>) {
        require(sheets.isNotEmpty()) { "A workbook needs at least one sheet." }
        val used = mutableSetOf<String>()
        val safeSheets = sheets.mapIndexed { index, sheet -> sheet.copy(name = safeName(sheet.name, index, used)) }

        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(safeSheets.size))
            zip.put("_rels/.rels", rootRels())
            zip.put("xl/workbook.xml", workbook(safeSheets))
            zip.put("xl/_rels/workbook.xml.rels", workbookRels(safeSheets.size))
            zip.put("xl/styles.xml", styles())
            safeSheets.forEachIndexed { index, sheet ->
                zip.put("xl/worksheets/sheet${index + 1}.xml", worksheet(sheet))
            }
        }
    }

    private fun ZipOutputStream.put(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /**
     * Excel forbids : \ / ? * [ ] in a sheet name, caps it at 31 characters,
     * and refuses a workbook where two sheets share a name — so a collision is
     * resolved here rather than producing a file that will not open.
     */
    private fun safeName(name: String, index: Int, used: MutableSet<String>): String {
        val base = name.replace(Regex("[:\\\\/?*\\[\\]]"), "-").take(31).trim()
            .ifBlank { "Sheet${index + 1}" }

        if (used.add(base.lowercase())) return base

        var suffix = 2
        while (true) {
            val tag = " $suffix"
            // Trim the base rather than the suffix, so the number always survives.
            val candidate = base.take(31 - tag.length) + tag
            if (used.add(candidate.lowercase())) return candidate
            suffix++
        }
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..sheetCount) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun workbook(sheets: List<Sheet>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { index, sheet ->
            append("""<sheet name="${escape(sheet.name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        // The styles part must not collide with the sheet relationship ids.
        append("""<Relationship Id="rId${sheetCount + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    /**
     * Two cell formats: 0 is the default, 1 is bold on a light fill for the
     * header row. Excel requires the fonts/fills/borders/cellXfs skeleton even
     * when it is nearly empty.
     */
    private fun styles(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2">""" +
            """<font><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>""" +
            """</fonts>""" +
            """<fills count="3">""" +
            """<fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF1F3A5F"/><bgColor indexed="64"/></patternFill></fill>""" +
            """</fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="2">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>""" +
            """</cellXfs>""" +
            """</styleSheet>"""

    private fun worksheet(sheet: Sheet): String = buildString {
        val lastColumn = columnName(maxOf(sheet.headers.size, 1))
        val lastRow = sheet.rows.size + 1

        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")

        if (sheet.columnWidths.isNotEmpty()) {
            append("<cols>")
            sheet.columnWidths.forEachIndexed { index, width ->
                append("""<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>""")
            }
            append("</cols>")
        }

        append("<sheetData>")

        append("""<row r="1">""")
        sheet.headers.forEachIndexed { index, header ->
            append(cellXml("${columnName(index + 1)}1", Cell.Text(header), styleId = 1))
        }
        append("</row>")

        sheet.rows.forEachIndexed { rowIndex, row ->
            val rowNumber = rowIndex + 2
            append("""<row r="$rowNumber">""")
            row.forEachIndexed { colIndex, cell ->
                append(cellXml("${columnName(colIndex + 1)}$rowNumber", cell, styleId = 0))
            }
            append("</row>")
        }

        append("</sheetData>")
        // Freeze the header and let the reader filter — this is what makes an
        // export usable rather than just correct.
        append("""<autoFilter ref="A1:$lastColumn$lastRow"/>""")
        append("</worksheet>")
    }

    private fun cellXml(ref: String, cell: Cell, styleId: Int): String {
        val style = if (styleId != 0) """ s="$styleId"""" else ""
        return when (cell) {
            is Cell.Blank -> """<c r="$ref"$style/>"""
            is Cell.Number -> """<c r="$ref"$style><v>${cell.value}</v></c>"""
            // Inline strings avoid maintaining a shared-strings table; the
            // files are small enough that the size trade-off does not matter.
            is Cell.Text -> """<c r="$ref"$style t="inlineStr"><is><t xml:space="preserve">${escape(cell.value)}</t></is></c>"""
        }
    }

    /** 1 -> A, 26 -> Z, 27 -> AA. */
    private fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n > 0) {
            val remainder = (n - 1) % 26
            sb.insert(0, ('A' + remainder))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // XML 1.0 forbids most control characters outright; drop them
                // rather than produce a file Excel refuses to open.
                else -> if (ch >= ' ' || ch == '\t' || ch == '\n') append(ch)
            }
        }
    }
}
