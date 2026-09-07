package org.sewaentry.tablet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.sewaentry.tablet.data.DayFilter
import org.sewaentry.tablet.data.DepartmentEntity
import org.sewaentry.tablet.data.ExportRow
import org.sewaentry.tablet.data.PeriodTotals
import org.sewaentry.tablet.data.ReportPeriod
import org.sewaentry.tablet.data.Reporting
import org.sewaentry.tablet.data.SewaDatabase
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewaRepository
import org.sewaentry.tablet.data.SewadarTotals
import org.sewaentry.tablet.data.SewadarWithStatus
import org.sewaentry.tablet.export.AttendanceExport
import java.time.LocalDate

/** Everything the analytics tab needs, recomputed whenever a filter changes. */
data class ReportState(
    val totals: List<SewadarTotals> = emptyList(),
    val byPeriod: List<PeriodTotals> = emptyList(),
    val periodKeys: List<String> = emptyList(),
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

    /** Live tap: the time is the device clock. Only offered on today. */
    fun recordArrival(sewadarId: Long, signature: String) = viewModelScope.launch {
        repo.recordArrival(
            sewadarId = sewadarId,
            departmentId = _departmentId.value,
            date = _date.value,
            at = System.currentTimeMillis(),
            signature = signature,
        ).exceptionOrNull()?.let { _message.value = it.message }
    }

    fun recordDeparture(sewadarId: Long, signature: String) = viewModelScope.launch {
        repo.recordDeparture(
            sewadarId = sewadarId,
            departmentId = _departmentId.value,
            date = _date.value,
            at = System.currentTimeMillis(),
            signature = signature,
        ).exceptionOrNull()?.let { _message.value = it.message }
    }

    /**
     * Manual entry. Sets the time by hand on the currently selected date, and
     * flags the row as edited so the register and every export show it.
     */
    fun manualSetTime(sewadarId: Long, arrival: Boolean, hour: Int, minute: Int) = viewModelScope.launch {
        val at = SewaDays.timeOnDate(_date.value, hour, minute)
        val result = if (arrival) {
            repo.recordArrival(sewadarId, _departmentId.value, _date.value, at, signature = null, edited = true)
        } else {
            repo.recordDeparture(sewadarId, _departmentId.value, _date.value, at, signature = null, edited = true)
        }
        result.fold(
            onSuccess = { _message.value = "Time saved." },
            onFailure = { _message.value = it.message },
        )
    }

    fun clearRecord(sewadarId: Long) = viewModelScope.launch {
        repo.clear(sewadarId, _date.value)
        _message.value = "Entry cleared."
    }

    fun addSewadar(name: String, phone: String?) = viewModelScope.launch {
        val dept = _departmentId.value ?: return@launch
        if (name.isBlank()) return@launch
        repo.addSewadar(name, dept, phone)
    }

    // --- Analytics ---------------------------------------------------------

    private val _from = MutableStateFlow(LocalDate.now().minusMonths(3))
    private val _to = MutableStateFlow(LocalDate.now())
    private val _dayFilter = MutableStateFlow(DayFilter.ALL)
    private val _period = MutableStateFlow(ReportPeriod.OVERALL)
    private val _reportDepartmentId = MutableStateFlow<Long?>(null)

    val from: StateFlow<LocalDate> = _from.asStateFlow()
    val to: StateFlow<LocalDate> = _to.asStateFlow()
    val dayFilter: StateFlow<DayFilter> = _dayFilter.asStateFlow()
    val period: StateFlow<ReportPeriod> = _period.asStateFlow()
    val reportDepartmentId: StateFlow<Long?> = _reportDepartmentId.asStateFlow()

    /**
     * Reports are computed from the raw rows in Kotlin rather than in SQL:
     * the filtering and bucketing is the part most likely to be wrong, and
     * this way it is pure, shared with the Excel export, and unit-tested.
     */
    val report: StateFlow<ReportState> =
        combine(_from, _to, _dayFilter, _period, _reportDepartmentId) { from, to, day, period, dept ->
            listOf(from, to, day, period, dept)
        }.flatMapLatest {
            flowOf(Unit).map {
                val all = repo.exportRows(_from.value, _to.value)
                val scoped = if (_reportDepartmentId.value == null) all
                    else all.filter { it.departmentId == _reportDepartmentId.value }
                val filtered = Reporting.filter(scoped, _dayFilter.value)
                val roster = repo.rosterRefs(_reportDepartmentId.value)

                ReportState(
                    totals = Reporting.totals(filtered, roster),
                    byPeriod = Reporting.byPeriod(filtered, _period.value),
                    periodKeys = Reporting.periodKeys(filtered, _period.value),
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

    fun setPeriod(value: ReportPeriod) {
        _period.value = value
    }

    fun setReportDepartment(id: Long?) {
        _reportDepartmentId.value = id
    }

    fun exportExcel() = viewModelScope.launch {
        try {
            val state = report.value
            val departmentName = departments.value.firstOrNull { it.id == _reportDepartmentId.value }?.name

            val sheets = AttendanceExport.build(
                totals = state.totals,
                byPeriod = state.byPeriod,
                periodKeys = state.periodKeys,
                rows = state.rows,
                from = _from.value,
                to = _to.value,
                dayFilter = _dayFilter.value,
                period = _period.value,
                departmentName = departmentName,
            )

            val tag = buildString {
                append(SewaDays.format(_from.value))
                append("_to_")
                append(SewaDays.format(_to.value))
                if (_dayFilter.value != DayFilter.ALL) append("_${_dayFilter.value.name.lowercase()}")
                if (_period.value != ReportPeriod.OVERALL) append("_${_period.value.name.lowercase()}")
            }
            val saved = AttendanceExport.save(getApplication(), "sewa_hours_$tag.xlsx", sheets)
            _message.value = "Saved ${saved.fileName} to ${saved.location}."
        } catch (e: Exception) {
            _message.value = "Export failed: ${e.message}"
        }
    }
}
