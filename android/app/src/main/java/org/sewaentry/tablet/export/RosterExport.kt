package org.sewaentry.tablet.export

import org.sewaentry.tablet.data.DepartmentEntity
import org.sewaentry.tablet.data.SewadarEntity

object RosterExport {
    fun build(departments: List<DepartmentEntity>, sewadars: List<SewadarEntity>): List<XlsxWriter.Sheet> {
        val departmentNames = departments.associate { it.id to it.name }
        val grouped = sewadars.asSequence()
            .filter { it.isActive && it.departmentId != null }
            .groupBy { it.departmentId!! }

        return departments.filter { it.isActive }.map { department ->
            val rows = grouped[department.id].orEmpty()
                .sortedBy { it.name.lowercase() }
                .mapIndexed { index, person ->
                    listOf(
                        XlsxWriter.Cell.Number((index + 1).toDouble()),
                        text(person.name),
                        text(person.phone),
                        text(person.phone2),
                        text(person.organization),
                    )
                }
            XlsxWriter.Sheet(
                name = departmentNames[department.id] ?: department.name,
                headers = listOf(
                    "Sr. No.",
                    "Name of Sewadar",
                    "Contact No. 1",
                    "Contact No. 2",
                    "Organization",
                ),
                columnWidths = listOf(10, 30, 18, 18, 30),
                rows = rows,
            )
        }.ifEmpty {
            listOf(
                XlsxWriter.Sheet(
                    name = "Roster",
                    headers = listOf("Sr. No.", "Name of Sewadar", "Contact No. 1", "Contact No. 2", "Organization"),
                    rows = emptyList(),
                ),
            )
        }
    }

    private fun text(value: String?): XlsxWriter.Cell =
        value?.takeIf { it.isNotBlank() }?.let(XlsxWriter.Cell::Text) ?: XlsxWriter.Cell.Blank
}
