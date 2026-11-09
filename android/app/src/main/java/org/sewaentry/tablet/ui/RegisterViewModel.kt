package org.sewaentry.tablet.ui

import android.app.Application
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.sewaentry.tablet.data.AdminLock
import org.sewaentry.tablet.data.PinAttemptResult
import org.sewaentry.tablet.data.FullLocalBackup
import org.sewaentry.tablet.data.BackupManager
import org.sewaentry.tablet.data.BackupErrorMessages
import org.sewaentry.tablet.data.DayFilter
import org.sewaentry.tablet.data.DepartmentEntity
import org.sewaentry.tablet.data.DepartmentGroup
import org.sewaentry.tablet.data.ExportRow
import org.sewaentry.tablet.data.Quarter
import org.sewaentry.tablet.data.Reporting
import org.sewaentry.tablet.data.SewaDatabase
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewaRepository
import org.sewaentry.tablet.data.SewadarTotals
import org.sewaentry.tablet.data.SewadarWithStatus
import org.sewaentry.tablet.export.AttendanceExport
import org.sewaentry.tablet.export.RosterExport
import org.sewaentry.tablet.export.XlsxWriter
import org.sewaentry.tablet.roster.ImportPlan
import org.sewaentry.tablet.roster.RosterImport
import org.sewaentry.tablet.roster.XlsxReader
import java.time.LocalDate

