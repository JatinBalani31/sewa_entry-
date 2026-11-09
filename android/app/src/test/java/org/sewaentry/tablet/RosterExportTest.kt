package org.sewaentry.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sewaentry.tablet.data.DepartmentEntity
import org.sewaentry.tablet.data.SewadarEntity
import org.sewaentry.tablet.export.RosterExport
import org.sewaentry.tablet.export.XlsxWriter
import org.sewaentry.tablet.roster.RosterImport
import org.sewaentry.tablet.roster.XlsxReader
import java.io.ByteArrayOutputStream

class RosterExportTest {
    @Test
    fun `roster export retains phone numbers and generic organization text`() {
        val department = DepartmentEntity(id = 1, name = "Garden")
        val person = SewadarEntity(
            id = 4,
            name = "Asha Example",
            departmentId = 1,
            phone = "9000011111",
            phone2 = "9000022222",
            organization = "Community Group - HOD",
        )
        val output = ByteArrayOutputStream()
        XlsxWriter().write(output, RosterExport.build(listOf(department), listOf(person)))

        val plan = RosterImport.parse(XlsxReader.read(output.toByteArray().inputStream()))
        val imported = plan.departments(plan.defaultSelection).single().sewadars.single()

        assertEquals(person.name, imported.name)
        assertEquals(person.phone, imported.phone)
        assertEquals(person.phone2, imported.phone2)
        assertEquals(person.organization, imported.organization)
        assertFalse(imported.isHod)
    }
}
