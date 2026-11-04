package jp.sekisho.todayshift.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import jp.sekisho.todayshift.Layout
import jp.sekisho.vertical.shift.ShiftParser

/**
 * ウィジェット用：1日分の全員のシフト表を1枚の画像に描く。
 * 幅に入りきらない人数なら講師を2段（以上）に分け、高さに入りきらなければ文字を小さくする。
 * 各生徒は1行（「中2坪本(数)」）。
 */
object TableRenderer {
    private const val INK = 0xFF2B2B2B.toInt()
    private const val SUB = 0xFF555555.toInt()
    private const val LINE = 0xFFD9D4C7.toInt()
    private const val PAPER = 0xFFFFFDF7.toInt()
    private const val HEAD = 0xFFFFF8DC.toInt()
    private const val HEAD_LINE = 0xFFF2C230.toInt()
    private const val MINE = 0xFFFFF4CC.toInt()
    private const val NOW = 0xFFFFE9D2.toInt()
    private const val GUEST = 0xFF8A2E2E.toInt()

    private class TierRows(val teachers: List<String>, val rows: List<Pair<Int, ShiftParser.RowView>>)

    fun render(day: ShiftParser.DayView, myName: String, nowMin: Int?, widthDp: Float, heightDp: Float, density: Float): Bitmap {
        val scale = density.coerceAtMost(3f)
        val w = (widthDp * scale).toInt().coerceAtLeast(1)
        val h = (heightDp * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val cv = Canvas(bmp)
        cv.drawColor(PAPER)

        val nowRow = nowMin?.let { Layout.nowRow(day, it) }
        val n = day.teachers.size.coerceAtLeast(1)

        // 文字の幅を dp で測る（textSize を dp の値にして測る）
        val measure = Paint(Paint.ANTI_ALIAS_FLAG)
        fun widthAt(s: String, f: Float, bold: Boolean = false): Float {
            measure.textSize = f
            measure.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            return measure.measureText(s)
        }
        fun timeColAt(f: Float) = widthAt("20:35", f) + 6f
        fun tiersOf(per: Int) = day.teachers.chunked(per).map { group ->
            TierRows(group, day.rows.withIndex().filter { (_, r) -> group.any { r.cells[it]?.isEmpty == false } }.map { it.index to it.value })
        }
        fun heightAt(tiers: List<TierRows>, f: Float): Float {
            val lineH = f * 1.3f
            return tiers.sumOf { t ->
                val body = t.rows.sumOf { (_, r) ->
                    val lines = t.teachers.maxOf { r.cells[it]?.let { c -> Layout.shortLines(c).size } ?: 0 }.coerceAtLeast(1)
                    (lines * lineH + f * 0.5f).toDouble()
                }
                (f * 1.6f + body + f * 0.6f).toDouble()
            }.toFloat()
        }
        /** 文字の大きさ f で、高さにも幅にも収まるか */
        fun fits(tiers: List<TierRows>, per: Int, f: Float): Boolean {
            if (heightAt(tiers, f) > heightDp) return false
            val colW = (widthDp - timeColAt(f)) / per - 5f
            if (colW <= 0) return false
            return tiers.all { t ->
                t.teachers.all { name -> widthAt(name, f, bold = true) <= colW } &&
                    t.rows.all { (_, r) -> t.teachers.all { name -> r.cells[name]?.let { c -> Layout.shortLines(c).all { widthAt(it, f) <= colW } } ?: true } }
            }
        }
        // 1段・2段・3段…のうち、いちばん大きな文字で収まる並べ方を選ぶ（縦に伸ばすと段を増やして文字を大きくできることがある）
        var best = Triple(n, tiersOf(n), 6f)
        for (tierCount in 1..n) {
            val per = ceil(n / tierCount.toDouble()).toInt()
            if (tierCount > 1 && ceil(n / (tierCount - 1).toDouble()).toInt() == per) continue
            val tiers = tiersOf(per)
            var f = 28f
            while (f > 6f && !fits(tiers, per, f)) f -= 0.25f
            if (f > best.third || (f == best.third && per > best.first)) best = Triple(per, tiers, f)
        }
        val (perTier, tiers, f) = best
        val timeDp = timeColAt(f)
        fun dp(v: Float) = v * scale

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(f); color = INK }
        val fill = Paint()
        val line = Paint().apply { color = LINE; strokeWidth = dp(0.8f) }
        val lineH = dp(f * 1.3f)
        val timeW = dp(timeDp)
        val colW = (w - timeW) / perTier

        fun drawFit(s: String, x: Float, y: Float, maxW: Float, p: Paint) {
            if (maxW <= 0) return
            val n = p.breakText(s, true, maxW, null)
            cv.drawText(if (n < s.length) s.substring(0, (n - 1).coerceAtLeast(1)) + "…" else s, x, y, p)
        }

        var y = 0f
        for (t in tiers) {
            val tierTop = y
            // 講師名の行
            val headH = dp(f * 1.6f)
            fill.color = HEAD
            cv.drawRect(0f, y, w.toFloat(), y + headH, fill)
            t.teachers.forEachIndexed { i, name ->
                val x = timeW + colW * i
                if (name == myName) { fill.color = MINE; cv.drawRect(x, y, x + colW, y + headH, fill) }
                text.typeface = if (name == myName) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                text.color = INK
                text.textAlign = Paint.Align.CENTER
                drawFit(name, x + colW / 2, y + headH * 0.72f, colW - dp(2f), text)
            }
            fill.color = HEAD_LINE
            cv.drawRect(0f, y + headH - dp(1.5f), w.toFloat(), y + headH, fill)
            y += headH

            for ((ri, r) in t.rows) {
                val lines = t.teachers.map { r.cells[it]?.let { c -> Layout.shortLines(c) } ?: emptyList() }
                val rowH = lines.maxOf { it.size }.coerceAtLeast(1) * lineH + dp(f * 0.5f)
                if (ri == nowRow) { fill.color = NOW; cv.drawRect(0f, y, w.toFloat(), y + rowH, fill) }
                t.teachers.forEachIndexed { i, name ->
                    if (name == myName && ri != nowRow) {
                        fill.color = 0x66FFF4CC
                        cv.drawRect(timeW + colW * i, y, timeW + colW * (i + 1), y + rowH, fill)
                    }
                }
                // 時刻
                text.typeface = if (ri == nowRow) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                text.color = if (ri == nowRow) 0xFFB45309.toInt() else SUB
                text.textAlign = Paint.Align.CENTER
                drawFit(r.label.lineSequence().firstOrNull()?.ifEmpty { "—" } ?: "—", timeW / 2, y + lineH * 0.85f, timeW - dp(2f), text)
                // 各講師
                text.textAlign = Paint.Align.LEFT
                text.typeface = Typeface.DEFAULT
                lines.forEachIndexed { i, ls ->
                    val x = timeW + colW * i + dp(2.5f)
                    ls.forEachIndexed { k, s ->
                        text.color = if (s.contains("様")) GUEST else INK
                        drawFit(s, x, y + lineH * (k + 0.85f), colW - dp(4f), text)
                    }
                }
                cv.drawLine(0f, y + rowH, w.toFloat(), y + rowH, line)
                y += rowH
            }
            // 縦の区切り線（この段の中だけ）
            for (i in 0..t.teachers.size) {
                val x = timeW + colW * i
                cv.drawLine(x, tierTop, x, y, line)
            }
            y += dp(f * 0.6f)
        }
        return bmp
    }
}
