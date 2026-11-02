package jp.sekisho.vertical.ui

import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

fun c(argb: Int) = Color(argb)

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

/** 予定の見た目：薄い背景＋左端に予定の色の帯 */
fun Modifier.eventStyle(color: Int) = background(Color(Palette.eventBg(color))).drawBehind {
    drawRect(Color(color), size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height))
}
