package jp.sekisho.vertical.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.sekisho.vertical.data.CalEvent
import jp.sekisho.vertical.data.CalendarInfo
import jp.sekisho.vertical.data.Dates
import jp.sekisho.vertical.data.EventDraft
import jp.sekisho.vertical.data.Task
import jp.sekisho.vertical.shift.ShiftSync
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar

/** 予定ダイアログを開くときの初期値 */
data class EditorState(
    val existing: CalEvent?,
    val date: LocalDate,
    val start: LocalTime = LocalTime.of(9, 0),
    val allDay: Boolean = false,
)

private val DATE_FMT = DateTimeFormatter.ofPattern("M/d（E）", java.util.Locale.JAPAN)
private val TIME_FMT = DateTimeFormatter.ofPattern("H:mm")

@Composable
fun EventEditorDialog(
    state: EditorState,
    calendars: List<CalendarInfo>,
    defaultCalendar: CalendarInfo?,
    onDismiss: () -> Unit,
    onSave: (EventDraft, Long?) -> Unit,
    onDelete: (CalEvent) -> Unit,
) {
    val context = LocalContext.current
    val ev = state.existing
    val init = remember(state) { initialDraft(state) }
    var title by remember { mutableStateOf(init.title) }
    var memo by remember { mutableStateOf(init.description) }
    var allDay by remember { mutableStateOf(init.allDay) }
    var startDate by remember { mutableStateOf(init.startDate) }
    var startTime by remember { mutableStateOf(init.startTime) }
    var endDate by remember { mutableStateOf(init.endDate) }
    var endTime by remember { mutableStateOf(init.endTime) }
    var calendarId by remember { mutableStateOf(ev?.calendarId ?: defaultCalendar?.id) }
    var calMenu by remember { mutableStateOf(false) }
    val readOnly = ev?.recurring == true
    val writable = calendars.filter { it.writable }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ev == null) "予定を追加" else "予定") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("タイトル") },
                    singleLine = true, readOnly = readOnly, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("終日", modifier = Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { if (!readOnly) allDay = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("開始", modifier = Modifier.width(32.dp))
                    OutlinedButton(enabled = !readOnly, onClick = {
                        pickDate(context, startDate) {
                            val diff = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate)
                            startDate = it
                            endDate = it.plusDays(diff.coerceAtLeast(0))
                        }
                    }) { Text(startDate.format(DATE_FMT)) }
                    if (!allDay) OutlinedButton(enabled = !readOnly, onClick = {
                        pickTime(context, startTime) {
                            val dur = java.time.Duration.between(startTime, endTime)
                            startTime = it
                            if (startDate == endDate && !dur.isNegative) {
                                endTime = it.plus(dur).let { e -> if (e.isBefore(it)) LocalTime.of(23, 59) else e }
                            }
                        }
                    }) { Text(startTime.format(TIME_FMT)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("終了", modifier = Modifier.width(32.dp))
                    OutlinedButton(enabled = !readOnly, onClick = {
                        pickDate(context, endDate) { endDate = if (it.isBefore(startDate)) startDate else it }
                    }) { Text(endDate.format(DATE_FMT)) }
                    if (!allDay) OutlinedButton(enabled = !readOnly, onClick = {
                        pickTime(context, endTime) { endTime = it }
                    }) { Text(endTime.format(TIME_FMT)) }
                }
                OutlinedTextField(
                    value = memo, onValueChange = { memo = it }, label = { Text("メモ") },
                    minLines = 2, readOnly = readOnly, modifier = Modifier.fillMaxWidth(),
                )
                if (ev == null) {
                    Box {
                        val cal = writable.firstOrNull { it.id == calendarId }
                        TextButton(onClick = { calMenu = true }) {
                            CalendarDot(cal?.color)
                            Spacer(Modifier.width(6.dp))
                            Text(cal?.let { "${it.name}（${it.accountName}）" } ?: "保存先のカレンダーがありません", fontSize = 13.sp)
                        }
                        DropdownMenu(expanded = calMenu, onDismissRequest = { calMenu = false }) {
                            writable.forEach { c ->
                                DropdownMenuItem(
                                    leadingIcon = { CalendarDot(c.color) },
                                    text = { Text("${c.name}（${c.accountName}）") },
                                    onClick = { calendarId = c.id; calMenu = false },
                                )
                            }
                        }
                    }
                } else {
                    val cal = calendars.firstOrNull { it.id == ev.calendarId }
                    if (cal != null) Text("カレンダー：${cal.name}", fontSize = 12.sp, color = c(Palette.SUB))
                }
                if (readOnly) {
                    Text("繰り返し予定はカレンダーアプリで編集してください。", fontSize = 12.sp, color = c(Palette.SUB))
                    TextButton(onClick = { openInCalendarApp(context, ev!!); onDismiss() }) { Text("カレンダーアプリで開く") }
                }
            }
        },
        confirmButton = {
            if (!readOnly) TextButton(
                enabled = title.isNotBlank() && (ev != null || calendarId != null),
                onClick = {
                    onSave(EventDraft(title, memo, allDay, startDate, startTime, endDate, endTime), calendarId)
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (ev != null && !readOnly) TextButton(onClick = { onDelete(ev); onDismiss() }) {
                    Text("削除", color = c(Palette.SUN))
                }
                TextButton(onClick = onDismiss) { Text(if (readOnly) "閉じる" else "キャンセル") }
            }
        },
    )
}

