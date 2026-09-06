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
import org.sewaentry.tablet.data.DepartmentEntity
import org.sewaentry.tablet.data.SewaDatabase
import org.sewaentry.tablet.data.SewaDays
import org.sewaentry.tablet.data.SewaRepository
import org.sewaentry.tablet.data.SewadarStats
import org.sewaentry.tablet.data.SewadarWithStatus
import org.sewaentry.tablet.export.AttendanceExport
import java.time.LocalDate

/**
 * Holds the register's state: which day, which department, who is selected,
 * and which signature dialog (if any) is open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SewaRepository(SewaDatabase.get(app))

    private val _date = MutableStateFlow(SewaDays.currentOrPrevious())
    val date: StateFlow<LocalDate> = _date.asStateFlow()

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

    /**
     * The name grid. Re-queries whenever the day or the department changes,
     * which is what makes the tiles flip to the new department's states.
     */
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
        // Land on a department immediately so the register is usable on open.
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
        // Clearing the selection is what prevents a name staying highlighted
        // after switching to a department it does not belong to.
        _selectedSewadarId.value = null
    }

    fun selectSewadar(id: Long?) {
        _selectedSewadarId.value = id
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** Live tap: the time is the device clock, never anything the user typed. */
    fun recordArrival(sewadarId: Long, signature: String) = viewModelScope.launch {
        val result = repo.recordArrival(
            sewadarId = sewadarId,
            departmentId = _departmentId.value,
            date = _date.value,
            at = System.currentTimeMillis(),
            signature = signature,
        )
        result.exceptionOrNull()?.let { _message.value = it.message }
    }

    fun recordDeparture(sewadarId: Long, signature: String) = viewModelScope.launch {
        val result = repo.recordDeparture(
            sewadarId = sewadarId,
            departmentId = _departmentId.value,
            date = _date.value,
            at = System.currentTimeMillis(),
            signature = signature,
        )
        result.exceptionOrNull()?.let { _message.value = it.message }
    }

    /**
     * Admin correction. Unlike a tap this sets the time by hand, so the row is
     * flagged as edited and the register shows it.
     */
    fun adminSetTime(sewadarId: Long, arrival: Boolean, hour: Int, minute: Int) = viewModelScope.launch {
        val at = SewaDays.timeOnDate(_date.value, hour, minute)
        val result = if (arrival) {
            repo.recordArrival(sewadarId, _departmentId.value, _date.value, at, signature = null, edited = true)
        } else {
            repo.recordDeparture(sewadarId, _departmentId.value, _date.value, at, signature = null, edited = true)
        }
        result.fold(
            onSuccess = { _message.value = "Time updated." },
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

    fun addDepartment(name: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        repo.addDepartment(name)
    }

    // --- Analytics ---------------------------------------------------------

    private val _statsFrom = MutableStateFlow(LocalDate.now().minusMonths(3))
    private val _statsTo = MutableStateFlow(LocalDate.now())
    val statsFrom: StateFlow<LocalDate> = _statsFrom.asStateFlow()
    val statsTo: StateFlow<LocalDate> = _statsTo.asStateFlow()

    val stats: StateFlow<List<SewadarStats>> =
        combine(_statsFrom, _statsTo) { from, to -> from to to }
            .flatMapLatest { (from, to) -> repo.stats(from, to) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setStatsRange(from: LocalDate, to: LocalDate) {
        _statsFrom.value = from
        _statsTo.value = to
    }

    fun exportExcel() = viewModelScope.launch {
        try {
            val from = _statsFrom.value
            val to = _statsTo.value
            val sheets = AttendanceExport.build(
                stats = stats.value,
                rows = repo.exportRows(from, to),
                from = from,
                to = to,
            )
            val name = "sewa_attendance_${SewaDays.format(from)}_to_${SewaDays.format(to)}.xlsx"
            val saved = AttendanceExport.save(getApplication(), name, sheets)
            _message.value = "Saved ${saved.fileName} to ${saved.location}."
        } catch (e: Exception) {
            _message.value = "Export failed: ${e.message}"
        }
    }
}
