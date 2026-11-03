package jp.sekisho.vertical.shift

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * シフト表（Google スプレッドシート「シフト表(NEW)」）から講師のコマを読み取る。
 * リンクを知っている人が閲覧できるシートを、ログインなしで HTML 表示（htmlview）から読む。
 * Windows 版（desktop/shift.js）と同じルール。
 */
object ShiftParser {
    data class Lesson(val date: LocalDate, val start: Int, val end: Int, val title: String)
    data class Other(val date: LocalDate, val start: Int, val end: Int, val title: String, val allDay: Boolean)
    data class Result(val lessons: List<Lesson>, val others: List<Other>, val notes: List<String>)

    /* ---------------- 取得 ---------------- */

    fun sheetId(urlOrId: String): String =
        Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)").find(urlOrId)?.groupValues?.get(1) ?: urlOrId.trim()

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("シフト表を読み込めませんでした ($code)")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** タブ名 → gid */
    private fun listTabs(id: String): Map<String, String> {
        val html = fetch("https://docs.google.com/spreadsheets/d/$id/htmlview")
        val re = Regex("""name: "((?:[^"\\]|\\.)*)", pageUrl: "[^"]*", gid: "(\d+)"""")
        return re.findAll(html).associate { m ->
            val name = runCatching { JSONObject("{\"n\":\"${m.groupValues[1]}\"}").getString("n") }.getOrDefault(m.groupValues[1])
            name to m.groupValues[2]
        }
    }

    private fun fetchGrid(id: String, gid: String) =
        parseTable(fetch("https://docs.google.com/spreadsheets/d/$id/htmlview/sheet?headers=false&gid=$gid"))

    fun load(url: String, teacher: String, today: LocalDate, tab: String = "シフト表(NEW)", rosterTab: String = "名簿"): Result {
        val id = sheetId(url)
        val tabs = listTabs(id)
        val gid = tabs[tab] ?: throw IOException("シフト表に「$tab」のタブが見つかりません")
        val roster = HashMap<String, String>()
        tabs[rosterTab]?.let { rg ->
            for (r in fetchGrid(id, rg)) {
                val g = r.getOrNull(0) ?: continue
                val n = r.getOrNull(1) ?: continue
                if (Regex("^(小|中|高)[1-6]$").matches(g) && n.isNotBlank()) roster[noSpace(n)] = g
            }
        }
        return extract(fetchGrid(id, gid), teacher, roster, today)
    }

    /* ---------------- HTML の表 → 2次元配列（結合セルは左上だけに値） ---------------- */

    private fun decode(s: String): String = s
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        .replace("&amp;", "&")

    private class Cell(var text: String, val cs: Int, val rs: Int, val th: Boolean)

    fun parseTable(html: String): List<List<String>> {
        val start = html.indexOf("<table")
        val body = if (start >= 0) html.substring(start) else html
        val rows = mutableListOf<MutableList<Cell>>()
        var row: MutableList<Cell>? = null
        var cell: Cell? = null
        val re = Regex("""<(/?)(tr|td|th|br|table)\b([^>]*)>|<[^>]*>|([^<]+)""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(body)) {
            val text = m.groups[4]?.value
            if (text != null) { cell?.let { it.text += decode(text) }; continue }
            val close = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            when {
                tag == "table" && close -> break
                tag == "tr" && !close -> { row = mutableListOf(); rows += row!! }
                (tag == "td" || tag == "th") && !close -> {
                    fun num(k: String) = Regex("$k=\"?(\\d+)").find(attrs)?.groupValues?.get(1)?.toInt() ?: 1
                    cell = Cell("", num("colspan"), num("rowspan"), tag == "th")
                }
                (tag == "td" || tag == "th") && close -> { cell?.let { row?.add(it) }; cell = null }
                tag == "br" -> cell?.let { it.text += "\n" }
            }
        }
        val grid = mutableListOf<List<String>>()
        val taken = HashSet<Long>()
        fun key(r: Int, c: Int) = r.toLong() * 100_000 + c
        rows.forEachIndexed { ri, r ->
            val line = mutableListOf<String>()
            var c = 0
            for (x in r.filter { !it.th }) { // 行番号（th）は除く
                while (key(ri, c) in taken) { line += ""; c++ }
                line += x.text.trim()
                repeat(x.cs - 1) { line += "" }
                for (rr in 1 until x.rs) for (cc in 0 until x.cs) taken += key(ri + rr, c + cc)
                c += x.cs
            }
            while (key(ri, c) in taken) { line += ""; c++ }
            grid += line
        }
        return grid
    }

    /* ---------------- 読み取りルール ---------------- */

    private val GRADE_RE = Regex("^(小|中|高)\\s*([1-6１-６])(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val TIME_RE = Regex("(\\d{1,2})[:：](\\d{2})")
    private val PAREN_RE = Regex("[（(][^）)]*[）)]")
    private val SUBJECTS = mapOf(
        "数学" to "数", "算数" to "算", "英語" to "英", "理科" to "理", "国語" to "国", "社会" to "社",
        "数" to "数", "算" to "算", "英" to "英", "理" to "理", "国" to "国", "社" to "社",
    )

    private fun toHalf(s: String) = buildString { s.forEach { append(if (it in '０'..'９') '0' + (it - '０') else it) } }
    fun noSpace(s: String) = s.replace(Regex("[\\s　]"), "")
    private fun minutes(t: String): Int? = TIME_RE.find(toHalf(t))?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
    private fun stripParen(s: String) = PAREN_RE.replace(s, "").trim()
    private fun gradeOf(t: String): String? = GRADE_RE.find(toHalf(t))?.let { it.groupValues[1] + it.groupValues[2] }

    /** 科目 → 略称（「英語・理科」→「英・理」）。科目でなければ null */
    fun subjectAbbr(text: String): String? {
        val parts = stripParen(text).split(Regex("[・/／,、]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || !parts.all { it in SUBJECTS }) return null
        return parts.joinToString("・") { SUBJECTS.getValue(it) }
    }

    /** 名字（「竹内　咲希」→「竹内」、「山本パガロビン」→「山本」） */
    fun surname(name: String): String {
        val sp = name.trim().split(Regex("[\\s　]+"))
        if (sp.size > 1) return sp[0]
        val m = Regex("^[一-鿿々]+").find(name)
        return if (m != null && m.value.length < name.length) m.value else name.trim()
    }

    /** 「11/2」→ 今日に一番近い年の日付 */
    private fun resolveDate(md: String, today: LocalDate): LocalDate? {
        val m = Regex("^(\\d{1,2})/(\\d{1,2})$").find(md.trim()) ?: return null
        return (today.year - 1..today.year + 1).mapNotNull {
            runCatching { LocalDate.of(it, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
        }.minByOrNull { abs(ChronoUnit.DAYS.between(today, it)) }
    }

    private data class Student(val grade: String?, val name: String, val subject: String?)
    private class Slot {
        val students = mutableListOf<Student>()
        var group: String? = null
        val freeText = mutableListOf<String>()
        val others = mutableListOf<Pair<Int?, String>>()
        var override: Int? = null
    }

    private fun readSlot(cells: List<List<String>>, roster: Map<String, String>): Slot {
        val out = Slot()
        fun kind(t: String): String = when {
            t.isBlank() -> "empty"
            t.contains("様") -> "guest"
            GRADE_RE.find(toHalf(t))?.let { PAREN_RE.replace(it.groupValues[3], "").isBlank() } == true -> "grade"
            subjectAbbr(t) != null -> "subject"
            Regex("^[\\d:：\\s～~\\-]+$").matches(toHalf(t)) && TIME_RE.containsMatchIn(toHalf(t)) -> "time"
            roster.containsKey(noSpace(t)) || Regex("[\\s　]").containsMatchIn(t.trim()) -> "name"
            else -> "text"
        }
        val k = cells.map { r -> r.map { kind(it) } }

        // 時刻指定（「中2(15:10)」「数学(16:05～)」）
        for (r in cells) for (t in r) {
            val p = Regex("[（(]\\s*(\\d{1,2}[:：]\\d{2})").find(t)
            if (p != null && out.override == null) out.override = minutes(p.groupValues[1])
        }
        // 授業以外（「11:00」＋「中3竹内様」）
        for (i in 0..2) for (j in 0..1) {
            if (k[i][j] == "guest") out.others += minutes(cells[i][1 - j]) to cells[i][j].trim()
        }
        // 生徒（名前のセルごとに、同じ列の上の学年・下の科目、なければ隣の列の同じ行の科目）
        for (j in 0..1) for (i in 0..2) {
            if (k[i][j] != "name") continue
            val name = cells[i][j].trim()
            var grade: String? = null
            for (x in i - 1 downTo 0) if (k[x][j] == "grade") { grade = gradeOf(cells[x][j]); break }
            if (grade == null) grade = roster[noSpace(name)]
            var subject: String? = null
            for (x in i + 1..2) if (k[x][j] == "subject") { subject = subjectAbbr(cells[x][j]); break }
            if (subject == null && k[i][1 - j] == "subject") subject = subjectAbbr(cells[i][1 - j])
            out.students += Student(grade, name, subject)
        }
        // 集団授業（「中1」＋「数学」で名前なし）
        if (out.students.isEmpty()) {
            for (j in 0..1) {
                if (out.group != null) break
                val g = (0..2).firstOrNull { k[it][j] == "grade" } ?: continue
                val s = (g + 1..2).firstOrNull { k[it][j] == "subject" } ?: continue
                out.group = gradeOf(cells[g][j]) + stripParen(cells[s][j])
            }
        }
        // それ以外の書き込み（「指定校コース」など）
        if (out.students.isEmpty() && out.group == null) {
            for (i in 0..2) for (j in 0..1) if (k[i][j] == "text") out.freeText += cells[i][j].trim()
        }
        return out
    }

    /**
     * 講師のコマを読み取る。時刻は手帳の予定に合わせる：開始は「16:05→16:00」のように丸め、
     * 小学生だけのコマは60分（平日16:05の枠は16:30〜）、それ以外は90分。
     */
    fun extract(grid0: List<List<String>>, teacher: String, roster: Map<String, String>, today: LocalDate): Result {
        val w = grid0.maxOfOrNull { it.size } ?: 0
        val g = grid0.map { it + List(w - it.size) { "" } }
        val lessons = mutableListOf<Lesson>()
        val others = mutableListOf<Other>()
        val notes = mutableListOf<String>()
        val dateRe = Regex("^\\d{1,2}/\\d{1,2}$")
        for (top in 0 until g.size - 1) {
            if (!dateRe.matches(g[top].getOrElse(1) { "" })) continue
            for (d in 0 until 7) {
                val tc = 15 * d
                if (tc + 15 > w) break
                val date = resolveDate(g[top][tc + 1], today) ?: continue
                val c = (tc + 1 until tc + 15).firstOrNull { g[top + 1][it].trim() == teacher } ?: continue
                val weekday = date.dayOfWeek.value in 1..5
                for (off in 2..23 step 3) {
                    val l = top + off
                    if (l + 2 >= g.size) break
                    val cells = (0..2).map { i -> listOf(g[l + i][c], g[l + i].getOrElse(c + 1) { "" }) }
                    if (cells.flatten().all { it.isBlank() }) continue
                    val label = g[l][tc]
                    val slot = readSlot(cells, roster)

                    for ((t0, title) in slot.others) {
                        val t = t0 ?: minutes(label)
                        if (t == null) { notes += "$date $title"; continue }
                        others += Other(date, t, t + 60, title, false)
                    }
                    var elem = false
                    val title: String? = when {
                        slot.students.isNotEmpty() -> {
                            elem = slot.students.all { (it.grade ?: "").startsWith("小") }
                            slot.students.joinToString("/") { s ->
                                (s.grade ?: "") + surname(s.name) + (s.subject?.let { "($it)" } ?: "")
                            }
                        }
                        slot.group != null -> { elem = slot.group!!.startsWith("小"); slot.group }
                        slot.freeText.isNotEmpty() && slot.others.isEmpty() -> slot.freeText.joinToString(" ")
                        else -> null
                    }
                    if (title == null) {
                        if (slot.others.isEmpty()) notes += "$date ${cells.flatten().filter { it.isNotBlank() }.joinToString(" / ")}"
                        continue
                    }
                    // 時刻のない書き込み（日曜の「テスト対策」など）は、終日の予定（授業以外）
                    if (slot.freeText.isNotEmpty() && minutes(label) == null && slot.override == null) {
                        others += Other(date, 0, 0, title, true)
                        continue
                    }
                    // 開始時刻：「10:00(小) 10:30(中)」のような枠は小学生かどうかで選ぶ
                    val times = TIME_RE.findAll(toHalf(label)).map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }.toList()
                    var start = slot.override ?: (if (times.size > 1 && label.contains("中") && !elem) times[1] else times.firstOrNull())
                    if (start == null) { notes += "$date $title"; continue }
                    if (start % 30 == 5) start -= 5 // 16:05 → 16:00
                    if (weekday && elem && start == 16 * 60 && slot.override == null) start = 16 * 60 + 30
                    lessons += Lesson(date, start, minOf(start + if (elem) 60 else 90, 24 * 60), title)
                }
            }
        }
        return Result(lessons, others, notes)
    }
}