@Composable
private fun CalendarDot(color: Int?) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color?.let { Color(it) } ?: Color.Gray))
}

private fun initialDraft(s: EditorState): EventDraft {
    val ev = s.existing
    if (ev == null) {
        val end = s.start.plusHours(1).let { if (it.isBefore(s.start)) LocalTime.of(23, 59) else it }
        return EventDraft("", "", s.allDay, s.date, s.start, s.date, end)
    }
    return if (ev.allDay) {
        val sd = Dates.utcDate(ev.begin)
        val ed = Dates.utcDate(ev.end).minusDays(1).let { if (it.isBefore(sd)) sd else it }
        EventDraft(ev.title, ev.description, true, sd, LocalTime.of(9, 0), ed, LocalTime.of(10, 0))
    } else {
        val b = Instant.ofEpochMilli(ev.begin).atZone(Dates.zone)
        val e = Instant.ofEpochMilli(ev.end).atZone(Dates.zone)
        EventDraft(ev.title, ev.description, false, b.toLocalDate(), b.toLocalTime().withSecond(0).withNano(0),
            e.toLocalDate(), e.toLocalTime().withSecond(0).withNano(0))
    }
}

private fun pickDate(context: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
        datePicker.firstDayOfWeek = Calendar.MONDAY
    }.show()
}

private fun pickTime(context: Context, initial: LocalTime, onPicked: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
}

private fun openInCalendarApp(context: Context, ev: CalEvent) {
    val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.eventId))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.end)
    runCatching { context.startActivity(intent) }
}

