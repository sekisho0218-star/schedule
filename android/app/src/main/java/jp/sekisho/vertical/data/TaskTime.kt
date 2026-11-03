package jp.sekisho.vertical.data

/**
 * タスク名の先頭の時刻を読み取る。
 * Google Tasks API はタスクの時刻を扱えない（期限は日付のみ）ため、
 * 「15:00 教材発注」「15:00-16:30 面談準備」のように名前の先頭に書いた時刻を使う。
 */
object TaskTime {
    /** 時刻が1つだけのときの長さ（分） */
    private const val DEFAULT_MINUTES = 30

    private val RE = Regex("""^\s*(\d{1,2})[:：](\d{2})(?:\s*[-~〜～–ー−]\s*(\d{1,2})[:：](\d{2}))?\s*(.*)$""")

    data class Parsed(val startMin: Int, val endMin: Int, val title: String)

    fun parse(text: String): Parsed? {
        val m = RE.find(toHalfWidth(text)) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        val start = h * 60 + mi
        var end = if (m.groupValues[3].isNotEmpty()) {
            val eh = m.groupValues[3].toInt()
            val em = m.groupValues[4].toInt()
            if (eh > 24 || em > 59) start + DEFAULT_MINUTES else eh * 60 + em
        } else {
            start + DEFAULT_MINUTES
        }
        if (end <= start) end = start + DEFAULT_MINUTES
        return Parsed(start, minOf(end, 24 * 60), m.groupValues[5].ifBlank { "(タスク)" })
    }

    fun isTimed(text: String) = parse(text) != null

    /** 全角数字を半角に */
    private fun toHalfWidth(s: String) = buildString {
        s.forEach { ch -> append(if (ch in '０'..'９') ('0' + (ch - '０')) else ch) }
    }
}

/** 時刻付きタスクの表示色（温かみのある灰色） */
const val TASK_COLOR = 0xFF8D8778.toInt()

/** 時刻付きタスクをスケジュールに並べるための予定に変換 */
fun taskEvents(date: java.time.LocalDate, tasks: List<Task>): List<CalEvent> = tasks.mapNotNull { t ->
    val p = TaskTime.parse(t.text) ?: return@mapNotNull null
    val base = Dates.startMillis(date)
    CalEvent(
        eventId = -1,
        title = "☐ " + p.title,
        description = "",
        begin = base + p.startMin * 60_000L,
        end = base + p.endMin * 60_000L,
        allDay = false,
        color = TASK_COLOR,
        calendarId = -1,
        recurring = false,
        taskId = t.id,
        taskDate = date,
    )
}

/** タスクリストに表示する（時刻のない）タスク */
fun untimedTasks(tasks: List<Task>): List<Task> = tasks.filterNot { TaskTime.isTimed(it.text) }
