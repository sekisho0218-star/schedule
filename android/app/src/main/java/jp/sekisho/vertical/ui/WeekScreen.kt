package jp.sekisho.vertical.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.sekisho.vertical.data.CalEvent
import jp.sekisho.vertical.data.Dates
import jp.sekisho.vertical.data.Task
import jp.sekisho.vertical.data.TaskRepository
import jp.sekisho.vertical.data.TimedSlot
import jp.sekisho.vertical.data.eventsForDay
import jp.sekisho.vertical.data.linkTasks
import jp.sekisho.vertical.data.taskEvents
import jp.sekisho.vertical.data.untimedTasks
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

private val AXIS_W: Dp @Composable get() = sdp(34f)
private val TASK_ROW_H: Dp @Composable get() = sdp(28f)
private val HOURS = Dates.END_HOUR - Dates.START_HOUR

@Composable
fun WeekScreen(
    weekStart: LocalDate,
    events: List<CalEvent>,
    now: LocalTime,
    taskVersion: Long,
    tasksOf: (LocalDate) -> List<Task>,
    onAddTask: (LocalDate, String) -> Unit,
    onCompleteTask: (LocalDate, String) -> Unit,
    onEditTask: (LocalDate, Task) -> Unit,
    onEventClick: (CalEvent) -> Unit,
    onAddEvent: (LocalDate, LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = remember(weekStart) { (0L..6L).map { weekStart.plusDays(it) } }
    // 予定に、時刻付きタスク（「15:00 〇〇」）を加えてスケジュールに並べる
    // さらに、日時を設定したタスク（カレンダーに同じ名前の時刻付き予定がある）はスケジュールにだけ表示
    val linked = remember(events, weekStart, taskVersion) {
        val all = events + days.flatMap { taskEvents(it, tasksOf(it)) }
        days.associateWith { linkTasks(it, tasksOf(it), eventsForDay(all, it)) }
    }
    val dayEvents = linked.mapValues { it.value.first }
    // スケジュール上のタスクをタップ → タスクの編集（完了）、予定をタップ → 予定の編集
    val onItemClick: (CalEvent) -> Unit = { ev ->
        val id = ev.taskId
        val d = ev.taskDate
        if (id != null && d != null) {
            tasksOf(d).firstOrNull { it.id == id }?.let { onEditTask(d, it) }
        } else {
            onEventClick(ev)
        }
    }
    val today = LocalDate.now()

    BoxWithConstraints(modifier.fillMaxSize().padding(4.dp)) {
    val scale = textScaleFor((maxWidth - 34.dp) / 7)
    CompositionLocalProvider(LocalTextScale provides scale) {
    Column(
        Modifier
            .fillMaxSize()
            .shadow(3.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(c(Palette.PAPER)),
    ) {
        // 曜日ヘッダー
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(AXIS_W).fillMaxHeight().bottomLine(c(Palette.LINE_SOFT)))
            days.forEach { d ->
                DayHeader(d, d == today, Modifier.weight(1f).clickable { onAddEvent(d, null) })
            }
        }
        // 終日予定
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(AXIS_W).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("終日", fontSize = ssp(10f), color = c(Palette.SUB))
            }
            days.forEach { d ->
                Column(
                    Modifier.weight(1f).fillMaxHeight().heightIn(min = sdp(22f)).leftDashed().padding(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val list = dayEvents.getValue(d).allDay
                    list.take(3).forEach { ev ->
                        Text(
                            ev.title,
                            fontSize = ssp(11f),
                            fontWeight = FontWeight.Medium,
                            color = c(Palette.EVENT_TEXT),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                                .eventStyle(ev.color).clickable { onItemClick(ev) }
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    if (list.size > 3) Text("+${list.size - 3}", fontSize = ssp(8f), color = c(Palette.SUB))
                }
            }
        }

        // バーチカル（8:00〜24:00）＋タスク。画面に収まるよう1時間の高さを自動調整
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tasksH = TASK_ROW_H * TaskRepository.PER_DAY + 8.dp
            val hourH = ((maxHeight - tasksH) / HOURS).coerceAtLeast(30.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().topLine(c(Palette.LINE_SOFT))) {
                    TimeAxis(hourH)
                    days.forEach { d ->
                        DayTimeline(
                            date = d,
                            slots = dayEvents.getValue(d).timed,
                            hourH = hourH,
                            nowMin = if (d == today) now.hour * 60 + now.minute else null,
                            onEventClick = onItemClick,
                            onAddEvent = onAddEvent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).topLine(c(Palette.LINE))) {
                    Box(Modifier.width(AXIS_W).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text("TASK", fontSize = ssp(9f), color = c(Palette.SUB))
                    }
                    days.forEach { d ->
                        key(d, taskVersion) {
                            TaskCell(
                                date = d,
                                tasks = linked.getValue(d).second,
                                onAdd = { onAddTask(d, it) },
                                onComplete = { onCompleteTask(d, it) },
                                onEdit = { onEditTask(d, it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
private fun DayHeader(d: LocalDate, isToday: Boolean, modifier: Modifier) {
    val k = Dates.kind(d)
    Column(
        modifier.background(c(Palette.headerBg(k))).leftDashed().bottomLine(c(Palette.accent(k))).padding(top = 6.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(Dates.dowJa(d), fontSize = ssp(11f), fontWeight = FontWeight.Medium, color = c(Palette.text(k)))
        Box(
            Modifier.padding(top = 2.dp).size(sdp(30f)).clip(CircleShape).background(if (isToday) c(Palette.TODAY) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${d.dayOfMonth}",
                fontSize = ssp(18f),
                fontWeight = FontWeight.Medium,
                color = if (isToday) Color.White else c(Palette.numText(k)),
            )
        }
    }
}

@Composable
private fun TimeAxis(hourH: Dp) {
    Box(Modifier.width(AXIS_W).height(hourH * HOURS)) {
        for (i in 0 until HOURS) {
            Text(
                "${Dates.START_HOUR + i}",
                fontSize = ssp(12f),
                color = c(0xFF555555.toInt()),
                modifier = Modifier.offset(y = hourH * i).width(AXIS_W).padding(top = 2.dp),
                style = TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
        }
    }
}

@Composable
private fun DayTimeline(
    date: LocalDate,
    slots: List<TimedSlot>,
    hourH: Dp,
    nowMin: Int?,
    onEventClick: (CalEvent) -> Unit,
    onAddEvent: (LocalDate, LocalTime?) -> Unit,
    modifier: Modifier,
) {
    val k = Dates.kind(date)
    val lineSoft = Color(0xFFD8D2C4)
    val half = Color(0xFFEBE6DB)
    BoxWithConstraints(
        modifier
            .height(hourH * HOURS)
            .background(c(Palette.tint(k)))
            .drawBehind {
                val h = hourH.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                for (i in 1 until HOURS) {
                    drawLine(lineSoft, Offset(0f, h * i), Offset(size.width, h * i), 1.dp.toPx(), pathEffect = dash)
                }
                for (i in 0 until HOURS) {
                    val y = h * i + h / 2
                    drawLine(half, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
                }
            }
            .leftDashed()
            .pointerInput(date, hourH) {
                detectTapGestures { off ->
                    val mins = (off.y / hourH.toPx() * 60).toInt() / 30 * 30
                    val start = (Dates.START_MIN + mins).coerceAtMost(23 * 60)
                    onAddEvent(date, LocalTime.of(start / 60, start % 60))
                }
            },
    ) {
        val colW = maxWidth
        slots.forEach { s ->
            val top = (s.startMin - Dates.START_MIN).coerceAtLeast(0)
            val bottom = (s.endMin - Dates.START_MIN).coerceAtMost(Dates.RANGE_MIN)
            if (bottom <= 0 || top >= Dates.RANGE_MIN) return@forEach
            val h = (hourH * (bottom - top) / 60).coerceAtLeast(sdp(16f))
            // 枠に入る行数（上下の余白を除いた高さ ÷ 1行の高さ）。入りきらない分は「…」
            val lineDp = 13f * LocalTextScale.current * LocalDensity.current.fontScale
            val maxLines = (((h - 6.dp).value) / lineDp).toInt().coerceAtLeast(1)
            Box(
                Modifier
                    .offset(x = colW * s.col / s.ncols, y = hourH * top / 60)
                    .width(colW / s.ncols)
                    .height(h)
                    .padding(horizontal = 2.dp, vertical = 1.dp)
                    .eventStyle(s.event.color)
                    .clickable { onEventClick(s.event) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    s.event.title,
                    fontSize = ssp(11f),
                    lineHeight = ssp(13f),
                    color = c(Palette.EVENT_TEXT),
                    fontWeight = FontWeight.Medium,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (nowMin != null && nowMin in Dates.START_MIN..Dates.END_MIN) {
            val y = hourH * (nowMin - Dates.START_MIN) / 60
            Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(c(Palette.TODAY)))
            Box(Modifier.offset(x = (-3).dp, y = y - 4.dp).size(8.dp).clip(CircleShape).background(c(Palette.TODAY)))
        }
    }
}

@Composable
private fun TaskCell(
    date: LocalDate,
    tasks: List<Task>,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onEdit: (Task) -> Unit,
    modifier: Modifier,
) {
    val rule = Color(0xFFEBE6DB)
    Column(modifier.fillMaxHeight().leftDashed().padding(horizontal = 4.dp)) {
        for (i in 0 until TaskRepository.PER_DAY) {
            val t = tasks.getOrNull(i)
            val rowMod = Modifier
                .fillMaxWidth()
                .height(TASK_ROW_H)
                .drawBehind {
                    if (i < TaskRepository.PER_DAY - 1) {
                        drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    }
                }
            if (t != null) {
                key(t.id) { TaskRow(t, rowMod, onComplete = { onComplete(t.id) }, onEdit = { onEdit(t) }) }
            } else {
                EmptyTaskRow(rowMod, onAdd)
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task, modifier: Modifier, onComplete: () -> Unit, onEdit: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (checked) 0f else 1f, tween(400), label = "task")
    LaunchedEffect(checked) {
        if (checked) {
            delay(420)
            onComplete()
        }
    }
    Row(modifier.alpha(alpha), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(sdp(20f)).clickable(enabled = !checked) { checked = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(sdp(12f)).border(1.dp, c(Palette.INK), RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Text("✓", fontSize = ssp(8f), lineHeight = ssp(8f), color = c(Palette.INK))
            }
        }
        Text(
            task.text,
            fontSize = ssp(11f),
            fontWeight = FontWeight.Medium,
            color = c(Palette.INK),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (checked) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f).clickable { onEdit() },
        )
    }
}

@Composable
private fun EmptyTaskRow(modifier: Modifier, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val commit = {
        if (text.isNotBlank()) {
            onAdd(text.trim())
            text = ""
        }
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(fontSize = ssp(11f), color = c(Palette.INK)),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().padding(start = 2.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("+", fontSize = ssp(15f), color = c(0xFF9D9686.toInt()))
                inner()
            }
        },
    )
}
