package jp.sekisho.vertical.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
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
    private const val BASE_AXIS_DP = 18f

    /** 文字の拡大率：1日分の列幅に比例（スマホ=1.0、Galaxy Z Fold の内側画面など広い画面で最大1.6） */
    fun textScale(widthDp: Float): Float = ((widthDp - BASE_AXIS_DP) / 7f / 50f).coerceIn(1f, 1.6f)

    fun axisDp(widthDp: Float): Float = BASE_AXIS_DP * textScale(widthDp)

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
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val cv = Canvas(bmp)
        fun dp(v: Float) = v * scale
        val ts = textScale(widthDp)
        /** 文字・部品のサイズ（拡大率を掛ける） */
        fun sz(v: Float) = v * scale * ts

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint().apply { strokeWidth = dp(1f) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG)

        cv.drawColor(Palette.PAPER)

        val axisW = dp(axisDp(widthDp))
        val colW = (w - axisW) / 7f
        val headerH = sz(30f)
        val eventText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = sz(9.5f)
            color = Palette.EVENT_TEXT
        }
        val maxAllDay = days.maxOf { dayEvents[it]?.allDay?.size ?: 0 }.coerceAtMost(2)
        val allDayRowH = sz(13f)
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
            text.textSize = sz(9f)
            text.color = Palette.text(k)
            cv.drawText(Dates.dowJa(d), x + colW / 2, sz(10f), text)
            text.textSize = sz(14f)
            if (d == today) {
                fill.color = Palette.TODAY
                cv.drawCircle(x + colW / 2, sz(19.5f), sz(9f), fill)
                text.color = 0xFFFFFFFF.toInt()
            } else {
                text.color = Palette.numText(k)
            }
            cv.drawText("${d.dayOfMonth}", x + colW / 2, sz(24.5f), text)
        }

        // ---- 時間帯の背景・罫線 ----
        days.forEachIndexed { i, d ->
            val x = axisW + colW * i
            fill.color = Palette.tint(Dates.kind(d))
            cv.drawRect(x, top, x + colW, h.toFloat(), fill)
        }
        val dash = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
        line.pathEffect = dash
        for (i in 0 until hours) {
            val half = top + hourH * i + hourH / 2
            line.color = 0xFFEBE6DB.toInt()
            cv.drawLine(axisW, half, w.toFloat(), half, line)
            if (i == 0) continue
            val y = top + hourH * i
            line.color = 0xFFD8D2C4.toInt()
            cv.drawLine(axisW, y, w.toFloat(), y, line)
        }
        line.pathEffect = null
        line.color = Palette.LINE_SOFT
        cv.drawLine(0f, top, w.toFloat(), top, line)

        // ---- 時刻ラベル ----
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT
        text.textSize = sz(10f)
        text.color = 0xFF555555.toInt()
        val step = if (hourH < sz(11f)) 2 else 1
        for (i in 0 until hours step step) {
            cv.drawText("${Dates.START_HOUR + i}", axisW / 2, top + hourH * i + sz(10f), text)
        }

        // ---- 終日予定 ----
        days.forEachIndexed { i, d ->
            val x = axisW + colW * i
            dayEvents[d]?.allDay?.take(2)?.forEachIndexed { j, ev ->
                val y = headerH + dp(1.5f) + allDayRowH * j
                val r = RectF(x + dp(1f), y, x + colW - dp(1f), y + allDayRowH - dp(1f))
                drawEventBox(cv, r, ev.color, fill, ::dp)
                drawClipped(cv, ev.title, r, dp(1.5f), dp(3f), eventText, singleLine = true)
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
                val y2 = maxOf(top + hourH * b / 60f, y1 + sz(12f))
                val r = RectF(x + cw * s.col + dp(1.5f), y1 + dp(1f), x + cw * (s.col + 1) - dp(1.5f), y2 - dp(1f))
                drawEventBox(cv, r, s.event.color, fill, ::dp)
                drawClipped(cv, s.event.title, r, dp(2f), dp(3f), eventText, singleLine = false)
            }
        }

        // ---- 縦の区切り線（破線） ----
        line.color = Palette.LINE
        line.pathEffect = dash
        for (i in 0..6) {
            val x = axisW + colW * i
            cv.drawLine(x, 0f, x, h.toFloat(), line)
        }
        line.pathEffect = null

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

    /** 予定の箱：角丸・予定の色を薄くした背景・下側にうっすら影 */
    private fun drawEventBox(cv: Canvas, r: RectF, color: Int, fill: Paint, dp: (Float) -> Float) {
        fill.color = 0x22000000
        cv.drawRoundRect(RectF(r.left, r.top + dp(0.8f), r.right, r.bottom + dp(0.8f)), dp(4f), dp(4f), fill)
        fill.color = Palette.eventBg(color)
        cv.drawRoundRect(r, dp(4f), dp(4f), fill)
    }

    /** 矩形内に文字を折り返して描画（はみ出す分は切り捨て） */
    private fun drawClipped(cv: Canvas, s: String, r: RectF, pad: Float, padLeft: Float, p: Paint, singleLine: Boolean) {
        cv.save()
        cv.clipRect(r)
        val maxW = r.width() - padLeft - pad
        if (maxW <= 0) { cv.restore(); return }
        val fm = p.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 0.95f
        var y = r.top + pad - fm.ascent
        var rest = s
        var first = true
        // 1行目は必ず描き、2行目以降は矩形に収まる範囲だけ描く
        while (rest.isNotEmpty() && (first || y + fm.descent <= r.bottom + lineH * 0.3f)) {
            first = false
            val n = p.breakText(rest, true, maxW, null).coerceAtLeast(1)
            cv.drawText(rest, 0, n, r.left + padLeft, y, p)
            if (singleLine) break
            rest = rest.substring(n)
            y += lineH
        }
        cv.restore()
    }
}