/** Everything the summary tab needs, recomputed whenever a filter changes. */
data class ReportState(
    val totals: List<SewadarTotals> = emptyList(),
    val departments: List<DepartmentGroup> = emptyList(),
    val monthlyHours: Map<Pair<Long, String>, Double> = emptyMap(),
    val sewaDates: List<String> = emptyList(),
    val rows: List<ExportRow> = emptyList(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SewaRepository(SewaDatabase.get(app))

    // --- Register ----------------------------------------------------------

    private val _date = MutableStateFlow(SewaDays.currentOrPrevious())
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    /**
     * Live capture only makes sense on the day it happens; every other day is
     * manual-entry only. The register screen reads this to decide whether the
     * arrival/departure buttons exist at all.
     */
    val isToday: StateFlow<Boolean> =
        _date.map { it == LocalDate.now() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, _date.value == LocalDate.now())

    private val _departmentId = MutableStateFlow<Long?>(null)
    val departmentId: StateFlow<Long?> = _departmentId.asStateFlow()

    private val _selectedSewadarId = MutableStateFlow<Long?>(null)
    val selectedSewadarId: StateFlow<Long?> = _selectedSewadarId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val departments: StateFlow<List<DepartmentEntity>> =
        repo.departments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recordedDates: StateFlow<Set<String>> =
        repo.recordedDates()
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val roster: StateFlow<List<SewadarWithStatus>> =
        combine(_departmentId, _date) { dept, date -> dept to date }
            .flatMapLatest { (dept, date) ->
                if (dept == null) flowOf(emptyList()) else repo.register(dept, date)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<SewadarWithStatus?> =
        combine(roster, _selectedSewadarId) { list, id -> list.firstOrNull { it.sewadarId == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            departments.collect { list ->
                if (_departmentId.value == null && list.isNotEmpty()) {
                    _departmentId.value = list.first().id
                }
            }
        }
    }

    fun selectDate(value: LocalDate) {
        if (value.isAfter(LocalDate.now())) return
        _date.value = value
        _selectedSewadarId.value = null
    }

    fun selectDepartment(id: Long) {
        _departmentId.value = id
        _selectedSewadarId.value = null
    }

    fun selectSewadar(id: Long?) {
        _selectedSewadarId.value = id
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** Manual live arrival: the time is the device clock. Star-marked as manual entry. */
    fun recordArrival(sewadarId: Long) = viewModelScope.launch {
        repo.recordArrival(
            sewadarId = sewadarId,
            departmentId = _departmentId.value,
            date = _date.value,
            at = System.currentTimeMillis(),
            signature = null,
            edited = true,
            method = "MANUAL",
            adminId = null,
            note = null,
        ).fold(
            onSuccess = { _message.value = "Arrival recorded." },
            onFailure = { _message.value = it.message },
        )
    }

    /** Manual live departure: the time is the device clock. Star-marked as manual entry. */
    fun recordDeparture(sewadarId: Long) = viewModelScope.launch {
        repo.recordDeparture(
            sewadarId = sewadarId,
            date = _date.value,
            at = System.currentTimeMillis(),
            signature = null,
            edited = true,
            method = "MANUAL",
            adminId = null,
            note = null,
        ).fold(
            onSuccess = { _message.value = "Departure recorded." },
            onFailure = { _message.value = it.message },
        )
    }

    /**
     * Manual entry. Sets the time by hand on the currently selected date, and
     * flags the row as edited (star-marked) so the register and every export show it.
     */
    fun manualSetTime(
        sewadarId: Long,
        arrival: Boolean,
        hour: Int,
        minute: Int,
    ) = viewModelScope.launch {
        val at = SewaDays.timeOnDate(_date.value, hour, minute)
        val result = if (arrival) {
            repo.recordArrival(
                sewadarId, _departmentId.value, _date.value, at, signature = null, edited = true,
                method = "MANUAL", adminId = null, note = null,
            )
        } else {
            repo.recordDeparture(
                sewadarId, _date.value, at, signature = null, edited = true,
                method = "MANUAL", adminId = null, note = null,
            )
        }
        result.fold(
            onSuccess = { _message.value = "Time saved." },
            onFailure = { _message.value = it.message },
        )
    }

    fun clearRecord(sewadarId: Long, arrival: Boolean) = viewModelScope.launch {
        repo.clear(sewadarId, _date.value, arrival = arrival).fold(
            onSuccess = { _message.value = if (arrival) "Arrival cleared." else "Departure cleared." },
            onFailure = { _message.value = it.message },
        )
    }

    fun addSewadar(name: String, phone: String?, organization: String? = null) = viewModelScope.launch {
        val dept = _departmentId.value ?: return@launch
        if (name.isBlank()) return@launch
        repo.addSewadar(name, dept, phone, organization)
        _message.value = "Sewadar '$name' added."
    }

    fun exportRoster(uri: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val database = SewaDatabase.get(getApplication<Application>())
                val sheets = RosterExport.build(database.departments().all(), database.sewadars().all())
                val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected file location.")
                output.use { XlsxWriter().write(it, sheets) }
            }
        }
        result.fold(
            onSuccess = { _message.value = "Roster Excel file exported with contact numbers." },
            onFailure = { _message.value = "Roster export failed: ${it.message ?: "could not write the file"}" },
        )
    }

    fun removeSewadar(sewadarId: Long) = viewModelScope.launch {
        repo.removeSewadar(sewadarId)
        _selectedSewadarId.value = null
        _message.value = "Sewadar removed from the active roster. Their saved records were kept."
    }

    fun updateSewadarDetails(
        sewadarId: Long,
        name: String,
        phone: String?,
        organization: String?,
        departmentId: Long,
    ) = viewModelScope.launch {
        try {
            repo.updateSewadarDetails(sewadarId, name, phone, organization, departmentId)
            _message.value = "Sewadar details updated."
        } catch (error: IllegalArgumentException) {
            _message.value = error.message ?: "Could not update sewadar details."
        } catch (error: SQLiteException) {
            _message.value = "Could not update sewadar details: ${error.message ?: "unknown error"}"
        }
    }

    fun renameDepartment(departmentId: Long, name: String) = viewModelScope.launch {
        try {
            repo.renameDepartment(departmentId, name)
            _message.value = "Department name updated."
        } catch (error: IllegalArgumentException) {
            _message.value = error.message ?: "Could not update department name."
        } catch (error: SQLiteException) {
            _message.value = "Could not update department name: ${error.message ?: "unknown error"}"
        }
    }

    // --- Analytics ---------------------------------------------------------

    // The summary opens on the current quarter, because producing the
    // quarterly summary is the job this screen exists for.
    private val _quarter = MutableStateFlow(Quarter.current())
    private val _from = MutableStateFlow(Quarter.current().start)
    private val _to = MutableStateFlow(Quarter.current().end)
    private val _dayFilter = MutableStateFlow(DayFilter.ALL)
    private val _reportDepartmentId = MutableStateFlow<Long?>(null)
    private val _reportSewadarId = MutableStateFlow<Long?>(null)

    val quarter: StateFlow<Quarter> = _quarter.asStateFlow()
    val from: StateFlow<LocalDate> = _from.asStateFlow()
    val to: StateFlow<LocalDate> = _to.asStateFlow()
    val dayFilter: StateFlow<DayFilter> = _dayFilter.asStateFlow()
    val reportDepartmentId: StateFlow<Long?> = _reportDepartmentId.asStateFlow()
    val reportSewadarId: StateFlow<Long?> = _reportSewadarId.asStateFlow()

    /**
     * What the period dropdown reads. Held rather than derived, because
     * "this quarter" and "2026 Q3" can be the same dates but are not the same
     * choice, and the label should say which one was picked.
     */
    private val _periodLabel = MutableStateFlow(Quarter.current().label)
    val periodLabel: StateFlow<String> = _periodLabel.asStateFlow()

    /** Picking a quarter sets the whole range — one choice, not two dates. */
    fun selectQuarter(value: Quarter) {
        _quarter.value = value
        _from.value = value.start
        _to.value = value.end
        _periodLabel.value = value.label
    }

    /** A span that is not quarter-shaped: this month, a year, everything. */
    fun selectRange(label: String, from: LocalDate, to: LocalDate) {
        _from.value = from
        _to.value = to
        _periodLabel.value = label
        // Keep the quarter pointing at wherever the range ends, so the export's
        // month columns still line up with the period on screen.
        _quarter.value = Quarter.of(to)
    }

    /**
     * Reports are computed from the raw rows in Kotlin rather than in SQL:
     * the filtering and bucketing is the part most likely to be wrong, and
     * this way it is pure, shared with the Excel export, and unit-tested.
     */
    init {
        // A report needs a department to be about. Rather than opening on a
        // meaningless total across the whole sangat, the summary lands on the
        // first department and the admin changes it from there.
        viewModelScope.launch {
            departments.collect { list ->
                val current = _reportDepartmentId.value
                if (current == null || list.none { it.id == current }) {
                    _reportDepartmentId.value = list.firstOrNull()?.id
                }
            }
        }
    }

    val report: StateFlow<ReportState> =
        combine(_from, _to, _dayFilter, _reportDepartmentId, _reportSewadarId) { f, t, d, dept, who ->
            listOf(f, t, d, dept, who)
        }.flatMapLatest {
            flowOf(Unit).map {
                val all = repo.exportRows(_from.value, _to.value)
                // A report is always about one department, and optionally one
                // person within it. A total across the whole sangat is a
                // number nobody asked for.
                val inDepartment = all.filter { it.departmentId == _reportDepartmentId.value }
                val scoped = _reportSewadarId.value
                    ?.let { id -> inDepartment.filter { row -> row.sewadarId == id } }
                    ?: inDepartment
                val filtered = Reporting.filter(scoped, _dayFilter.value)

                val roster = repo.rosterRefs(_reportDepartmentId.value)
                    .filter { _reportSewadarId.value == null || it.id == _reportSewadarId.value }

                val totals = Reporting.totals(filtered, roster)

                ReportState(
                    totals = totals,
                    departments = Reporting.byDepartment(totals),
                    monthlyHours = Reporting.monthlyHours(filtered),
                    sewaDates = Reporting.sewaDatesIn(filtered),
                    rows = filtered,
                    loading = false,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportState())

    fun setRange(from: LocalDate, to: LocalDate) {
        _from.value = from
        _to.value = to
    }

    fun setDayFilter(value: DayFilter) {
        _dayFilter.value = value
    }

    fun setReportDepartment(id: Long?) {
        _reportDepartmentId.value = id
        // Whoever was picked belonged to the department being left.
        _reportSewadarId.value = null
    }

    fun setReportSewadar(id: Long?) {
        _reportSewadarId.value = id
    }

    // --- Admin lock --------------------------------------------------------

    private val lock = AdminLock(app)

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private val _lastAdminActivity = MutableStateFlow(0L)
    val lastAdminActivity: StateFlow<Long> = _lastAdminActivity.asStateFlow()

    /** False until someone sets a PIN — all tabs remain gated until setup completes. */
    val pinConfigured: Boolean get() = lock.isConfigured
    val pinMigrationRequired: Boolean get() = lock.requiresPinMigration

    fun unlock(pin: String): PinAttemptResult {
        val result = lock.verifyCurrentPin(pin)
        if (result.accepted) {
            _unlocked.value = true
            _lastAdminActivity.value = System.currentTimeMillis()
        }
        return result
    }

    fun verifyCurrentPin(pin: String): PinAttemptResult =
        lock.verifyCurrentPin(pin)

    fun changePin(current: String, next: String): PinAttemptResult {
        val result = if (lock.requiresPinMigration) {
            lock.migratePin(current, next)
        } else {
            lock.changePin(current, next)
        }
        if (result.accepted) {
            _unlocked.value = true
            _lastAdminActivity.value = System.currentTimeMillis()
        }
        return result
    }

    fun setPin(pin: String): Boolean {
        val ok = lock.setPin(pin)
        if (ok) {
            _unlocked.value = true
            _lastAdminActivity.value = System.currentTimeMillis()
        }
        return ok
    }

    fun markAdminActivity() {
        if (_unlocked.value) _lastAdminActivity.value = System.currentTimeMillis()
    }

    /** Locks the admin area again — used when the tablet goes back on the counter. */
    fun lockAdmin() {
        _unlocked.value = false
        _lastAdminActivity.value = 0L
    }

    // --- Backup / Restore --------------------------------------------------

    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    /** Suggested file name for the "save as" dialog. */
    val backupFileName: String get() = FullLocalBackup.suggestedFileName()
    val databaseBackupFileName: String get() = BackupManager.suggestedFileName()

    fun backupDatabaseTo(uri: Uri) = viewModelScope.launch {
        _backupBusy.value = true
        val result = withContext(Dispatchers.IO) { runCatching { BackupManager.backupTo(getApplication(), uri) } }
        _backupBusy.value = false
        result.fold({ _message.value = "App data backup saved." }, { _message.value = "Backup failed: ${it.message}" })
    }

    fun restoreDatabaseFrom(uri: Uri) = viewModelScope.launch {
        _backupBusy.value = true
        val result = withContext(Dispatchers.IO) { runCatching { BackupManager.restoreFrom(getApplication(), uri) } }
        _backupBusy.value = false
        result.fold({ _message.value = "App data restored. Restarting…"; _needsRestart.value = true }, { _message.value = "Restore failed: ${it.message}" })
    }

    fun backupTo(uri: Uri, password: CharArray) = viewModelScope.launch {
        _backupBusy.value = true
        try {
            withContext(Dispatchers.IO) { FullLocalBackup.backupTo(getApplication(), uri, password) }
            _message.value = "Full backup saved with roster, attendance, and face entries. Keep the password safe."
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _message.value = BackupErrorMessages.describe("Backup failed", error)
        } finally {
            password.fill('\u0000')
            _backupBusy.value = false
        }
    }

    /**
     * Restores from a backup file. After overwriting the database the process
     * must restart so Room picks up the new file — we use the activity's
     * recreate() for that, triggered by [needsRestart].
     */
    private val _needsRestart = MutableStateFlow(false)
    val needsRestart: StateFlow<Boolean> = _needsRestart.asStateFlow()

    fun restoreFrom(uri: Uri, password: CharArray) = viewModelScope.launch {
        _backupBusy.value = true
        try {
            _message.value = withContext(Dispatchers.IO) {
                FullLocalBackup.restoreFrom(getApplication(), uri, password)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _message.value = BackupErrorMessages.describe("Backup import failed", error)
        } finally {
            password.fill('\u0000')
            _backupBusy.value = false
        }
    }

    // --- Roster import -----------------------------------------------------

    private val _import = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _import.asStateFlow()

    /**
     * Reads a chosen .xlsx and shows what it would do. Nothing is written until
     * the admin confirms, so a wrong file costs nothing.
     */
    fun previewImport(uri: Uri) = viewModelScope.launch {
        _import.value = ImportUiState(loading = true)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val stream = resolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("That file could not be opened.")
                stream.use { RosterImport.parse(XlsxReader.read(it)) }
            }
        }
        result.fold(
            onSuccess = { plan ->
                _import.value = ImportUiState(
                    plan = plan,
                    selection = plan.defaultSelection,
                    fileName = uri.lastPathSegment?.substringAfterLast('/'),
                )
            },
            onFailure = { error ->
                _import.value = ImportUiState(
                    error = error.message ?: "That file could not be read as a spreadsheet.",
                )
            },
        )
    }

    fun toggleImportSheet(sheet: String) {
        val state = _import.value
        val next = if (sheet in state.selection) state.selection - sheet else state.selection + sheet
        _import.value = state.copy(selection = next)
    }

    fun setDeactivateMissing(value: Boolean) {
        _import.value = _import.value.copy(deactivateMissing = value)
    }

    fun clearImport() {
        _import.value = ImportUiState()
    }

    fun confirmImport() = viewModelScope.launch {
        val state = _import.value
        val plan = state.plan ?: return@launch
        _import.value = state.copy(applying = true)

        val result = runCatching {
            repo.applyImport(plan.departments(state.selection), state.deactivateMissing)
        }
        result.fold(
            onSuccess = {
                _import.value = ImportUiState(completedSummary = "Roster imported - ${it.summary}")
            },
            onFailure = {
                _import.value = state.copy(applying = false, error = it.message ?: "Import failed.")
            },
        )
    }

    fun exportExcel() = viewModelScope.launch {
        try {
            val state = report.value
            val departmentName = departments.value
                .firstOrNull { it.id == _reportDepartmentId.value }?.name
                ?: "All departments"
            val sewadarName = _reportSewadarId.value
                ?.let { id -> state.totals.firstOrNull { it.sewadarId == id }?.name }

            val sheets = AttendanceExport.build(
                quarter = _quarter.value,
                departmentName = departmentName,
                sewadarName = sewadarName,
                periodLabel = _periodLabel.value,
                totals = state.totals,
                monthlyHours = state.monthlyHours,
                sewaDates = state.sewaDates,
                rows = state.rows,
                from = _from.value,
                to = _to.value,
                dayFilter = _dayFilter.value,
            )

            // The file name says what the report is about, so a folder of
            // exports can be read without opening any of them.
            val tag = buildString {
                append((sewadarName ?: departmentName).replace(Regex("""[^A-Za-z0-9]+"""), "-").trim('-'))
                append("_")
                append(_periodLabel.value.replace(Regex("""[^A-Za-z0-9]+"""), "-").trim('-'))
                if (_dayFilter.value != DayFilter.ALL) append("_${_dayFilter.value.name.lowercase()}")
            }
            val saved = AttendanceExport.save(getApplication(), "sewa_hours_$tag.xlsx", sheets)
            _message.value = "Saved ${saved.fileName} to ${saved.location}."
        } catch (e: Exception) {
            _message.value = "Export failed: ${e.message}"
        }
    }
}

/** The import wizard's state: read a file, review, confirm. */
data class ImportUiState(
    val plan: ImportPlan? = null,
    val selection: Set<String> = emptySet(),
    val fileName: String? = null,
    val deactivateMissing: Boolean = false,
    val loading: Boolean = false,
    val applying: Boolean = false,
    val error: String? = null,
    val completedSummary: String? = null,
) {
    val isOpen: Boolean get() = plan != null || loading || error != null
}
