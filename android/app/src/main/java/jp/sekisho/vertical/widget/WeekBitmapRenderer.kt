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
        /** セリフ体（日付・時刻）。null なら標準の書体 */
        serif: Typeface? = null,
        serifItalic: Typeface? = null,
        /** 本文の書体（Inter Tight） */
        sans: Typeface? = null,
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
            typeface = sans ?: Typeface.DEFAULT
            textSize = sz(9.5f)
            color = Palette.EVENT_TEXT
        }
        val serifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif ?: Typeface.SERIF
            fontFeatureSettings = "lnum" // 高さのそろった数字
            textAlign = Paint.Align.CENTER
        }
        val maxAllDay = days.maxOf { dayEvents[it]?.allDay?.size ?: 0 }.coerceAtMost(2)
        val allDayRowH = sz(13f)
        val allDayH = if (maxAllDay == 0) dp(4f) else allDayRowH * maxAllDay + dp(3f)
        val top = headerH + allDayH
        val hours = Dates.END_HOUR - Dates.START_HOUR
        val hourH = (h - top) / hours

        // ---- 曜日ヘッダー（英字の曜日＋セリフ体の日付。今日は銅色の斜体） ----
        days.forEachIndexed { i, d ->
            val k = Dates.kind(d)
            val x = axisW + colW * i
            val isToday = d == today
            text.textAlign = Paint.Align.CENTER
            text.typeface = sans ?: Typeface.DEFAULT
            text.textSize = sz(6.5f)
            text.letterSpacing = 0.15f
            text.color = if (isToday) Palette.COPPER else Palette.text(k)
            cv.drawText(Dates.dowEn(d), x + colW / 2, sz(10f), text)
            text.letterSpacing = 0f
            serifPaint.typeface = if (isToday) (serifItalic ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)) else (serif ?: Typeface.SERIF)
            serifPaint.textSize = sz(17f)
            serifPaint.color = if (isToday) Palette.COPPER else Palette.numText(k)
            cv.drawText("${d.dayOfMonth}", x + colW / 2, sz(25f), serifPaint)
        }
        line.color = Palette.LINE
        cv.drawLine(0f, headerH - dp(0.5f), w.toFloat(), headerH - dp(0.5f), line)

        // ---- 今日の列・罫線（細い実線） ----
        val ti0 = days.indexOf(today)
        if (ti0 >= 0) {
            fill.color = Palette.TODAY_TINT
            cv.drawRect(axisW + colW * ti0, top, axisW + colW * (ti0 + 1), h.toFloat(), fill)
        }
        for (i in 0 until hours) {
            val half = top + hourH * i + hourH / 2
            line.color = 0x73E6E1DA // 30分の線（薄く）
            cv.drawLine(axisW, half, w.toFloat(), half, line)
            if (i == 0) continue
            val y = top + hourH * i
            line.color = Palette.LINE_SOFT
            cv.drawLine(axisW, y, w.toFloat(), y, line)
        }
        line.color = Palette.LINE
        cv.drawLine(0f, top, w.toFloat(), top, line)

        // ---- 時刻ラベル ----
        serifPaint.typeface = serif ?: Typeface.SERIF
        serifPaint.textSize = sz(10.5f)
        serifPaint.color = Palette.FAINT
        val step = if (hourH < sz(11f)) 2 else 1
        for (i in 0 until hours step step) {
            cv.drawText("${Dates.START_HOUR + i}", axisW / 2, top + hourH * i + sz(10f), serifPaint)
        }

        // ---- 終日予定 ----
        days.forEachIndexed { i, d ->
            val x = axisW + colW * i
            dayEvents[d]?.allDay?.take(2)?.forEachIndexed { j, ev ->
                val y = headerH + dp(1.5f) + allDayRowH * j
                val r = RectF(x + dp(1.5f), y, x + colW - dp(1.5f), y + allDayRowH - dp(1f))
                // 終日予定は線で囲むだけ
                line.color = Palette.LINE
                line.style = Paint.Style.STROKE
                cv.drawRoundRect(r, dp(1.5f), dp(1.5f), line)
                line.style = Paint.Style.FILL_AND_STROKE
                eventText.color = Palette.SUB
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
                if (s.event.taskId != null) {
                    // 時刻付きタスク：点線の枠だけ
                    line.color = Palette.FAINT
                    line.style = Paint.Style.STROKE
                    line.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(2f)), 0f)
                    cv.drawRoundRect(r, dp(1.5f), dp(1.5f), line)
                    line.pathEffect = null
                    line.style = Paint.Style.FILL_AND_STROKE
                    eventText.color = Palette.SUB
                } else {
                    drawEventBox(cv, r, s.event.color, fill, ::dp)
                    eventText.color = Palette.eventInk(s.event.color)
                }
                drawClipped(cv, s.event.title, r, dp(2f), dp(4f), eventText, singleLine = false)
            }
        }

        // ---- 縦の区切り線（細い実線） ----
        line.color = Palette.LINE_SOFT
        for (i in 0..6) {
            val x = axisW + colW * i
            cv.drawLine(x, headerH, x, h.toFloat(), line)
        }

        // ---- 現在時刻 ----
        val ti = days.indexOf(today)
        val nowMin = now.hour * 60 + now.minute
        if (ti >= 0 && nowMin in Dates.START_MIN..Dates.END_MIN) {
            val y = top + hourH * (nowMin - Dates.START_MIN) / 60f
            val x = axisW + colW * ti
            // 細い銅色の線と、左端に小さなひし形
            fill.color = Palette.COPPER
            cv.drawRect(x, y - dp(0.5f), x + colW, y + dp(0.5f), fill)
            val dia = android.graphics.Path().apply {
                moveTo(x, y - dp(3f)); lineTo(x + dp(3f), y); lineTo(x, y + dp(3f)); lineTo(x - dp(3f), y); close()
            }
            cv.drawPath(dia, fill)
        }

        return bmp
    }

    /** 予定の箱：くすませた予定の色の背景＋左端に細い線（Greige & Copper） */
    private fun drawEventBox(cv: Canvas, r: RectF, color: Int, fill: Paint, dp: (Float) -> Float) {
        fill.color = Palette.eventBg(color)
        cv.drawRoundRect(r, dp(1.5f), dp(1.5f), fill)
        fill.color = Palette.eventBar(color)
        cv.drawRect(r.left, r.top, r.left + dp(1.5f), r.bottom, fill)
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
