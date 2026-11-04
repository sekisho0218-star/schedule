package jp.sekisho.todayshift

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import jp.sekisho.vertical.shift.ShiftParser
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val INK = Color(0xFF2B2B2B)
private val SUB = Color(0xFF777777)
private val LINE = Color(0xFFD9D4C7)
private val PAPER = Color(0xFFFFFDF7)
private val BG = Color(0xFFF1EEE6)
private val MINE_BG = Color(0xFFFFF4CC)
private val NOW_BG = Color(0xFFFFE9D2)
private val ACCENT = Color(0xFF2F6F6A)

private val DOW = arrayOf("月", "火", "水", "木", "金", "土", "日")

@Composable
fun TodayScreen(vm: MainViewModel) {
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }
    // 今の時間帯の色付けを1分ごとに更新
    val now by produceState(LocalTime.now()) { while (true) { delay(60_000); value = LocalTime.now() } }
    var showSettings by remember { mutableStateOf(vm.url.isBlank()) }

    Column(Modifier.fillMaxSize().background(BG).statusBarsPadding().navigationBarsPadding()) {
        TopBar(vm, onSettings = { showSettings = true })
        val status = when {
            vm.loading -> "読み込み中…"
            vm.error != null -> vm.error!!
            vm.lastFetched > 0 -> "最終更新 " + Instant.ofEpochMilli(vm.lastFetched).atZone(ZoneId.systemDefault()).toLocalTime().let { "${it.hour}:${"%02d".format(it.minute)}" }
            else -> ""
        }
        if (status.isNotEmpty()) {
            Text(status, fontSize = 11.sp, color = if (vm.error != null) Color(0xFFC62828) else SUB, modifier = Modifier.padding(horizontal = 12.dp))
        }
        val day = vm.day
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            when {
                vm.sheet == null -> Message(if (vm.url.isBlank()) "⚙ でシフト表の URL を入力してください" else "⟳ でシフト表を読み込んでください")
                day == null -> Message("この日はシフト表にありません")
                day.rows.all { it.cells.isEmpty() } -> Message("この日はシフトがありません")
                else -> ShiftTable(day, vm.myName, if (day.date == LocalDate.now()) now else null)
            }
        }
    }
    if (showSettings) SettingsDialog(vm, onDismiss = { showSettings = false })
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = SUB, fontSize = 14.sp) }
}

@Composable
private fun TopBar(vm: MainViewModel, onSettings: () -> Unit) {
    val d = vm.date
    val dowColor = when (d.dayOfWeek) { DayOfWeek.SATURDAY -> Color(0xFF3D7FD9); DayOfWeek.SUNDAY -> Color(0xFFE0473D); else -> INK }
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BarButton("‹") { vm.move(-1) }
        Text(
            "${d.monthValue}/${d.dayOfMonth}(${DOW[d.dayOfWeek.value - 1]})",
            fontSize = 19.sp, fontWeight = FontWeight.Medium, color = dowColor,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        BarButton("›") { vm.move(1) }
        if (d != LocalDate.now()) BarButton("今日", wide = true) { vm.today() }
        Spacer(Modifier.weight(1f))
        BarButton("⟳") { vm.refresh() }
        BarButton("⚙", onClick = onSettings)
    }
}

@Composable
private fun BarButton(label: String, wide: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(38.dp).let { if (wide) it else it.width(38.dp) }.clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = if (wide) 10.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = if (wide) 14.sp else 22.sp, color = INK) }
}

/**
 * シフト表と同じ並び：横に講師、縦に時間帯。
 * 画面の幅に全員が入るよう、入りきらないときは講師を2段（以上）に分ける（横スクロールなし）。
 */
@Composable
private fun ShiftTable(day: ShiftParser.DayView, myName: String, now: LocalTime?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val timeW = 42.dp
        val (perTier, tiers) = Layout.tiers(day.teachers.size, (maxWidth - timeW).value, minColDp = 104f)
        val colW = (maxWidth - timeW) / perTier
        val nowRow = now?.let { Layout.nowRow(day, it.hour * 60 + it.minute) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            day.teachers.chunked(perTier).forEach { group ->
                Tier(day, group, perTier, timeW, colW, myName, nowRow)
            }
            if (tiers > 1) Spacer(Modifier.height(4.dp))
        }
    }
}

