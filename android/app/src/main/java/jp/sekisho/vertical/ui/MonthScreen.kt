package jp.sekisho.vertical.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.sekisho.vertical.data.CalEvent
import jp.sekisho.vertical.data.Dates
import jp.sekisho.vertical.data.eventsForDay
import jp.sekisho.vertical.data.taskEvents
import jp.sekisho.vertical.data.untimedTasks
import java.time.LocalDate
import java.time.temporal.ChronoUnit


@Composable
fun MonthScreen(
    anchor: LocalDate,
    events: List<CalEvent>,
    taskVersion: Long,
    tasksOf: (LocalDate) -> List<jp.sekisho.vertical.data.Task>,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = anchor.withDayOfMonth(1)
    val start = Dates.monthGridStart(anchor)
    val last = first.plusMonths(1).minusDays(1)
    val weeks = ((ChronoUnit.DAYS.between(start, last) + 1 + 6) / 7).toInt()
    val today = LocalDate.now()
    // 時刻付きタスク（「15:00 〇〇」）も予定と一緒に表示
    val perDay = remember(events, start, taskVersion) {
        val days = (0L until 42L).map { start.plusDays(it) }
        val all = events + days.flatMap { taskEvents(it, tasksOf(it)) }
        days.associateWith { eventsForDay(all, it) }
    }

    BoxWithConstraints(modifier.fillMaxSize().padding(4.dp)) {
    val scale = textScaleFor(maxWidth / 7)
    // セルの高さに入るだけ予定を表示（1行 ≒ 文字サイズ10×拡大率×1.45）
    val rowH = (maxHeight - (28f * scale).dp) / weeks
    val maxItems = ((rowH - (34f * scale).dp) / (14.5f * scale).dp).toInt().coerceIn(1, 10)
    CompositionLocalProvider(LocalTextScale provides scale) {
    Column(
        Modifier
            .fillMaxSize()
            .shadow(3.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(c(Palette.PAPER)),
    ) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0L until 7L) {
                val d = start.plusDays(i)
                val k = Dates.kind(d)
                Box(
                    Modifier.weight(1f).background(c(Palette.headerBg(k))).bottomLine(c(Palette.accent(k))).padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(Dates.dowJa(d), fontSize = ssp(12f), fontWeight = FontWeight.Bold, color = c(Palette.text(k)))
                }
            }
        }
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (i in 0 until 7) {
                    val d = start.plusDays((w * 7 + i).toLong())
                    val k = Dates.kind(d)
                    val other = d.month != first.month
                    val de = perDay.getValue(d)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(c(Palette.tint(k)))
                            .leftLine(c(Palette.LINE_SOFT))
                            .topLine(c(Palette.LINE_SOFT))
                            .clickable { onDayClick(d) }
                            .alpha(if (other) 0.45f else 1f)
                            .padding(2.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        val isToday = d == today
                        Box(
                            Modifier.size(sdp(20f)).clip(CircleShape).background(if (isToday) c(Palette.TODAY) else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${d.dayOfMonth}",
                                fontSize = ssp(12f),
                                fontWeight = FontWeight.SemiBold,
                                color = if (isToday) Color.White else c(Palette.numText(k)),
                            )
                        }
                        val items = de.allDay.map { it to true } + de.timed.map { it.event to false }
                        items.take(maxItems).forEach { (ev, allDay) ->
                            if (allDay) {
                                Text(
                                    ev.title, fontSize = ssp(10.5f), fontWeight = FontWeight.Medium, color = c(Palette.EVENT_TEXT),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().eventStyle(ev.color).padding(horizontal = 4.dp),
                                )
                            } else {
                                Text(
                                    ev.title, fontSize = ssp(10.5f), color = c(Palette.EVENT_TEXT), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                                        .background(Color(Palette.eventBg(ev.color, 0.7f))).padding(horizontal = 4.dp),
                                )
                            }
                        }
                        if (items.size > maxItems) Text("他${items.size - maxItems}件", fontSize = ssp(9f), color = c(Palette.SUB))
                        val n = remember(d, taskVersion) { untimedTasks(tasksOf(d)).size }
                        if (n > 0) {
                            Spacer(Modifier.weight(1f))
                            Text("☐ $n", fontSize = ssp(9f), color = c(Palette.INK))
                        }
                    }
                }
            }
        }
    }
}
    }
    }