@Composable
fun TaskEditDialog(task: Task, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: () -> Unit) {
    var text by remember { mutableStateOf(task.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タスク") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(text); onDismiss() }) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onDelete(); onDismiss() }) { Text("完了（削除）", color = c(Palette.SUN)) }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        },
    )
}

@Composable
fun SettingsDialog(
    calendars: List<CalendarInfo>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    tasksGoogle: Boolean,
    tasksConnecting: Boolean,
    onConnectTasks: () -> Unit,
    onDisconnectTasks: () -> Unit,
    shift: ShiftSettingsUi,
    onDismiss: () -> Unit,
) {
    val writable = calendars.filter { it.writable }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("新しい予定の保存先", fontSize = 14.sp)
                if (writable.isEmpty()) {
                    Text(
                        "書き込めるカレンダーがありません。端末の「設定 → アカウント」で Google アカウントを追加し、カレンダーの同期をオンにしてください。",
                        fontSize = 12.sp, color = c(Palette.SUB), modifier = Modifier.padding(top = 6.dp),
                    )
                }
                writable.forEach { cal ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(cal.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = cal.id == selectedId, onClick = { onSelect(cal.id) })
                        CalendarDot(cal.color)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(cal.name, fontSize = 14.sp)
                            Text(cal.accountName, fontSize = 11.sp, color = c(Palette.SUB))
                        }
                    }
                }
                Text(
                    "予定は端末に登録された Google アカウントを通して Google カレンダーと自動で同期されます。「⟳」で今すぐ同期できます。",
                    fontSize = 12.sp, color = c(Palette.SUB), modifier = Modifier.padding(top = 10.dp),
                )

                Text("タスクの同期", fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp))
                Text(
                    if (tasksGoogle) "Google ToDo リストと同期中です。PC版や Google カレンダーと同じタスクが表示されます。"
                    else "タスクはこの端末にだけ保存されています。同期すると PC版と同じタスクを使えます（今あるタスクは Google に移動します）。",
                    fontSize = 12.sp, color = c(Palette.SUB), modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.padding(top = 6.dp)) {
                    if (tasksConnecting) {
                        Text("接続中…", fontSize = 13.sp)
                    } else if (tasksGoogle) {
                        OutlinedButton(onClick = onDisconnectTasks) { Text("同期を解除") }
                    } else {
                        OutlinedButton(onClick = onConnectTasks) { Text("Google ToDo リストと同期") }
                    }
                }

                ShiftSettingsSection(shift, writable)

                val context = LocalContext.current
                val version = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                }
                if (version != null) {
                    Text("バージョン $version", fontSize = 11.sp, color = c(Palette.SUB), modifier = Modifier.padding(top = 14.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}


/* ---------------- シフト表との連携 ---------------- */

/** 設定画面のシフト表の欄 */
data class ShiftSettingsUi(
    val enabled: Boolean,
    val url: String,
    val teacher: String,
    val calendarId: Long,
    val status: String,
    val running: Boolean,
    val onChange: (enabled: Boolean?, url: String?, teacher: String?, calendarId: Long?) -> Unit,
    val onRunNow: () -> Unit,
    val onResetDismissed: () -> Unit,
)

@Composable
private fun ShiftSettingsSection(st: ShiftSettingsUi, writable: List<CalendarInfo>) {
    var url by remember { mutableStateOf(st.url) }
    var teacher by remember { mutableStateOf(st.teacher) }
    var calMenu by remember { mutableStateOf(false) }
    Text("シフト表との連携", fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("シフト表の予定をカレンダーに反映", fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = st.enabled, onCheckedChange = { st.onChange(it, url, teacher, null) })
    }
    OutlinedTextField(
        value = url, onValueChange = { url = it }, label = { Text("シフト表の URL") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && url != st.url) st.onChange(null, url, null, null) },
    )
    OutlinedTextField(
        value = teacher, onValueChange = { teacher = it }, label = { Text("講師名") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .onFocusChanged { if (!it.isFocused && teacher != st.teacher) st.onChange(null, null, teacher, null) },
    )
    Box {
        val cal = writable.firstOrNull { it.id == st.calendarId }
        TextButton(onClick = { calMenu = true }) {
            CalendarDot(cal?.color)
            Spacer(Modifier.width(6.dp))
            Text("反映先：" + (cal?.let { "${it.name}（${it.accountName}）" } ?: "新しい予定の保存先と同じ"), fontSize = 13.sp)
        }
        DropdownMenu(expanded = calMenu, onDismissRequest = { calMenu = false }) {
            DropdownMenuItem(text = { Text("新しい予定の保存先と同じ") }, onClick = { st.onChange(null, null, null, -1L); calMenu = false })
            writable.forEach { c ->
                DropdownMenuItem(
                    leadingIcon = { CalendarDot(c.color) },
                    text = { Text("${c.name}（${c.accountName}）") },
                    onClick = { st.onChange(null, null, null, c.id); calMenu = false },
                )
            }
        }
    }
    Text(
        "「シフト表(NEW)」タブの講師の列を30分ごとに確認します。授業は自動で反映（初回のみ確認）、授業以外とシフト表にない予定は確認してから反映します。",
        fontSize = 12.sp, color = c(Palette.SUB),
    )
    if (st.status.isNotBlank()) Text("前回の照合：${st.status}", fontSize = 12.sp, color = c(Palette.SUB), modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !st.running, onClick = {
            st.onChange(null, url, teacher, null)
            st.onRunNow()
        }) { Text(if (st.running) "照合中…" else "今すぐ照合") }
        TextButton(onClick = st.onResetDismissed) { Text("「反映しない」をリセット", fontSize = 12.sp) }
    }
}

/** シフト表との違い（確認画面） */
@Composable
fun ShiftDialog(
    pending: ShiftSync.Pending,
    applied: List<String>,
    onApply: (applyLessons: Boolean, keys: Set<String>) -> Unit,
    onSkip: () -> Unit,
    onLater: () -> Unit,
) {
    var applyLessons by remember(pending) { mutableStateOf(true) }
    val checked = remember(pending) { mutableStateMapOf<String, Boolean>().apply { pending.proposals.forEach { put(it.key, it.checked) } } }
    val opLabel = mapOf("add" to "追加", "update" to "変更", "delete" to "削除", "remove" to "削除")
    @Composable
    fun Tag(op: String) {
        val (bg, fg) = when (op) {
            "add" -> Color(0xFFE3F1DC) to Color(0xFF2F6B1F)
            "update" -> Color(0xFFFFF1C9) to Color(0xFF7A5B00)
            else -> Color(0xFFFBE1DF) to Color(0xFFA1342B)
        }
        Text(opLabel[op] ?: op, fontSize = 10.sp, color = fg,
            modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 4.dp))
    }
    @Composable
    fun Head(t: String) = Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("シフト表との違い") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (applied.isNotEmpty()) {
                    Head("自動で反映した授業（${applied.size}件）")
                    applied.forEach { Text(it, fontSize = 12.sp) }
                }
                if (pending.lessons.isNotEmpty()) {
                    Head("授業の変更（初回のみ確認。以後は自動で反映）")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = applyLessons, onCheckedChange = { applyLessons = it })
                        Text("次の${pending.lessons.size}件を反映し、今後は授業を自動で反映する", fontSize = 12.sp)
                    }
                    pending.lessons.forEach { op ->
                        Row(verticalAlignment = Alignment.CenterVertically) { Tag(op.op); Text(op.desc, fontSize = 12.sp) }
                    }
                }
                val adds = pending.proposals.filter { it.type == "add" }
                val rms = pending.proposals.filter { it.type == "remove" }
                @Composable
                fun Item(p: ShiftSync.Proposal) = Row(
                    Modifier.fillMaxWidth().clickable { checked[p.key] = !(checked[p.key] ?: false) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked[p.key] ?: false, onCheckedChange = { checked[p.key] = it })
                    Tag(p.type)
                    Text(p.desc, fontSize = 12.sp)
                }
                if (adds.isNotEmpty()) {
                    Head("授業以外は反映しますか？")
                    adds.forEach { Item(it) }
                }
                if (rms.isNotEmpty()) {
                    Head("シフト表にない予定です。削除しますか？")
                    rms.forEach { Item(it) }
                }
                if (pending.notes.isNotEmpty()) {
                    Text("読み取れなかった書き込み：" + pending.notes.take(5).joinToString("、"), fontSize = 11.sp, color = c(Palette.SUB),
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(applyLessons, checked.filterValues { it }.keys) }) { Text("チェックした項目を反映") }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("反映しない") } },
    )
}
