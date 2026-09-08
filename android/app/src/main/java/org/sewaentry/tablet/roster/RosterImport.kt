package org.sewaentry.tablet.roster

/**
 * Reads the sangat's "List of HOD & All Sewadar Contact Detail" workbook into
 * departments and sewadars.
 *
 * The workbook is the sangat's own document, not a format invented for this
 * app — one sheet per department, a `Department :- X` line, then a table of
 * `Sr. No. | Name of Sewadar | Contact No. 1 | Contact No. 2`, with the HOD's
 * name in **bold**. Bold is therefore data, not decoration, which is why the
 * reader bothers to resolve it.
 *
 * The result is deliberately reported **per sheet** rather than pre-merged into
 * departments. Two sheets can claim the same department — in the real workbook
 * one of them is a scratch copy holding people who belong to other teams — and
 * silently merging them would quietly triple a department's roster. The
 * importer flags the clash and lets the admin decide instead of guessing.
 *
 * Everything here is pure: a workbook in, a plan out, no database and no
 * Android. That keeps the rules — the part most likely to be wrong —
 * unit-testable against the real file.
 */
object RosterImport {

    private val DEPARTMENT_LABEL = Regex("""^department\s*:?\s*-?\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val NAME_HEADER = Regex("""name\s*of\s*sewadar""", RegexOption.IGNORE_CASE)
    private val CONTACT_HEADER = Regex("""contact|mobile|phone""", RegexOption.IGNORE_CASE)

    /** How far down to look for the department line and the table header. */
    private const val HEADER_SEARCH_ROWS = 15

    fun parse(workbook: XlsxWorkbook): ImportPlan {
        val read = mutableListOf<ParsedSheet>()
        val skipped = mutableListOf<SkippedSheet>()

        for (sheet in workbook.sheets) {
            val blocks = findBlocks(sheet)
            if (blocks.isEmpty()) {
                skipped += SkippedSheet(sheet.name, "no \"Name of Sewadar\" column")
                continue
            }

            val people = dedupe(blocks.flatMap { readBlock(sheet, it) })
            if (people.rows.isEmpty()) {
                skipped += SkippedSheet(sheet.name, "no names listed")
                continue
            }

            // The department line is what the sangat wrote; the sheet name is
            // only a fallback for a sheet that omits it.
            read += ParsedSheet(
                sheet = sheet.name.trim(),
                department = departmentName(sheet) ?: sheet.name.trim(),
                sewadars = people.rows,
                tables = blocks.size,
                duplicatesMerged = people.duplicates,
            )
        }

        return ImportPlan(sheets = resolveClashes(read), skipped = skipped)
    }

    /**
     * Several sheets can declare the same department. In the real workbook one
     * of them is a scratch copy holding people from other teams, so importing
     * both would swell the department with names that do not belong to it.
     *
     * The canonical sheet is the one whose own tab name is the department name
     * — a sheet called "Account" is the Account sheet; "Account (2)" is a copy
     * of it. Failing that, the one that carries more contact numbers, since the
     * maintained sheet is the one people keep filling in. Everything else is
     * marked as a clash and starts excluded, visible for the admin to override.
     */
    private fun resolveClashes(sheets: List<ParsedSheet>): List<ParsedSheet> {
        val canonical = sheets
            .groupBy { it.department.lowercase() }
            .mapValues { (_, group) ->
                group.maxWithOrNull(
                    compareBy<ParsedSheet> { it.sheet.equals(it.department, ignoreCase = true) }
                        .thenBy { it.sewadars.count { p -> p.phone != null } }
                        .thenByDescending { sheets.indexOf(it) },
                )!!
            }

        return sheets.map { sheet ->
            val winner = canonical.getValue(sheet.department.lowercase())
            if (winner === sheet) sheet else sheet.copy(conflictsWith = winner.sheet)
        }
    }

    private fun departmentName(sheet: XlsxSheet): String? {
        for (row in 1..minOf(sheet.maxRow, HEADER_SEARCH_ROWS)) {
            for (col in 1..8) {
                val match = DEPARTMENT_LABEL.find(sheet.text(row, col)) ?: continue
                val name = match.groupValues[1].collapseSpaces()
                if (name.isNotEmpty()) return name
            }
        }
        return null
    }

    /**
     * One table = one "Name of Sewadar" header cell.
     *
     * Sheets are usually a single table, but at least one in the real workbook
     * puts a second table beside the first. Keying off every header cell rather
     * than assuming column B means those people are read rather than dropped.
     */
    private fun findBlocks(sheet: XlsxSheet): List<Block> {
        val headers = sheet.cells
            .filter { it.row <= HEADER_SEARCH_ROWS && NAME_HEADER.containsMatchIn(it.text) }
            .sortedBy { it.col }

        return headers.mapIndexed { index, header ->
            // Contact columns run from just after the name to just before the
            // next table begins, capped at two — the sheet's own layout.
            val limit = headers.getOrNull(index + 1)?.col ?: (header.col + 3)
            val contacts = ((header.col + 1) until minOf(limit, header.col + 3))
                .filter { col ->
                    val heading = sheet.text(header.row, col)
                    heading.isEmpty() || CONTACT_HEADER.containsMatchIn(heading)
                }
            Block(headerRow = header.row, nameCol = header.col, contactCols = contacts)
        }
    }

    private fun readBlock(sheet: XlsxSheet, block: Block): List<ParsedSewadar> {
        val people = mutableListOf<ParsedSewadar>()

        // Scans to the end of the sheet rather than stopping at the first gap:
        // the real file has tables that resume after a blank run, and stopping
        // early would drop everyone below it.
        for (row in (block.headerRow + 1)..sheet.maxRow) {
            val name = sheet.text(row, block.nameCol).collapseSpaces()
            if (name.isEmpty() || looksLikeHeading(name)) continue

            val phones = block.contactCols
                .map { sheet.text(row, it).normalisePhone() }
                .filter { it.isNotEmpty() }

            people += ParsedSewadar(
                name = name,
                phone = phones.getOrNull(0),
                phone2 = phones.getOrNull(1),
                isHod = sheet.isBold(row, block.nameCol),
            )
        }
        return people
    }

    /** A repeated header inside the table body should not become a sewadar. */
    private fun looksLikeHeading(name: String): Boolean =
        NAME_HEADER.containsMatchIn(name) || name.equals("total", ignoreCase = true)

    private fun dedupe(people: List<ParsedSewadar>): Deduped {
        val byKey = LinkedHashMap<String, ParsedSewadar>()
        var duplicates = 0

        for (person in people) {
            val key = person.name.lowercase()
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = person
            } else {
                duplicates++
                // Keep whatever the fuller of the two rows knows.
                byKey[key] = existing.copy(
                    phone = existing.phone ?: person.phone,
                    phone2 = existing.phone2 ?: person.phone2,
                    isHod = existing.isHod || person.isHod,
                )
            }
        }
        return Deduped(byKey.values.toList(), duplicates)
    }

    /**
     * Folds the sheets the admin chose into the departments to write. Sheets
     * that share a department name merge here — by then it is an explicit
     * choice rather than a guess.
     */
    fun departmentsOf(sheets: List<ParsedSheet>): List<ParsedDepartment> {
        val grouped = LinkedHashMap<String, MutableList<ParsedSewadar>>()
        for (sheet in sheets) {
            grouped.getOrPut(sheet.department) { mutableListOf() } += sheet.sewadars
        }
        return grouped.map { (name, people) ->
            val merged = dedupe(people)
            ParsedDepartment(name, merged.rows, merged.duplicates)
        }
    }

    private fun String.collapseSpaces(): String = trim().replace(Regex("""\s+"""), " ")

    /**
     * Phone numbers in the sheet are written for reading ("86685  66515").
     * Digits are kept as one run so they can be dialled; anything that is not a
     * plausible number is dropped rather than stored as noise.
     */
    private fun String.normalisePhone(): String {
        val digits = filter { it.isDigit() }
        return if (digits.length < 6) "" else digits
    }

    private data class Block(val headerRow: Int, val nameCol: Int, val contactCols: List<Int>)
    private data class Deduped(val rows: List<ParsedSewadar>, val duplicates: Int)
}

data class ParsedSewadar(
    val name: String,
    val phone: String? = null,
    val phone2: String? = null,
    /** Bold in the sheet — the sangat's own marking for the head of department. */
    val isHod: Boolean = false,
)

/** One sheet of the workbook, as read. The unit the admin includes or excludes. */
data class ParsedSheet(
    val sheet: String,
    val department: String,
    val sewadars: List<ParsedSewadar>,
    /** How many side-by-side tables this sheet held. */
    val tables: Int = 1,
    /** Names listed twice within this sheet, folded into one. */
    val duplicatesMerged: Int = 0,
    /** The earlier sheet already claiming this department name, if any. */
    val conflictsWith: String? = null,
) {
    val hods: List<ParsedSewadar> get() = sewadars.filter { it.isHod }

    /** A clashing sheet has to be opted into, because it is usually a stray copy. */
    val includedByDefault: Boolean get() = conflictsWith == null
}

data class ParsedDepartment(
    val name: String,
    val sewadars: List<ParsedSewadar>,
    val duplicatesMerged: Int = 0,
) {
    val hods: List<ParsedSewadar> get() = sewadars.filter { it.isHod }
}

data class SkippedSheet(val sheet: String, val reason: String)

/** What an import would do, shown for confirmation before anything is written. */
data class ImportPlan(
    val sheets: List<ParsedSheet> = emptyList(),
    val skipped: List<SkippedSheet> = emptyList(),
) {
    val isEmpty: Boolean get() = sheets.isEmpty()

    val defaultSelection: Set<String>
        get() = sheets.filter { it.includedByDefault }.map { it.sheet }.toSet()

    val conflicts: List<ParsedSheet> get() = sheets.filter { it.conflictsWith != null }

    fun selected(selection: Set<String>): List<ParsedSheet> = sheets.filter { it.sheet in selection }

    fun departments(selection: Set<String>): List<ParsedDepartment> =
        RosterImport.departmentsOf(selected(selection))

    fun sewadarCount(selection: Set<String>): Int = departments(selection).sumOf { it.sewadars.size }

    fun hodCount(selection: Set<String>): Int = departments(selection).sumOf { it.hods.size }

    fun withoutHod(selection: Set<String>): List<String> =
        departments(selection).filter { it.hods.isEmpty() }.map { it.name }
}
