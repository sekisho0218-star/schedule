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
