package jp.sekisho.vertical.ui

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.sekisho.vertical.data.CalEvent
import jp.sekisho.vertical.data.CalendarInfo
import jp.sekisho.vertical.data.CalendarRepository
import jp.sekisho.vertical.data.DataSignals
import jp.sekisho.vertical.data.Dates
import jp.sekisho.vertical.data.EventDraft
import jp.sekisho.vertical.data.Prefs
import jp.sekisho.vertical.data.Task
import jp.sekisho.vertical.data.TaskRepository
import jp.sekisho.vertical.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class ViewMode { WEEK, MONTH }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val calendarRepo = CalendarRepository(app)
    private val taskRepo = TaskRepository(app)
    private val prefs = Prefs(app)

    var anchor by mutableStateOf(LocalDate.now()); private set
    var mode by mutableStateOf(runCatching { ViewMode.valueOf(prefs.viewMode) }.getOrDefault(ViewMode.WEEK)); private set
    var events by mutableStateOf<List<CalEvent>>(emptyList()); private set
    var calendars by mutableStateOf<List<CalendarInfo>>(emptyList()); private set
    var hasPermission by mutableStateOf(calendarRepo.hasReadPermission()); private set
    var defaultCalendarId by mutableStateOf(prefs.defaultCalendarId); private set
    var message by mutableStateOf<String?>(null)
    var tasksGoogle by mutableStateOf(taskRepo.googleMode); private set
    var tasksConnecting by mutableStateOf(false); private set

    private var loadJob: Job? = null
    private var observerRegistered = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            reload()
            WidgetUpdater.refresh(getApplication())
        }
    }

    init {
        registerObserver()
        reload()
        viewModelScope.launch { DataSignals.errors.collect { message = it } }
    }

    private fun registerObserver() {
        if (observerRegistered || !hasPermission) return
        runCatching {
            getApplication<Application>().contentResolver
                .registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            observerRegistered = true
        }
    }

    fun onPermissionResult() {
        hasPermission = calendarRepo.hasReadPermission()
        registerObserver()
        reload()
        WidgetUpdater.refresh(getApplication())
    }

    /** 表示中の月の6週間分を読み込む（週間表示の週も必ず含まれる） */
    fun reload() {
        val from = Dates.monthGridStart(anchor)
        val to = from.plusDays(42)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val (ev, cals) = withContext(Dispatchers.IO) {
                calendarRepo.instances(from, to) to calendarRepo.calendars()
            }
            events = ev
            calendars = cals
        }
        if (taskRepo.googleMode) viewModelScope.launch { taskRepo.refresh(from, to) }
    }

    /** Google ToDo リストへの接続が許可された */
    fun onTasksAuthorized(token: String?) {
        if (token == null) {
            message = "Google ToDo リストに接続できませんでした"
            return
        }
        tasksConnecting = true
        viewModelScope.launch {
            val r = taskRepo.enableGoogle(token)
            tasksConnecting = false
            tasksGoogle = taskRepo.googleMode
            message = r.fold(
                { n -> if (n > 0) "Google ToDo リストと同期しました（端末のタスク${n}件を移動）" else "Google ToDo リストと同期しました" },
                { "Google ToDo リストに接続できませんでした：${it.message}" },
            )
            reload()
        }
    }

    fun disconnectTasks() {
        taskRepo.disableGoogle()
        tasksGoogle = false
        message = "タスクの同期を解除しました（以後は端末内に保存）"
    }

    fun move(dir: Int) {
        anchor = if (mode == ViewMode.WEEK) anchor.plusWeeks(dir.toLong())
        else anchor.withDayOfMonth(1).plusMonths(dir.toLong())
        reload()
    }

    fun goToday() {
        anchor = LocalDate.now()
        reload()
    }

    fun switchMode(m: ViewMode) {
        mode = m
        prefs.viewMode = m.name
        reload()
    }

    fun openWeekOf(date: LocalDate) {
        anchor = date
        switchMode(ViewMode.WEEK)
    }

    fun sync() {
        if (!hasPermission) {
            message = "カレンダーへのアクセスを許可してください"
            return
        }
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { calendarRepo.requestSync() }
            message = if (n > 0) "Googleカレンダーと同期しています…" else "端末にGoogleアカウントのカレンダーが見つかりません"
            reload()
        }
    }

    fun setDefaultCalendar(id: Long) {
        prefs.defaultCalendarId = id
        defaultCalendarId = id
    }

    fun defaultCalendar(): CalendarInfo? {
        val writable = calendars.filter { it.writable }
        return writable.firstOrNull { it.id == defaultCalendarId }
            ?: writable.firstOrNull { it.isGoogle && it.primary }
            ?: writable.firstOrNull { it.isGoogle }
            ?: writable.firstOrNull()
    }

    fun saveEvent(existing: CalEvent?, draft: EventDraft, calendarId: Long?) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                when {
                    existing != null -> calendarRepo.update(existing.eventId, draft)
                    calendarId != null -> calendarRepo.insert(calendarId, draft)
                    else -> false
                }
            }
            message = if (ok) null else "予定を保存できませんでした"
            reload()
            WidgetUpdater.refresh(getApplication())
        }
    }

    fun deleteEvent(ev: CalEvent) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { calendarRepo.delete(ev.eventId) }
            if (!ok) message = "予定を削除できませんでした"
            reload()
            WidgetUpdater.refresh(getApplication())
        }
    }

    // ---- タスク ----
    fun tasks(date: LocalDate): List<Task> = taskRepo.tasks(date)
    fun addTask(date: LocalDate, text: String) {
        if (!taskRepo.add(date, text)) message = "タスクは1日${TaskRepository.PER_DAY}件までです"
    }
    fun updateTask(date: LocalDate, id: String, text: String) = taskRepo.update(date, id, text)
    fun completeTask(date: LocalDate, id: String) = taskRepo.complete(date, id)

    override fun onCleared() {
        if (observerRegistered) getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}
