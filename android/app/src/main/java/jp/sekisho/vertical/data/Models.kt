package jp.sekisho.vertical.data

import java.time.LocalDate
import java.time.LocalTime

/** カレンダー予定（CalendarContract.Instances の1件） */
data class CalEvent(
    val eventId: Long,
    val title: String,
    val description: String,
    /** エポックミリ秒。終日予定は UTC 0:00 基準 */
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val calendarId: Long,
    val recurring: Boolean,
    /** 時刻付きタスクをスケジュールに表示するとき：タスクのID と日付 */
    val taskId: String? = null,
    val taskDate: LocalDate? = null,
)

data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val writable: Boolean,
    val primary: Boolean,
) {
    val isGoogle get() = accountType == "com.google"
}

data class Task(val id: String, val text: String)

/** 予定の入力内容（ダイアログ → 保存） */
data class EventDraft(
    val title: String,
    val description: String,
    val allDay: Boolean,
    val startDate: LocalDate,
    val startTime: LocalTime,
    /** 終日予定の場合は最終日（含む） */
    val endDate: LocalDate,
    val endTime: LocalTime,
)

/** 1日の時間軸上に置く予定。分は 0:00 からの経過分（日をまたぐ予定は切り詰め済み） */
data class TimedSlot(
    val event: CalEvent,
    val startMin: Int,
    val endMin: Int,
    val col: Int = 0,
    val ncols: Int = 1,
)

data class DayEvents(val allDay: List<CalEvent>, val timed: List<TimedSlot>)