/** 1段分（講師の一部） */
@Composable
private fun Tier(
    day: ShiftParser.DayView,
    teachers: List<String>,
    perTier: Int,
    timeW: Dp,
    colW: Dp,
    myName: String,
    nowRow: Int?,
) {
    // この段の講師が誰も入っていない時間帯は出さない
    val rows = day.rows.withIndex().filter { (_, r) -> teachers.any { r.cells[it]?.isEmpty == false } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PAPER).border(1.dp, LINE, RoundedCornerShape(10.dp)),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(Color(0xFFFFF8DC)).bottomLine(Color(0xFFF2C230), 2f)) {
            Box(Modifier.width(timeW))
            teachers.forEach { t ->
                val mine = t == myName
                Box(
                    Modifier.width(colW).fillMaxHeight().background(if (mine) MINE_BG else Color.Transparent).leftLine().padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t, fontSize = 14.sp, fontWeight = if (mine) FontWeight.Bold else FontWeight.Medium, color = INK, maxLines = 1)
                }
            }
        }
        if (rows.isEmpty()) {
            Text("この段の講師はシフトがありません", fontSize = 12.sp, color = SUB, modifier = Modifier.padding(8.dp))
        }
        rows.forEach { (i, row) ->
            val isNow = i == nowRow
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(if (isNow) NOW_BG else Color.Transparent).bottomLine(LINE, 1f)) {
                Box(Modifier.width(timeW).fillMaxHeight().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
                    Text(
                        row.label.ifEmpty { "—" }, fontSize = 12.sp, lineHeight = 13.sp, textAlign = TextAlign.Center,
                        color = if (isNow) Color(0xFFB45309) else Color(0xFF555555), fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                teachers.forEach { t ->
                    val mine = t == myName
                    Box(
                        Modifier.width(colW).fillMaxHeight()
                            .background(if (mine && !isNow) MINE_BG.copy(alpha = 0.45f) else Color.Transparent)
                            .leftLine().padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        row.cells[t]?.let { Cell(it) }
                    }
                }
            }
        }
    }
}

/** 1コマ分：学年・名前・科目（シフト表の書き方のまま） */
@Composable
private fun Cell(c: ShiftParser.CellView) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        c.students.forEach { s ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    s.grade?.let { GradeChip(it) }
                    Text(Layout.displayName(s.name), fontSize = 13.sp, color = INK, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                s.subject?.let { Text(it, fontSize = 11.sp, lineHeight = 12.sp, color = Color(0xFF555555), modifier = Modifier.padding(start = 2.dp)) }
            }
        }
        c.group?.let { g ->
            val parts = g.split(" ", limit = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradeChip(parts[0])
                Text(parts.getOrElse(1) { "" } + "（集団）", fontSize = 13.sp, color = INK, fontWeight = FontWeight.Medium)
            }
        }
        c.texts.forEach { Text(it, fontSize = 12.sp, color = INK) }
        c.guests.forEach { (time, title) ->
            Text(
                listOfNotNull(time, title).joinToString(" "), fontSize = 12.sp, color = Color(0xFF8A2E2E),
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFFFBE3E0)).padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun GradeChip(grade: String) {
    val (bg, fg) = when (grade.firstOrNull()) {
        '小' -> Color(0xFFFFF1C2) to Color(0xFF8A6A00)
        '中' -> Color(0xFFDCEBFA) to Color(0xFF1F5D99)
        '高' -> Color(0xFFEADFF7) to Color(0xFF5B3C8C)
        else -> Color(0xFFEEEEEE) to SUB
    }
    Text(
        grade, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = Modifier.padding(end = 3.dp).clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 3.dp),
    )
}

@Composable
private fun SettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(vm.url) }
    var name by remember { mutableStateOf(vm.myName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("シフト表の URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("自分の名前（列を目立たせる）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("「シフト表(NEW)」タブを読み込みます。シフト表は「リンクを知っている全員が閲覧可」にしてください。", fontSize = 12.sp, color = SUB)
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveSettings(url, name); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

private fun Modifier.leftLine() = drawBehind { drawLine(LINE, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }

private fun Modifier.bottomLine(color: Color, width: Float) = drawBehind {
    val w = width.dp.toPx()
    drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}
