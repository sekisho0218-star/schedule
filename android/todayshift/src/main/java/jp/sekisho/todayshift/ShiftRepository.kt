package jp.sekisho.todayshift

import android.content.Context
import jp.sekisho.vertical.shift.ShiftParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 設定と、シフト表の読み込み（前回の内容を端末に保存し、電波がなくても表示できるように） */
class ShiftRepository(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val gridFile = File(app.filesDir, "grid.tsv")
    private val rosterFile = File(app.filesDir, "roster.tsv")

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v.trim()).apply()

    /** 自分の名前（列を目立たせる） */
    var myName: String
        get() = sp.getString("myName", "赤松") ?: "赤松"
        set(v) = sp.edit().putString("myName", v.trim()).apply()

    var lastFetched: Long
        get() = sp.getLong("lastFetched", 0L)
        private set(v) = sp.edit().putLong("lastFetched", v).apply()

    /** 保存してある前回のシフト表 */
    fun cached(): ShiftParser.Sheet? = runCatching {
        if (!gridFile.exists()) return null
        val grid = gridFile.readText().split('\n').filter { it.isNotEmpty() }.map { line -> line.split('\t').map(::unescape) }
        val roster = if (rosterFile.exists()) {
            rosterFile.readText().split('\n').filter { it.contains('\t') }.associate { val (n, g) = it.split('\t'); n to g }
        } else {
            emptyMap()
        }
        ShiftParser.Sheet(grid, roster)
    }.getOrNull()

    /** シフト表を読み込んで保存 */
    suspend fun fetch(): ShiftParser.Sheet = withContext(Dispatchers.IO) {
        val sheet = ShiftParser.fetchSheet(url)
        gridFile.writeText(sheet.grid.joinToString("\n") { row -> row.joinToString("\t") { escape(it) } } + "\n")
        rosterFile.writeText(sheet.roster.entries.joinToString("\n") { "${it.key}\t${it.value}" } + "\n")
        lastFetched = System.currentTimeMillis()
        sheet
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i + 1] })
                i += 2
            } else { append(s[i]); i++ }
        }
    }
}
