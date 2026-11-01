package jp.sekisho.vertical.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import jp.sekisho.vertical.data.DayEvents
import jp.sekisho.vertical.data.Dates
import jp.sekisho.vertical.ui.Palette
import java.time.LocalDate
import java.time.LocalTime

/**
 * ウィジェット用：週間バーチカル（曜日ヘッダー・終日・8〜24時）を1枚の画像に描く。
 * RemoteViews では自由な配置ができないため Canvas で描画する。
 */
object WeekBitmapRenderer {
    const val AXIS_DP = 20f

    fun render(
        widthDp: Float,
        heightDp: Float,
        scale: Float,
        days: List<LocalDate>,
        dayEvents: Map<LocalDate, DayEvents>,
        today: LocalDate,
        now: LocalTime,
    ): Bitmap {
        val w = (widthDp * scale).toInt().coerceAtLeast(1)
        val h = (heightDp * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        fun dp(v: Float) = v * scale

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint().apply { strokeWidth = dp(1f) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG)

        cv.drawColor(Palette.PAPER)

        val axisW = dp(AXIS_DP)
        val colW = (w - axisW) / 7f
        val headerH = dp(28f)
        val maxAllDay = days.maxOf { dayEvents[it]?.allDay?.size ?: 0 }.coerceAtMost(2)
        val allDayRowH = dp(11f)
        val allDayH = if (maxAllDay == 0) dp(4f) else allDayRowH * maxAllDay + dp(3f)
        val top = headerH + allDayH
        val hours = Dates.END_HOUR - Dates.START_HOUR
        val hourH = (h - top) / hours

        // ---- 曜日ヘッダー ----
        days.forEachIndexed { i, d ->
            val k = Dates.kind(d)
            val x = axisW + colW * i
            fill.color = Palette.headerBg(k)
            cv.drawRect(x, 0f, x + colW, headerH, fill)
            fill.color = Palette.accent(k)
            cv.drawRect(x, headerH - dp(2f), x + colW, headerH, fill)
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = dp(8f)
            text.color = Palette.text(k)
            cv.drawText(Dates.dowJa(d), x + colW / 2, dp(9f), text)
            text.textSize = dp(12f)
            if (d == today) {
                fill.color = Palette.TODAY
                cv.drawCircle(x + colW / 2, dp(18f), dp(8f), fill)
                text.color = 0xFFFFFFFF.toInt()
            } else {
                text.color = Palette.numText(k)
            }
            cv.drawText("${d.dayOfMonth}", x + colW / 2, dp(22f), text)
        }

        // ---- 時間帯の背景・罫線 ----
        days.forEachIndexed { i, d ->
            val x = axisW + colW * i
            fill.color = Palette.tint(Dates.kind(d))
            cv.drawRect(x, top, x + colW, h.toFloat(), fill)
        }
        line.color = Palette.LINE_SOFT
        for (i in 1 until hours) {
            val y = top + hourH * i
            cv.drawLine(axisW, y, w.toFloat(), y, line)
        }
        line.color = Palette.LINE
        cv.drawLine(0f, top, w.toFloat(), top, line)
        cv.drawLine(0f, headerH, w.toFloat(), headerH, line)

        // ---- 時刻ラベル ----
        text.textAlign = Paint.Align.RIGHT
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(7f)
        text.color = Palette.SUB
        val step = if (hourH < dp(9f)) 2 else 1
        for (i in 0 until hours step step) {
            cv.drawText("${Dates.START_HOUR + i}", axisW - dp(2f), top + hourH * i + dp(7f), text)
        }

        // ---- 終日予定 ----
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(7.5f)
        days.forEachIndexed { i, d ->
            val x = axisW + colW * i
            dayEvents[d]?.allDay?.take(2)?.forEachIndexed { j, ev ->
                val y = headerH + dp(1.5f) + allDayRowH * j
                val r = RectF(x + dp(1f), y, x + colW - dp(1f), y + allDayRowH - dp(1f))
                fill.color = ev.color
                cv.drawRoundRect(r, dp(2f), dp(2f), fill)
                text.color = 0xFFFFFFFF.toInt()
                drawClipped(cv, ev.title, r, dp(1.5f), text, singleLine = true)
            }
        }

        // ---- 時間指定の予定 ----
        days.forEachIndexed { i, d ->
            val x = axisW + colW * i
            dayEvents[d]?.timed?.forEach { s ->
                val t = (s.startMin - Dates.START_MIN).coerceAtLeast(0)
                val b = (s.endMin - Dates.START_MIN).coerceAtMost(Dates.RANGE_MIN)
                if (b <= 0 || t >= Dates.RANGE_MIN) return@forEach
                val cw = colW / s.ncols
                val y1 = top + hourH * t / 60f
                val y2 = maxOf(top + hourH * b / 60f, y1 + dp(9f))
                val r = RectF(x + cw * s.col + dp(1f), y1 + dp(0.5f), x + cw * (s.col + 1) - dp(1f), y2 - dp(0.5f))
                fill.color = s.event.color
                cv.drawRoundRect(r, dp(2f), dp(2f), fill)
                text.color = 0xFFFFFFFF.toInt()
                drawClipped(cv, s.event.title, r, dp(1.5f), text, singleLine = false)
            }
        }

        // ---- 縦の区切り線 ----
        line.color = Palette.LINE
        for (i in 0..6) {
            val x = axisW + colW * i
            cv.drawLine(x, 0f, x, h.toFloat(), line)
        }

        // ---- 現在時刻 ----
        val ti = days.indexOf(today)
        val nowMin = now.hour * 60 + now.minute
        if (ti >= 0 && nowMin in Dates.START_MIN..Dates.END_MIN) {
            val y = top + hourH * (nowMin - Dates.START_MIN) / 60f
            val x = axisW + colW * ti
            fill.color = Palette.TODAY
            cv.drawRect(x, y - dp(0.75f), x + colW, y + dp(0.75f), fill)
            cv.drawCircle(x + dp(2f), y, dp(2.5f), fill)
        }

        return bmp
    }

    /** 矩形内に文字を折り返して描画（はみ出す分は切り捨て） */
    private fun drawClipped(cv: Canvas, s: String, r: RectF, pad: Float, p: Paint, singleLine: Boolean) {
        cv.save()
        cv.clipRect(r)
        val maxW = r.width() - pad * 2
        if (maxW <= 0) { cv.restore(); return }
        val fm = p.fontMetrics
        val lineH = fm.descent - fm.ascent
        var y = r.top + pad - fm.ascent
        var rest = s
        var first = true
        // 1行目は必ず描き、2行目以降は矩形に収まる範囲だけ描く
        while (rest.isNotEmpty() && (first || y + fm.descent <= r.bottom + lineH * 0.3f)) {
            first = false
            val n = p.breakText(rest, true, maxW, null).coerceAtLeast(1)
            cv.drawText(rest, 0, n, r.left + pad, y, p)
            if (singleLine) break
            rest = rest.substring(n)
            y += lineH
        }
        cv.restore()
    }
}
