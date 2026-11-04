package jp.sekisho.todayshift

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.updateAll
import jp.sekisho.todayshift.widget.TodayRefreshWorker
import jp.sekisho.todayshift.widget.TodayWidget
import jp.sekisho.vertical.shift.ShiftParser
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ShiftRepository(app)

    var date by mutableStateOf(LocalDate.now()); private set
    var sheet by mutableStateOf(repo.cached()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var url by mutableStateOf(repo.url); private set
    var myName by mutableStateOf(repo.myName); private set
    var lastFetched by mutableStateOf(repo.lastFetched); private set

    /** 表示中の日の全員のシフト */
    val day: ShiftParser.DayView?
        get() = sheet?.let { ShiftParser.day(it.grid, it.roster, date) }

    /** 画面を開いたとき：日付を今日に戻し、10分以上たっていれば読み込み直す */
    fun onResume() {
        val today = LocalDate.now()
        if (lastOpenedDay != today) { date = today; lastOpenedDay = today }
        if (url.isNotBlank() && System.currentTimeMillis() - lastFetched > 10 * 60_000) refresh()
    }
    private var lastOpenedDay: LocalDate? = null

    fun refresh() {
        if (loading) return
        if (url.isBlank()) { error = "⚙ でシフト表の URL を入力してください"; return }
        loading = true
        viewModelScope.launch {
            runCatching { repo.fetch() }
                .onSuccess {
                    sheet = it; error = null; lastFetched = repo.lastFetched
                    // ウィジェットにも反映し、30分ごとの更新を登録
                    val ctx = getApplication<Application>()
                    runCatching { TodayWidget().updateAll(ctx) }
                    TodayRefreshWorker.schedule(ctx)
                }
                .onFailure { error = "シフト表を読み込めませんでした：${it.message ?: it.javaClass.simpleName}" }
            loading = false
        }
    }

    fun move(days: Long) { date = date.plusDays(days) }
    fun today() { date = LocalDate.now() }

    fun saveSettings(newUrl: String, newName: String) {
        val changed = newUrl.trim() != url
        repo.url = newUrl
        repo.myName = newName.ifBlank { "赤松" }
        url = repo.url
        myName = repo.myName
        if (changed) refresh()
    }
}
