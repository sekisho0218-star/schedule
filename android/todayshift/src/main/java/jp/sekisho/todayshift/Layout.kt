package jp.sekisho.todayshift

import jp.sekisho.vertical.shift.ShiftParser
import kotlin.math.ceil
import kotlin.math.max

/** アプリとウィジェットで共通の並べ方 */
object Layout {
    /**
     * 幅に全員が入るよう、講師を何段に分けるか。
     * @return (1段あたりの人数, 段数)。段ごとの人数はなるべく同じにする
     */
    fun tiers(teachers: Int, widthDp: Float, minColDp: Float): Pair<Int, Int> {
        val n = max(1, teachers)
        val fit = max(1, (widthDp / minColDp).toInt())
        val tiers = ceil(n / fit.toDouble()).toInt()
        val per = ceil(n / tiers.toDouble()).toInt()
        return per to tiers
    }

    /** 今の時間帯（開始が今以前で、次の時間帯の開始より前）の行番号 */
    fun nowRow(day: ShiftParser.DayView, nowMin: Int): Int? {
        val starts = day.rows.mapIndexedNotNull { i, r -> r.startMin?.let { i to it } }
        val (i, s) = starts.lastOrNull { it.second <= nowMin } ?: return null
        val next = starts.firstOrNull { it.first > i }?.second ?: (s + 90)
        return if (nowMin < next) i else null
    }

    /** 「坪本　光人」→「坪本 光人」（全角の空白は幅を取るので半角に） */
    fun displayName(name: String) = name.trim().replace(Regex("[\\s　]+"), " ")

    /** ウィジェット用の1行（「中2坪本(数)」） */
    fun shortLines(c: ShiftParser.CellView): List<String> = buildList {
        c.students.forEach { s ->
            val subj = s.subject?.let { ShiftParser.subjectAbbr(it) }
            add((s.grade ?: "") + ShiftParser.surname(s.name) + (subj?.let { "($it)" } ?: ""))
        }
        c.group?.let { add(it.replace(" ", "") + "(集団)") }
        addAll(c.texts)
        c.guests.forEach { (t, title) -> add(listOfNotNull(t, title).joinToString(" ")) }
    }
}
