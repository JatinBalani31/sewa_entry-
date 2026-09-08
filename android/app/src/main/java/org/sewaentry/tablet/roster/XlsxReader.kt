package org.sewaentry.tablet.roster

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * A minimal .xlsx reader — the counterpart to [org.sewaentry.tablet.export.XlsxWriter].
 *
 * An .xlsx is a ZIP of XML parts, so reading one costs a few hundred lines and
 * no dependency. Apache POI is the usual answer on the JVM but is a poor fit on
 * Android: large, drags in `java.awt` classes the platform lacks, and pushes
 * against the dex method limit.
 *
 * This reads only what the roster importer needs — cell text and whether the
 * cell is bold, because **bold is data here**: it is how the sangat marks the
 * HOD in the contact sheet. Formatting, formulas, merges and dates are all
 * ignored.
 *
 * Parsing is SAX rather than a pull parser so the same code runs unchanged on
 * the JVM in unit tests and on the device.
 */
object XlsxReader {

    /** Guards against a hostile or corrupt zip inflating to fill the device. */
    private const val MAX_PART_BYTES = 64L * 1024 * 1024

    fun read(input: InputStream): XlsxWorkbook {
        val parts = HashMap<String, ByteArray>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                // Only the parts we actually parse, so a workbook full of
                // images or charts costs nothing to open.
                val wanted = name == "xl/workbook.xml" ||
                    name == "xl/_rels/workbook.xml.rels" ||
                    name == "xl/sharedStrings.xml" ||
                    name == "xl/styles.xml" ||
                    name.startsWith("xl/worksheets/") && name.endsWith(".xml")
                if (!wanted) continue
                parts[name] = zip.readBoundedBytes()
            }
        }

        if (!parts.containsKey("xl/workbook.xml")) {
            throw IllegalArgumentException("This is not an Excel .xlsx file.")
        }

        val shared = parts["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val boldStyles = parts["xl/styles.xml"]?.let { parseBoldStyles(it) } ?: emptySet()
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let { parseRels(it) } ?: emptyMap()
        val order = parseSheetOrder(parts.getValue("xl/workbook.xml"))

        val sheets = order.mapNotNull { (name, relId) ->
            val target = rels[relId] ?: return@mapNotNull null
            val path = "xl/" + target.removePrefix("/xl/").removePrefix("xl/")
            val bytes = parts[path] ?: return@mapNotNull null
            XlsxSheet(name, parseSheet(bytes, shared, boldStyles))
        }

        return XlsxWorkbook(sheets)
    }

    private fun java.util.zip.ZipInputStream.readBoundedBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_PART_BYTES) throw IllegalArgumentException("This spreadsheet is too large to read.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // --- Parts ---------------------------------------------------------------

    /** Sheet name → relationship id, in workbook order. */
    private fun parseSheetOrder(bytes: ByteArray): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                if (local(qName) != "sheet") return
                val name = attrs.get("name") ?: return
                val id = attrs.get("id") ?: attrs.get("r:id") ?: return
                result += name to id
            }
        })
        return result
    }

    private fun parseRels(bytes: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                if (local(qName) != "Relationship") return
                val id = attrs.get("Id") ?: return
                val target = attrs.get("Target") ?: return
                result[id] = target
            }
        })
        return result
    }

    /**
     * The shared string table. Rich text splits one string across several `<r>`
     * runs, so the runs are concatenated — otherwise a name that happens to be
     * styled mid-word would come back truncated.
     */
    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        val text = StringBuilder()
        var inText = false

        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                when (local(qName)) {
                    "si" -> current.setLength(0)
                    "t" -> { inText = true; text.setLength(0) }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) text.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (local(qName)) {
                    "t" -> { current.append(text); inText = false }
                    "si" -> strings += current.toString()
                }
            }
        })
        return strings
    }

    /**
     * Style indexes whose font is bold.
     *
     * A cell carries `s="12"`, an index into `<cellXfs>`; that entry points at a
     * `<fonts>` entry, which may carry `<b/>`. Resolving the chain here means
     * the sheet parser only has to ask "is this style index bold".
     */
    private fun parseBoldStyles(bytes: ByteArray): Set<Int> {
        val fontBold = mutableListOf<Boolean>()
        val xfFont = mutableListOf<Int>()

        var inFonts = false
        var inCellXfs = false
        var currentFontBold = false

        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                when (local(qName)) {
                    "fonts" -> inFonts = true
                    "cellXfs" -> inCellXfs = true
                    "font" -> if (inFonts) currentFontBold = false
                    "b" -> if (inFonts) {
                        // `<b val="0"/>` is an explicit "not bold".
                        currentFontBold = attrs.get("val").isTruthy()
                    }
                    "xf" -> if (inCellXfs) xfFont += attrs.get("fontId")?.toIntOrNull() ?: 0
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (local(qName)) {
                    "font" -> if (inFonts) fontBold += currentFontBold
                    "fonts" -> inFonts = false
                    "cellXfs" -> inCellXfs = false
                }
            }
        })

        return xfFont.indices
            .filter { fontBold.getOrElse(xfFont[it]) { false } }
            .toSet()
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>, boldStyles: Set<Int>): List<XlsxCell> {
        val cells = mutableListOf<XlsxCell>()

        var row = 0
        var col = 0
        var type: String? = null
        var style = -1
        var inValue = false
        var inInlineText = false
        val value = StringBuilder()
        val inline = StringBuilder()

        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                when (local(qName)) {
                    "row" -> row = attrs.get("r")?.toIntOrNull() ?: (row + 1)
                    "c" -> {
                        val ref = attrs.get("r")
                        // A cell without a ref follows the previous one; Excel
                        // itself always writes refs, but other writers omit them.
                        col = ref?.let { columnOf(it) } ?: (col + 1)
                        ref?.let { rowOf(it) }?.let { row = it }
                        type = attrs.get("t")
                        style = attrs.get("s")?.toIntOrNull() ?: -1
                        value.setLength(0)
                        inline.setLength(0)
                    }
                    "v" -> { inValue = true; value.setLength(0) }
                    "t" -> inInlineText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value.appendRange(ch, start, start + length)
                else if (inInlineText) inline.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (local(qName)) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val text = when (type) {
                            "s" -> value.toString().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "inlineStr" -> inline.toString()
                            else -> value.toString()
                        }
                        if (text.isNotEmpty()) {
                            cells += XlsxCell(row, col, text, bold = style in boldStyles)
                        }
                    }
                }
            }
        })

        return cells
    }

    // --- Helpers -------------------------------------------------------------

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        // The parts come from a user-chosen file, so external entities stay off.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.isNamespaceAware = false
        factory.newSAXParser().parse(bytes.inputStream(), handler)
    }

    /** SAX without namespace awareness still yields prefixed names in some parsers. */
    private fun local(qName: String): String = qName.substringAfterLast(':')

    private fun Attributes.get(name: String): String? {
        getValue(name)?.let { return it }
        // Namespace-prefixed attributes (r:id, x:name) when the prefix varies.
        for (i in 0 until length) {
            if (local(getQName(i)) == local(name)) return getValue(i)
        }
        return null
    }

    private fun String?.isTruthy(): Boolean =
        this == null || this == "1" || this.equals("true", ignoreCase = true)

    /** "AB12" → 28. One-based, so column A is 1. */
    fun columnOf(ref: String): Int {
        var result = 0
        for (ch in ref) {
            val upper = ch.uppercaseChar()
            if (upper !in 'A'..'Z') break
            result = result * 26 + (upper - 'A' + 1)
        }
        return result
    }

    /** "AB12" → 12. */
    fun rowOf(ref: String): Int? = ref.dropWhile { it.isLetter() }.toIntOrNull()
}

data class XlsxCell(
    /** One-based, matching the row numbers Excel shows. */
    val row: Int,
    /** One-based, so column A is 1. */
    val col: Int,
    val text: String,
    val bold: Boolean,
)

class XlsxSheet(val name: String, val cells: List<XlsxCell>) {

    private val index: Map<Long, XlsxCell> = cells.associateBy { key(it.row, it.col) }

    val maxRow: Int = cells.maxOfOrNull { it.row } ?: 0

    fun cell(row: Int, col: Int): XlsxCell? = index[key(row, col)]

    fun text(row: Int, col: Int): String = cell(row, col)?.text?.trim().orEmpty()

    fun isBold(row: Int, col: Int): Boolean = cell(row, col)?.bold ?: false

    private fun key(row: Int, col: Int): Long = row.toLong() * 1024 + col

    override fun toString(): String = "XlsxSheet($name, ${cells.size} cells)"
}

class XlsxWorkbook(val sheets: List<XlsxSheet>) {
    override fun toString(): String = "XlsxWorkbook(${sheets.size} sheets)"
}
