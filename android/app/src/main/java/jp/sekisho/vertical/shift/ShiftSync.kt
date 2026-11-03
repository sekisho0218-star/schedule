package jp.sekisho.vertical.shift

import android.content.Context
import jp.sekisho.vertical.data.CalEvent
import jp.sekisho.vertical.data.CalendarRepository
import jp.sekisho.vertical.data.DataSignals
import jp.sekisho.vertical.data.Dates
import jp.sekisho.vertical.data.EventDraft
import jp.sekisho.vertical.data.Prefs
import jp.sekisho.vertical.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * シフト表と端末のカレンダー（＝Google カレンダー）を照合して反映する。
 *  - 授業：自動で追加・変更・削除（初回だけは確認してから）
 *  - 授業以外（「中3竹内様」など）と、シフト表にない予定：確認画面で選んで反映
 * Windows 版（desktop/shiftsync.js）と同じルール。
 */
class ShiftSync(context: Context) {
    companion object {
        /** シフト表から追加した予定のメモ（一括削除はこの印の付いた予定だけが対象） */
        const val MARK_ADDED = "シフト表から自動登録（バーチカル手帳）"
    }

    private val app = context.applicationContext
    private val prefs = Prefs(app)
    private val cal = CalendarRepository(app)

    /** 1件の変更（追加・変更・削除） */
    data class Op(
        val op: String, // add / update / delete
        val desc: String,
        val calendarId: Long,
        val date: LocalDate,
        val start: Int,
        val end: Int,
        val title: String,
        val allDay: Boolean,
        val eventId: Long = -1,
        val begin: Long = 0,
        val series: Boolean = false,
        /** 変更するときに残す、元の予定のメモ */
        val note: String = "",
    )

    data class Proposal(val key: String, val type: String, val desc: String, val checked: Boolean, val op: Op)

    data class Pending(val lessons: List<Op>, val proposals: List<Proposal>, val notes: List<String>) {
        val isEmpty get() = lessons.isEmpty() && proposals.isEmpty()
    }

    data class RunResult(val applied: List<String>, val failed: List<String>, val pending: Pending)

    /* ---------------- 照合して反映 ---------------- */

    suspend fun run(): RunResult = withContext(Dispatchers.IO) {
        if (prefs.shiftUrl.isBlank()) throw IOException("設定でシフト表の URL を入力してください")
        if (!cal.hasWritePermission()) throw IOException("カレンダーへのアクセスを許可してください")
        val diff = compare()
        val dismissed = prefs.shiftDismissed
        val proposals = diff.proposals.filter { it.key !in dismissed }
        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var pendingLessons = emptyList<Op>()
        if (prefs.shiftConfirmedOnce) {
            for (op in diff.lessons) if (apply(op)) applied += op.desc else failed += op.desc
        } else {
            pendingLessons = diff.lessons // 初回は授業の変更も確認してから
        }
        val pending = Pending(pendingLessons, proposals, diff.notes)
        prefs.shiftPending = toJson(pending)
        prefs.shiftLastRun = System.currentTimeMillis()
        prefs.shiftLastMessage = buildList {
            if (applied.isNotEmpty()) add("授業を${applied.size}件反映")
            if (!pending.isEmpty) add("確認待ち${pending.lessons.size + pending.proposals.size}件")
            if (failed.isNotEmpty()) add("反映できなかった予定${failed.size}件")
            if (isEmpty()) add("違いはありません")
        }.joinToString("・")
        if (applied.isNotEmpty()) {
            DataSignals.bump()
            WidgetUpdater.refresh(app)
        }
        RunResult(applied, failed, pending)
    }

    /** 確認待ちの内容 */
    fun pending(): Pending = fromJson(prefs.shiftPending)

