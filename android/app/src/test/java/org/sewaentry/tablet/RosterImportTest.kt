package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.sewaentry.tablet.roster.ImportPlan
import org.sewaentry.tablet.roster.ParsedSheet
import org.sewaentry.tablet.roster.RosterImport
import org.sewaentry.tablet.roster.XlsxReader
import java.io.File

/**
 * The importer is checked against a fixture that mirrors the sangat's real
 * contact workbook — same layout, same awkward corners, invented people. Real
 * contact details are deliberately not committed to the repository.
 *
 * [the real workbook parses when one is supplied] re-runs the structural
 * expectations against the genuine file when a path is given, so the fixture
 * can be trusted to stand in for it.
 */
class RosterImportTest {

    private fun plan(): ImportPlan {
        val stream = javaClass.classLoader!!.getResourceAsStream("roster-sample.xlsx")
        assertNotNull("fixture roster-sample.xlsx is missing", stream)
        return stream.use { RosterImport.parse(XlsxReader.read(it)) }
    }

    private fun ImportPlan.sheet(name: String): ParsedSheet =
        sheets.firstOrNull { it.sheet == name }
            ?: error("no sheet '$name' in ${sheets.map { it.sheet }}")

    /** What the plan would write with nothing changed. */
    private fun ImportPlan.defaults() = departments(defaultSelection)

    // --- Reader --------------------------------------------------------------

    @Test
    fun `column references decode`() {
        assertEquals(1, XlsxReader.columnOf("A1"))
        assertEquals(2, XlsxReader.columnOf("B6"))
        assertEquals(26, XlsxReader.columnOf("Z9"))
        assertEquals(27, XlsxReader.columnOf("AA1"))
        assertEquals(28, XlsxReader.columnOf("AB12"))
        assertEquals(12, XlsxReader.rowOf("AB12"))
    }

    @Test
    fun `a file that is not a spreadsheet is rejected clearly`() {
        val error = runCatching {
            XlsxReader.read("this is not a zip".byteInputStream())
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException || error is java.util.zip.ZipException)
    }

    // --- Department discovery ------------------------------------------------

    @Test
    fun `departments come from the Department line, not the sheet name`() {
        // The sheet is named "Transport " with a trailing space; the department
        // line inside it is the authoritative name.
        assertEquals("Transport (Traffic 1)", plan().sheet("Transport").department)
        assertTrue(plan().sheets.none { it.department != it.department.trim() })
    }

    @Test
    fun `an empty template sheet is skipped with a reason`() {
        val plan = plan()
        assertTrue(plan.sheets.none { it.sheet == "Sheet1" })
        assertTrue(plan.skipped.any { it.sheet == "Sheet1" })
    }

    // --- The clashing-sheet rule --------------------------------------------

    @Test
    fun `a second sheet claiming the same department is flagged, not merged in silently`() {
        val plan = plan()
        val copy = plan.sheet("Account (2)")
        assertEquals("Account", copy.department)
        assertEquals("Account", copy.conflictsWith)
        assertFalse("a clashing sheet must not be imported by default", copy.includedByDefault)
        assertTrue(plan.conflicts.map { it.sheet }.contains("Account (2)"))
    }

    @Test
    fun `by default the clashing sheet contributes nobody`() {
        val plan = plan()
        val account = plan.defaults().first { it.name == "Account" }
        // The genuine Account sheet only. Folding in the scratch copy would
        // silently swell the team with people who belong to other departments.
        assertEquals(5, account.sewadars.size)
        assertFalse(account.sewadars.map { it.name }.contains("Rashmi Chawla"))
    }

    @Test
    fun `including the clashing sheet merges it, because then it is a choice`() {
        val plan = plan()
        val both = plan.departments(plan.defaultSelection + "Account (2)")
        val account = both.first { it.name == "Account" }
        assertTrue(account.sewadars.map { it.name }.contains("Rashmi Chawla"))
        // Suresh Balani is on both sheets and must not appear twice.
        assertEquals(1, account.sewadars.count { it.name == "Suresh Balani" })
        assertEquals(1, both.count { it.name == "Account" })
    }

    @Test
    fun `merging keeps the contact details from whichever sheet has them`() {
        val plan = plan()
        // Suresh Balani has phones on the real sheet and none on the copy.
        val suresh = plan.departments(plan.defaultSelection + "Account (2)")
            .first { it.name == "Account" }
            .sewadars.first { it.name == "Suresh Balani" }
        assertEquals("8668566515", suresh.phone)
        assertEquals("9423428808", suresh.phone2)
    }

    // --- People --------------------------------------------------------------

