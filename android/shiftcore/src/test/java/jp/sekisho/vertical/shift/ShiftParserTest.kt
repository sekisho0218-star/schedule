package jp.sekisho.vertical.shift

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * シフト表の読み取りが Windows 版（desktop/shift.js）と同じ結果になることを確かめる。
 * テスト用のシフト表（架空の名前・本物と同じ並び）と期待値は desktop/shift.js で作成したもの。
 */
class ShiftParserTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText(Charsets.UTF_8)

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n' -> append('\n'); 't' -> append('\t'); else -> append(s[i + 1]) }
                i += 2
            } else { append(ch); i++ }
        }
    }

    @Test
    fun sameResultAsWindowsVersion() {
        val grid = resource("shift_grid.tsv").trimEnd('\n').split('\n').map { line -> line.split('\t').map(::unescape) }
        val roster = resource("shift_roster.tsv").trim().split('\n').associate { line ->
            val (grade, name) = line.split('\t')
            name to grade
        }
        val r = ShiftParser.extract(grid, "赤松", roster, LocalDate.of(2026, 11, 3))
        fun f(m: Int) = "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
        val actual = r.lessons.map { "L ${it.date} ${f(it.start)}-${f(it.end)} ${it.title}" } +
            r.others.map { "O ${it.date} ${if (it.allDay) "allday" else f(it.start) + "-" + f(it.end)} ${it.title}" }
        val expected = resource("shift_expected.txt").trim().split('\n')
        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    @Test
    fun surnameAndSubject() {
        assertEquals("竹内", ShiftParser.surname("竹内　咲希"))
        assertEquals("山本", ShiftParser.surname("山本パガロビン"))
        assertEquals("英・理", ShiftParser.subjectAbbr("英語・理科"))
        assertEquals("数", ShiftParser.subjectAbbr("数学(振替)"))
        assertEquals(null, ShiftParser.subjectAbbr("指定校コース"))
    }
}

class ShiftDayViewTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText(Charsets.UTF_8)

    @Test
    fun dayOfAllTeachers() {
        val grid = resource("shift_grid.tsv").trimEnd('\n').split('\n').map { line ->
            line.split('\t').map { it.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\") }
        }
        val roster = mapOf("小林八郎" to "高1", "吉田十郎" to "高2", "山口一子" to "高1")
        val day = ShiftParser.day(grid, roster, java.time.LocalDate.of(2026, 11, 3))!!
        assertEquals(listOf("赤松", "岸田"), day.teachers)
        val row = day.rows.first { it.label == "16:05" }
        val cell = row.cells.getValue("赤松")
        assertEquals(listOf("渡辺　六子", "中村　七子"), cell.students.map { it.name })
        assertEquals(listOf("小6", "小6"), cell.students.map { it.grade })
        assertEquals(listOf("算数", "算数"), cell.students.map { it.subject })
        // 学年なしの生徒は名簿の学年、科目はシフト表の書き方のまま（振替）
        val late = day.rows.first { it.label == "20:35" }.cells.getValue("赤松")
        assertEquals(listOf("高1" to "数学", "中3" to "数学(振替)"), late.students.map { it.grade to it.subject })
        // 授業以外
        val guest = day.rows.first { it.label == "10:30" }.cells.getValue("赤松")
        assertEquals(listOf("11:00" to "中3田中様"), guest.guests)
        assertEquals(null, ShiftParser.day(grid, roster, java.time.LocalDate.of(2026, 12, 25)))
    }
}
