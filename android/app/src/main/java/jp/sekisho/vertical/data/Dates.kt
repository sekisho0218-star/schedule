package jp.sekisho.vertical.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

enum class DayKind { WEEKDAY, SATURDAY, SUNDAY }

object Dates {
    const val START_HOUR = 8
    const val END_HOUR = 24
    const val START_MIN = START_HOUR * 60
    const val END_MIN = END_HOUR * 60
    const val RANGE_MIN = END_MIN - START_MIN

    private val DOW_JA = arrayOf("月", "火", "水", "木", "金", "土", "日")

    val zone: ZoneId get() = ZoneId.systemDefault()

    /** 月曜始まりの週の初日 */
    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 月間表示の左上（その月1日を含む週の月曜） */
    fun monthGridStart(d: LocalDate): LocalDate = weekStart(d.withDayOfMonth(1))

    fun kind(d: LocalDate): DayKind = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> DayKind.SATURDAY
        DayOfWeek.SUNDAY -> DayKind.SUNDAY
        else -> DayKind.WEEKDAY
    }

    fun dowJa(d: LocalDate): String = DOW_JA[d.dayOfWeek.value - 1]

    private val DOW_EN = arrayOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    fun dowEn(d: LocalDate): String = DOW_EN[d.dayOfWeek.value - 1]

    fun startMillis(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 終日予定のミリ秒（UTC 0:00）→ 日付 */
    fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    fun weekTitle(weekStart: LocalDate): String {
        val end = weekStart.plusDays(6)
        return if (weekStart.month == end.month) {
            "${weekStart.year}年${weekStart.monthValue}月 ${weekStart.dayOfMonth}–${end.dayOfMonth}日"
        } else {
            "${weekStart.year}年${weekStart.monthValue}/${weekStart.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
        }
    }

    fun monthTitle(d: LocalDate): String = "${d.year}年${d.monthValue}月"
}

/** 指定日の予定を終日／時間指定に分け、重なりを考慮した列を割り当てる */
fun eventsForDay(events: List<CalEvent>, date: LocalDate): DayEvents {
    val dayStart = Dates.startMillis(date)
    val dayEnd = Dates.startMillis(date.plusDays(1))
    val allDay = mutableListOf<CalEvent>()
    val timed = mutableListOf<TimedSlot>()
    for (ev in events) {
        if (ev.allDay) {
            val s = Dates.utcDate(ev.begin)
            var e = Dates.utcDate(ev.end)
            if (!e.isAfter(s)) e = s.plusDays(1)
            if (!date.isBefore(s) && date.isBefore(e)) allDay += ev
            continue
        }
        if (ev.end <= dayStart || ev.begin >= dayEnd) continue
        val s = ((maxOf(ev.begin, dayStart) - dayStart) / 60_000).toInt()
        val e = ((minOf(ev.end, dayEnd) - dayStart) / 60_000).toInt()
        timed += TimedSlot(ev, s, maxOf(e, s + 15))
    }
    allDay.sortBy { it.title }
    timed.sortWith(compareBy<TimedSlot> { it.startMin }.thenByDescending { it.endMin })
    return DayEvents(allDay, layoutColumns(timed))
}

/** 重なっている予定のまとまりごとに列番号と列数を決める */
private fun layoutColumns(items: List<TimedSlot>): List<TimedSlot> {
    val result = mutableListOf<TimedSlot>()
    var cluster = mutableListOf<TimedSlot>()
    var colEnds = mutableListOf<Int>()
    var clusterEnd = -1

    fun flush() {
        val n = colEnds.size.coerceAtLeast(1)
        cluster.forEach { result += it.copy(ncols = n) }
        cluster = mutableListOf()
        colEnds = mutableListOf()
    }

    for (it in items) {
        if (cluster.isNotEmpty() && it.startMin >= clusterEnd) flush()
        var col = colEnds.indexOfFirst { end -> end <= it.startMin }
        if (col == -1) {
            col = colEnds.size
            colEnds += it.endMin
        } else {
            colEnds[col] = it.endMin
        }
        cluster += it.copy(col = col)
        clusterEnd = if (cluster.size == 1) it.endMin else maxOf(clusterEnd, it.endMin)
    }
    if (cluster.isNotEmpty()) flush()
    return result
}