    @Test
    fun `a side-by-side second table is read, not dropped`() {
        val copy = plan().sheet("Account (2)")
        val names = copy.sewadars.map { it.name }
        // These live in columns E/F. A parser that only looked at column B
        // would silently lose them.
        assertTrue("missing the side-by-side table: $names", names.contains("Rashmi Chawla"))
        assertTrue(names.contains("Arti Pali"))
        assertEquals(2, copy.tables)
    }

    @Test
    fun `a block that resumes after a blank run is still read`() {
        val names = plan().sheet("Account (2)").sewadars.map { it.name }
        assertTrue("stopped at the first gap: $names", names.contains("Anand Harwani"))
        assertTrue(names.contains("Ajay Uttamchandani"))
    }

    @Test
    fun `trailing spaces in a name are trimmed`() {
        val names = plan().sheet("Account").sewadars.map { it.name }
        assertTrue(names.contains("Manish Adwani"))
        assertTrue(names.none { it != it.trim() })
    }

    // --- HOD -----------------------------------------------------------------

    @Test
    fun `bold names are read as the head of department`() {
        val hods = plan().sheet("Account").hods.map { it.name }.toSet()
        assertEquals(setOf("Suresh Balani", "Vicky Puruswani"), hods)
    }

    @Test
    fun `a plain name is not marked as head of department`() {
        assertFalse(plan().sheet("Account").sewadars.first { it.name == "Ghanshyam Kukreja" }.isHod)
    }

    @Test
    fun `a department with nobody marked bold is reported, not guessed at`() {
        val plan = plan()
        assertTrue(plan.sheet("Garden").hods.isEmpty())
        // Surfaced so the admin can fix the sheet, rather than the app inventing
        // an HOD by taking whoever happens to be listed first.
        assertTrue(plan.withoutHod(plan.defaultSelection).contains("Garden"))
    }

    // --- Contact numbers -----------------------------------------------------

    @Test
    fun `phone numbers keep their digits and lose their spacing`() {
        assertEquals("9000011111", plan().sheet("Garden").sewadars.first().phone)
    }

    @Test
    fun `text that is not a number is dropped rather than stored`() {
        val anil = plan().sheet("Transport").sewadars.first { it.name == "Anil Balani" }
        assertNull(anil.phone)
    }

    @Test
    fun `a sewadar with no contact number still imports`() {
        assertTrue(plan().sheet("Account (2)").sewadars.map { it.name }.contains("Anand Harwani"))
    }

    // --- Plan summary --------------------------------------------------------

    @Test
    fun `the plan counts what it would actually write`() {
        val plan = plan()
        val selection = plan.defaultSelection
        assertFalse(plan.isEmpty)
        assertEquals(plan.departments(selection).sumOf { it.sewadars.size }, plan.sewadarCount(selection))
        assertEquals(plan.departments(selection).sumOf { it.hods.size }, plan.hodCount(selection))
        assertTrue(plan.sewadarCount(selection) > 0)
    }

    @Test
    fun `selecting nothing writes nothing`() {
        assertEquals(0, plan().sewadarCount(emptySet()))
        assertTrue(plan().departments(emptySet()).isEmpty())
    }

    @Test
    fun `every imported name is non-blank`() {
        assertTrue(plan().sheets.all { s -> s.sewadars.all { it.name.isNotBlank() } })
    }

    // --- The genuine workbook ------------------------------------------------

    /**
     * Opt-in: point `SEWA_ROSTER_XLSX` at the sangat's real file to confirm the
     * committed fixture still stands in for it. Skipped when unset, so the
     * suite stays green on a machine that does not have the file.
     */
    @Test
    fun `the real workbook parses when one is supplied`() {
        val path = System.getenv("SEWA_ROSTER_XLSX")
        assumeTrue("SEWA_ROSTER_XLSX not set", path != null && File(path).exists())

        val plan = File(path!!).inputStream().use { RosterImport.parse(XlsxReader.read(it)) }
        val selection = plan.defaultSelection
        val departments = plan.departments(selection)

        assertTrue("expected many departments, got ${departments.size}", departments.size >= 20)
        assertTrue("expected hundreds of sewadars, got ${plan.sewadarCount(selection)}", plan.sewadarCount(selection) >= 300)
        assertTrue("expected HODs to be detected", plan.hodCount(selection) >= 20)
        assertTrue(departments.all { it.name.isNotBlank() })
        assertTrue(departments.all { d -> d.sewadars.all { it.name.isNotBlank() } })

        // Department names are unique, and no one is listed twice in a team —
        // either would show the same person twice in the register.
        assertEquals(departments.map { it.name.lowercase() }.toSet().size, departments.size)
        assertTrue(
            departments.all { d -> d.sewadars.map { it.name.lowercase() }.toSet().size == d.sewadars.size },
        )
    }
}
