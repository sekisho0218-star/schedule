package jp.sekisho.vertical.ui

import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

fun c(argb: Int) = Color(argb)

/**
 * 文字・部品の拡大率。1日分の列の幅に比例させる（スマホ縦=1.0、Galaxy Z Fold の内側画面など広い画面で最大1.6）。
 */
val LocalTextScale = compositionLocalOf { 1f }

fun textScaleFor(columnWidth: Dp): Float = (columnWidth / 50.dp).coerceIn(1f, MAX_TEXT_SCALE)
const val MAX_TEXT_SCALE = 1.6f

@Composable
fun ssp(v: Float): TextUnit = (v * LocalTextScale.current).sp

@Composable
fun sdp(v: Float): Dp = (v * LocalTextScale.current).dp

/** 列の左側に区切り線を引く */
fun Modifier.leftLine(color: Color = c(Palette.LINE)) = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
}

fun Modifier.topLine(color: Color = c(Palette.LINE), width: Float = 1f) = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), width.dp.toPx())
}

fun Modifier.bottomLine(color: Color, width: Float = 2f) = drawBehind {
    val w = width.dp.toPx()
    drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}

/** 予定の見た目：角を少しだけ丸め、くすませた予定の色の背景＋左端に細い線（Greige & Copper） */
fun Modifier.eventStyle(color: Int) = this
    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
    .background(Color(Palette.eventBg(color)))
    .drawBehind { drawRect(Color(Palette.eventBar(color)), size = androidx.compose.ui.geometry.Size(1.5.dp.toPx(), size.height)) }

/** 時刻付きタスクの見た目：背景なし・点線の枠 */
fun Modifier.taskStyle() = drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        Color(Palette.FAINT),
        topLeft = Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        style = androidx.compose.ui.graphics.drawscope.Stroke(w, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
    )
}

/** 列の左側の細い線（以前は破線） */
fun Modifier.leftDashed(color: Color = c(Palette.LINE_SOFT)) = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
}
