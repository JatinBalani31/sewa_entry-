package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.sewaentry.tablet.export.XlsxWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * The xlsx writer is hand-rolled, so these check the parts Excel actually
 * requires: the right entries in the zip, well-formed XML, and correct
 * escaping. A malformed workbook fails at the moment someone opens it, long
 * after the export "succeeded".
 */
class XlsxWriterTest {

    private fun write(sheets: List<XlsxWriter.Sheet>): Map<String, String> {
        val bytes = ByteArrayOutputStream().also { XlsxWriter().write(it, sheets) }.toByteArray()
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun sampleSheet(name: String = "Register") = XlsxWriter.Sheet(
        name = name,
        headers = listOf("Sewadar", "Hours"),
        rows = listOf(
            listOf(XlsxWriter.Cell.Text("Harpreet Singh"), XlsxWriter.Cell.Number(6.5)),
            listOf(XlsxWriter.Cell.Text("Manjit Kaur"), XlsxWriter.Cell.Blank),
        ),
    )

    @Test
    fun `produces the parts a workbook needs`() {
        val entries = write(listOf(sampleSheet()))

        assertNotNull("[Content_Types].xml missing", entries["[Content_Types].xml"])
        assertNotNull("root rels missing", entries["_rels/.rels"])
        assertNotNull("workbook missing", entries["xl/workbook.xml"])
        assertNotNull("workbook rels missing", entries["xl/_rels/workbook.xml.rels"])
        assertNotNull("styles missing", entries["xl/styles.xml"])
        assertNotNull("sheet1 missing", entries["xl/worksheets/sheet1.xml"])
    }

    @Test
    fun `every part is well-formed xml`() {
        val entries = write(listOf(sampleSheet("A"), sampleSheet("B")))
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()

        entries.forEach { (name, content) ->
            if (!name.endsWith(".xml") && !name.endsWith(".rels")) return@forEach
            // Throws on malformed markup, which is the whole point.
            factory.newDocumentBuilder().parse(ByteArrayInputStream(content.toByteArray()))
        }
    }

    @Test
    fun `cell values land in the right rows and columns`() {
        val sheet = write(listOf(sampleSheet()))["xl/worksheets/sheet1.xml"]!!

        assertTrue("header should be row 1 col A", sheet.contains("""r="A1""""))
        assertTrue("first data row should be row 2", sheet.contains("""r="A2""""))
        assertTrue("name should be an inline string", sheet.contains("Harpreet Singh"))
        assertTrue("number should be a bare value", sheet.contains("<v>6.5</v>"))
    }

    @Test
    fun `text is xml-escaped so ampersands cannot corrupt the file`() {
        val sheet = write(
            listOf(
                XlsxWriter.Sheet(
                    name = "Escaping",
                    headers = listOf("Name"),
                    rows = listOf(listOf(XlsxWriter.Cell.Text("""Lost & Found <"test">"""))),
                ),
            ),
        )["xl/worksheets/sheet1.xml"]!!

        assertTrue(sheet.contains("&amp;"))
        assertTrue(sheet.contains("&lt;"))
        assertTrue(sheet.contains("&quot;"))
        // The raw characters must not survive, or Excel rejects the file.
        assertTrue(!sheet.contains("Found <\""))
    }

    @Test
    fun `sheet names are sanitised to what excel accepts`() {
        val entries = write(
            listOf(
                XlsxWriter.Sheet("Lost/Found: 2026", listOf("A"), emptyList()),
                XlsxWriter.Sheet("A".repeat(40), listOf("A"), emptyList()),
            ),
        )
        val workbook = entries["xl/workbook.xml"]!!

        assertTrue("illegal characters should be replaced", workbook.contains("Lost-Found- 2026"))
        assertTrue("no name may exceed 31 chars", Regex("""name="([^"]*)"""").findAll(workbook).all { it.groupValues[1].length <= 31 })
    }

    @Test
    fun `duplicate sheet names are made unique`() {
        val workbook = write(
            listOf(
                XlsxWriter.Sheet("Register", listOf("A"), emptyList()),
                XlsxWriter.Sheet("Register", listOf("A"), emptyList()),
            ),
        )["xl/workbook.xml"]!!

        val names = Regex("""name="([^"]*)"""").findAll(workbook).map { it.groupValues[1] }.toList()
        assertEquals(2, names.size)
        assertEquals("two sheets must not share a name", names.size, names.distinct().size)
    }

    @Test
    fun `column references continue past Z`() {
        val headers = (1..30).map { "Col$it" }
        val sheet = write(
            listOf(XlsxWriter.Sheet("Wide", headers, listOf(headers.map { XlsxWriter.Cell.Text(it) }))),
        )["xl/worksheets/sheet1.xml"]!!

        assertTrue("27th column should be AA", sheet.contains("""r="AA1""""))
        assertTrue("30th column should be AD", sheet.contains("""r="AD1""""))
    }

    @Test
    fun `the styles relationship id does not collide with sheet ids`() {
        val rels = write(listOf(sampleSheet("One"), sampleSheet("Two")))["xl/_rels/workbook.xml.rels"]!!
        val ids = Regex("""Id="([^"]*)"""").findAll(rels).map { it.groupValues[1] }.toList()

        assertEquals("every relationship id must be unique", ids.size, ids.distinct().size)
        assertTrue("styles must be related", rels.contains("styles.xml"))
    }
}