    /** 確認画面の結果を反映。チェックしなかった項目は次回から聞かない */
    suspend fun applyChoices(applyLessons: Boolean, keys: Set<String>): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val p = pending()
        prefs.shiftPending = ""
        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        if (applyLessons && p.lessons.isNotEmpty()) {
            for (op in p.lessons) if (apply(op)) applied += op.desc else failed += op.desc
            prefs.shiftConfirmedOnce = true
        }
        for (pr in p.proposals) {
            if (pr.key !in keys) continue
            if (apply(pr.op)) applied += pr.desc else failed += pr.desc
        }
        prefs.shiftDismissed = (prefs.shiftDismissed + p.proposals.filter { it.key !in keys }.map { it.key }).toList().takeLast(1000).toSet()
        DataSignals.bump()
        WidgetUpdater.refresh(app)
        applied to failed
    }

    private fun apply(op: Op): Boolean {
        val draft = EventDraft(
            title = op.title,
            // 追加した予定には印を付ける（一括削除の対象）。変更した予定は元のメモのまま
            description = if (op.op == "add") MARK_ADDED else op.note,
            allDay = op.allDay,
            startDate = op.date,
            startTime = time(op.start),
            endDate = op.date,
            endTime = time(op.end),
        )
        val ev = CalEvent(op.eventId, op.title, "", op.begin, op.begin, op.allDay, 0, op.calendarId, op.series, series = op.series)
        return when (op.op) {
            "add" -> cal.insert(op.calendarId, draft)
            "update" -> cal.updateInstance(ev, draft)
            "delete" -> cal.deleteInstance(ev)
            else -> false
        }
    }

    private fun time(min: Int): LocalTime = if (min >= 24 * 60) LocalTime.of(23, 59) else LocalTime.of(min / 60, min % 60)

    /* ---------------- 照合 ---------------- */

    private data class Diff(val lessons: List<Op>, val proposals: List<Proposal>, val notes: List<String>)

    private val lessonTitle = Regex("^(小|中|高)\\s*[1-6１-６]")
    private fun isLesson(ev: CalEvent) = !ev.allDay &&
        ((lessonTitle.containsMatchIn(ev.title) && !ev.title.contains("様")) || ev.title.contains("指定校"))

    private fun localDate(ev: CalEvent): LocalDate =
        if (ev.allDay) Dates.utcDate(ev.begin) else Instant.ofEpochMilli(ev.begin).atZone(Dates.zone).toLocalDate()

    private fun minOfDay(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(Dates.zone).toLocalTime().let { it.hour * 60 + it.minute }

    private fun hm(m: Int) = "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    private fun dayLabel(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}(${Dates.dowJa(d)})"

    private data class Stu(val base: String, val subj: String)

    /** 授業名を生徒ごとに分ける（「中2奥中(数)」→ 中2奥中 / 数） */
    private fun students(title: String) = ShiftParser.noSpace(title).split("/").filter { it.isNotEmpty() }.map { p ->
        Regex("^(.*?)[（(]([^）)]*)[）)]$").find(p)?.let { Stu(it.groupValues[1], it.groupValues[2]) } ?: Stu(p, "")
    }

    /** 同じ授業か（並び順・空白の違いは無視。科目はどちらかが空なら一致とみなす） */
    private fun sameLesson(a: String, b: String): Boolean {
        val x = students(a)
        val y = students(b)
        return x.size == y.size && x.all { s -> y.any { it.base == s.base && (s.subj.isEmpty() || it.subj.isEmpty() || it.subj == s.subj) } }
    }

    /** シフト表で科目が空欄の生徒は、カレンダーの予定の科目を引き継ぐ */
    private fun mergeTitle(want: String, have: String): String {
        val h = students(have)
        return students(want).joinToString("/") { s ->
            val subj = s.subj.ifEmpty { h.firstOrNull { it.base == s.base }?.subj ?: "" }
            if (subj.isNotEmpty()) "${s.base}($subj)" else s.base
        }
    }

    private fun compare(): Diff {
        val today = LocalDate.now()
        val shift = ShiftParser.load(prefs.shiftUrl, prefs.shiftTeacher.ifBlank { "赤松" }, today)
        val lessons = shift.lessons.filter { !it.date.isBefore(today) }
        val others = shift.others.filter { !it.date.isBefore(today) }
        if (lessons.isEmpty() && others.isEmpty()) return Diff(emptyList(), emptyList(), shift.notes)
        val last = (lessons.map { it.date } + others.map { it.date }).max()

        val calendars = cal.calendars()
        val calId = prefs.shiftCalendarId.takeIf { id -> id >= 0 && calendars.any { it.id == id && it.writable } }
            ?: cal.defaultCalendar(prefs.defaultCalendarId)?.id
            ?: throw IOException("書き込めるカレンダーがありません")
        val writable = calendars.filter { it.writable }.map { it.id }.toSet()
        val events = cal.instances(today, last.plusDays(1)).filter { val d = localDate(it); !d.isBefore(today) && !d.isAfter(last) }

        /* ---- 授業 ---- */
        val ops = mutableListOf<Op>()
        val dates = (lessons.map { it.date } + events.filter { !it.allDay }.map { localDate(it) }).toSortedSet()
        for (date in dates) {
            val want = lessons.filter { it.date == date }
            val have = events.filter { it.calendarId == calId && it.calendarId in writable && isLesson(it) && localDate(it) == date }.toMutableList()
            val rest = mutableListOf<ShiftParser.Lesson>()
            // 1) 時刻も生徒も同じ → そのまま
            for (w in want) {
                val i = have.indexOfFirst { sameLesson(it.title, w.title) && minOfDay(it.begin) == w.start && minOfDay(it.end) == w.end }
                if (i >= 0) have.removeAt(i) else rest += w
            }
            // 2) 「開始が同じ」「重なる時間が長い」順に組み合わせて変更（組み合わせのないものは追加）
            data class Pair3(val wi: Int, val hi: Int, val score: Int)
            val pairs = mutableListOf<Pair3>()
            rest.forEachIndexed { wi, w ->
                have.forEachIndexed { hi, h ->
                    val hs = minOfDay(h.begin)
                    val he = minOfDay(h.end).let { if (it <= hs) 24 * 60 else it }
                    val overlap = minOf(he, w.end) - maxOf(hs, w.start)
                    if (overlap > 0) pairs += Pair3(wi, hi, (if (hs == w.start) 10000 else 0) + (if (sameLesson(h.title, w.title)) 5000 else 0) + overlap)
                }
            }
            val usedW = HashSet<Int>()
            val usedH = HashSet<Int>()
            for (p in pairs.sortedByDescending { it.score }) {
                if (p.wi in usedW || p.hi in usedH) continue
                usedW += p.wi; usedH += p.hi
                val w = rest[p.wi]
                val h = have[p.hi]
                val title = mergeTitle(w.title, h.title)
                if (title == h.title && minOfDay(h.begin) == w.start && minOfDay(h.end) == w.end) continue
                ops += Op("update", "${dayLabel(date)} ${hm(minOfDay(h.begin))}–${hm(minOfDay(h.end))} ${h.title} → ${hm(w.start)}–${hm(w.end)} $title",
                    calId, date, w.start, w.end, title, false, h.eventId, h.begin, h.series, h.description)
            }
            rest.forEachIndexed { wi, w ->
                if (wi !in usedW) ops += Op("add", "${dayLabel(date)} ${hm(w.start)}–${hm(w.end)} ${w.title}", calId, date, w.start, w.end, w.title, false)
            }
            have.forEachIndexed { hi, h ->
                if (hi !in usedH) ops += Op("delete", "${dayLabel(date)} ${hm(minOfDay(h.begin))}–${hm(minOfDay(h.end))} ${h.title}",
                    h.calendarId, date, 0, 0, h.title, false, h.eventId, h.begin, h.series)
            }
        }

        /* ---- 授業以外：シフト表にあってカレンダーにないもの ---- */
        val proposals = mutableListOf<Proposal>()
        for (o in others) {
            val exists = events.any { localDate(it) == o.date && ShiftParser.noSpace(it.title) == ShiftParser.noSpace(o.title) }
            if (exists) continue
            val whenText = if (o.allDay) "終日" else "${hm(o.start)}–${hm(o.end)}"
            val key = "add|${o.date}|${if (o.allDay) "allday" else o.start}|${ShiftParser.noSpace(o.title)}"
            proposals += Proposal(key, "add", "${dayLabel(o.date)} $whenText ${o.title}", true,
                Op("add", "", calId, o.date, o.start, o.end, o.title, o.allDay))
        }

        /* ---- シフト表にない予定（反映先カレンダーの、授業以外の予定） ---- */
        for (e in events) {
            if (e.calendarId != calId || e.calendarId !in writable || isLesson(e)) continue
            val date = localDate(e)
            if (others.any { it.date == date && ShiftParser.noSpace(it.title) == ShiftParser.noSpace(e.title) }) continue
            val whenText = if (e.allDay) "終日" else "${hm(minOfDay(e.begin))}–${hm(minOfDay(e.end))}"
            proposals += Proposal("rm|${e.eventId}|${e.begin}", "remove", "${dayLabel(date)} $whenText ${e.title}", false,
                Op("delete", "", e.calendarId, date, 0, 0, e.title, e.allDay, e.eventId, e.begin, e.series))
        }
        return Diff(ops, proposals, shift.notes)
    }

    /* ---------------- シフト表から追加した予定の一括削除 ---------------- */

    /** 一括削除の候補：シフト表から追加した予定（duplicate = 同じ時間に別の同じ授業がある） */
    data class Synced(val ev: CalEvent, val calendarName: String, val duplicate: Boolean, val desc: String)

    /** シフト表から追加した予定（30日前〜120日後）を一覧にする */
    suspend fun listSynced(): List<Synced> = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val calendars = cal.calendars().associateBy { it.id }
        val events = cal.instances(today.minusDays(30), today.plusDays(120))
        val mine = events.filter { it.description.contains(MARK_ADDED) && calendars[it.calendarId]?.writable == true }
        mine.map { e ->
            val date = localDate(e)
            val dup = events.any { o ->
                o !== e && !o.description.contains(MARK_ADDED) && localDate(o) == date &&
                    o.begin < e.end && o.end > e.begin &&
                    (sameLesson(o.title, e.title) || ShiftParser.noSpace(o.title) == ShiftParser.noSpace(e.title))
            }
            val whenText = if (e.allDay) "終日" else "${hm(minOfDay(e.begin))}–${hm(minOfDay(e.end))}"
            Synced(e, calendars[e.calendarId]?.name ?: "", dup, "${dayLabel(date)} $whenText ${e.title}")
        }.sortedBy { it.ev.begin }
    }

    /** 選んだ予定を削除。削除後は、次の照合で授業の変更をもう一度確認する */
    suspend fun deleteSynced(list: List<CalEvent>): Int = withContext(Dispatchers.IO) {
        val n = list.count { cal.deleteInstance(it) }
        prefs.shiftConfirmedOnce = false
        prefs.shiftPending = ""
        DataSignals.bump()
        WidgetUpdater.refresh(app)
        n
    }

    /* ---------------- 確認待ちの保存（JSON） ---------------- */

    private fun opJson(o: Op) = JSONObject()
        .put("op", o.op).put("desc", o.desc).put("cal", o.calendarId).put("date", o.date.toString())
        .put("start", o.start).put("end", o.end).put("title", o.title).put("allDay", o.allDay)
        .put("eventId", o.eventId).put("begin", o.begin).put("series", o.series).put("note", o.note)

    private fun opOf(j: JSONObject) = Op(
        j.getString("op"), j.getString("desc"), j.getLong("cal"), LocalDate.parse(j.getString("date")),
        j.getInt("start"), j.getInt("end"), j.getString("title"), j.getBoolean("allDay"),
        j.getLong("eventId"), j.getLong("begin"), j.getBoolean("series"), j.optString("note", ""),
    )

    private fun toJson(p: Pending): String = JSONObject()
        .put("lessons", JSONArray().apply { p.lessons.forEach { put(opJson(it)) } })
        .put("proposals", JSONArray().apply {
            p.proposals.forEach {
                put(JSONObject().put("key", it.key).put("type", it.type).put("desc", it.desc).put("checked", it.checked).put("op", opJson(it.op)))
            }
        })
        .put("notes", JSONArray(p.notes))
        .toString()

    private fun fromJson(s: String): Pending = runCatching {
        val j = JSONObject(s)
        val ls = j.getJSONArray("lessons")
        val ps = j.getJSONArray("proposals")
        val ns = j.optJSONArray("notes") ?: JSONArray()
        Pending(
            (0 until ls.length()).map { opOf(ls.getJSONObject(it)) },
            (0 until ps.length()).map {
                val o = ps.getJSONObject(it)
                Proposal(o.getString("key"), o.getString("type"), o.getString("desc"), o.getBoolean("checked"), opOf(o.getJSONObject("op")))
            },
            (0 until ns.length()).map { ns.getString(it) },
        )
    }.getOrDefault(Pending(emptyList(), emptyList(), emptyList()))
}
