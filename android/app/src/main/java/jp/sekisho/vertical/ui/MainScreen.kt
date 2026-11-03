package jp.sekisho.vertical.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.sekisho.vertical.data.DataSignals
import jp.sekisho.vertical.data.Dates
import jp.sekisho.vertical.data.GoogleAuth
import jp.sekisho.vertical.data.Task
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

@Composable
fun MainScreen(vm: MainViewModel) {
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermissionResult()
    }
    val requestPermission = {
        permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
    }
    LaunchedEffect(Unit) { if (!vm.hasPermission) requestPermission() }

    // Google ToDo リストへの接続（初回は Google の許可画面が開く）
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.onTasksAuthorized(GoogleAuth.tokenFromIntent(context, res.data))
    }
    val connectTasks: () -> Unit = {
        scope.launch {
            when (val r = GoogleAuth.authorize(context)) {
                is GoogleAuth.Result.Token -> vm.onTasksAuthorized(r.token)
                is GoogleAuth.Result.NeedsConsent ->
                    authLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent.intentSender).build())
                is GoogleAuth.Result.Error -> vm.message = r.message
            }
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LifecycleResumeEffect(Unit) {
        vm.reload()
        vm.openShiftDialog() // バックグラウンドの照合で確認待ちができていれば表示
        onPauseOrDispose { }
    }

    val taskVersion by DataSignals.version.collectAsStateWithLifecycle()
    val now by produceState(LocalTime.now()) {
        while (true) {
            delay(60_000)
            value = LocalTime.now()
        }
    }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var editingTask by remember { mutableStateOf<Pair<LocalDate, Task>?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        topBar = {
            TopBar(
                title = if (vm.mode == ViewMode.WEEK) Dates.weekTitle(Dates.weekStart(vm.anchor)) else Dates.monthTitle(vm.anchor),
                mode = vm.mode,
                onPrev = { vm.move(-1) },
                onNext = { vm.move(1) },
                onToday = vm::goToday,
                onMode = vm::switchMode,
                onSync = vm::sync,
                onSettings = { showSettings = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = c(Palette.BG),
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (!vm.hasPermission) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFFFFF3CD)).clickable { requestPermission() }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Googleカレンダーの予定を表示するには、カレンダーへのアクセスを許可してください（タップ）",
                        fontSize = 12.sp,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().swipeToMove { vm.move(it) }) {
                when (vm.mode) {
                    ViewMode.WEEK -> WeekScreen(
                        weekStart = Dates.weekStart(vm.anchor),
                        events = vm.events,
                        now = now,
                        taskVersion = taskVersion,
                        tasksOf = vm::tasks,
                        onAddTask = vm::addTask,
                        onCompleteTask = vm::completeTask,
                        onEditTask = { d, t -> editingTask = d to t },
                        onEventClick = { editor = EditorState(existing = it, date = LocalDate.now()) },
                        onAddEvent = { d, t ->
                            if (!vm.hasPermission) requestPermission()
                            else editor = EditorState(existing = null, date = d, start = t ?: LocalTime.of(9, 0), allDay = t == null)
                        },
                    )
                    ViewMode.MONTH -> MonthScreen(
                        anchor = vm.anchor,
                        events = vm.events,
                        taskVersion = taskVersion,
                        tasksOf = vm::tasks,
                        onDayClick = vm::openWeekOf,
                    )
                }
            }
        }
    }

    editor?.let { st ->
        EventEditorDialog(
            state = st,
            calendars = vm.calendars,
            defaultCalendar = vm.defaultCalendar(),
            onDismiss = { editor = null },
            onSave = { draft, calId -> vm.saveEvent(st.existing, draft, calId) },
            onDelete = vm::deleteEvent,
        )
    }
    editingTask?.let { (d, t) ->
        TaskEditDialog(
            task = t,
            onDismiss = { editingTask = null },
            onSave = { vm.updateTask(d, t.id, it) },
            onDelete = { vm.completeTask(d, t.id) },
        )
    }
    if (showSettings) {
        SettingsDialog(
            calendars = vm.calendars,
            selectedId = vm.defaultCalendar()?.id,
            onSelect = vm::setDefaultCalendar,
            tasksGoogle = vm.tasksGoogle,
            tasksConnecting = vm.tasksConnecting,
            onConnectTasks = connectTasks,
            onDisconnectTasks = vm::disconnectTasks,
            shift = ShiftSettingsUi(
                enabled = vm.shiftEnabled,
                url = vm.shiftUrl,
                teacher = vm.shiftTeacher,
                calendarId = vm.shiftCalendarId,
                status = vm.shiftStatus,
                running = vm.shiftRunning,
                onChange = { en, url, teacher, calId ->
                    // 通知で確認画面を開けるよう、オンにするとき通知の許可を求める
                    if (en == true && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    vm.setShiftSettings(en, url, teacher, calId)
                },
                onRunNow = { showSettings = false; vm.runShiftNow() },
                onResetDismissed = vm::resetShiftDismissed,
                onOpenBulkDelete = { showSettings = false; vm.openSyncedList() },
            ),
            onDismiss = { showSettings = false },
        )
    }
    vm.syncedList?.let { list ->
        BulkDeleteDialog(items = list, onDelete = vm::deleteSynced, onDismiss = vm::closeSyncedList)
    }
    vm.shiftDialog?.let { p ->
        ShiftDialog(
            pending = p,
            applied = vm.shiftApplied,
            onApply = vm::applyShift,
            onSkip = { vm.applyShift(false, emptySet()) },
            onLater = vm::closeShiftDialog,
        )
    }
}

/** 左右スワイプで前後の週／月へ */
private fun Modifier.swipeToMove(onMove: (Int) -> Unit) = pointerInput(Unit) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = { if (abs(total) > 120.dp.toPx()) onMove(if (total < 0) 1 else -1) },
    ) { change, dx ->
        total += dx
        change.consume()
    }
}

@Composable
private fun TopBar(
    title: String,
    mode: ViewMode,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onMode: (ViewMode) -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit,
) {
    val ink = c(Palette.INK)
    Column(Modifier.fillMaxWidth().background(c(Palette.BG)).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton("‹", onPrev)
            BarButton("今日", onToday, wide = true)
            BarButton("›", onNext)
            Text(
                title,
                color = ink,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            // 週間／月間の切り替え（グレーの台の上に白い選択中ボタン）
            Row(Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFFE2DED4)).padding(2.dp)) {
                ViewMode.entries.forEach { m ->
                    val active = m == mode
                    Box(
                        Modifier
                            .let { if (active) it.shadow(1.dp, RoundedCornerShape(7.dp)) else it }
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (active) Color.White else Color.Transparent)
                            .clickable { onMode(m) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            if (m == ViewMode.WEEK) "週間" else "月間",
                            fontSize = 13.sp,
                            color = if (active) ink else Color(0xFF555555),
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            BarButton("⟳", onSync)
            BarButton("⚙", onSettings)
        }
    }
}

@Composable
private fun BarButton(label: String, onClick: () -> Unit, wide: Boolean = false) {
    Box(
        Modifier.height(38.dp).let { if (wide) it.padding(horizontal = 2.dp) else it.size(38.dp) }
            .clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = if (wide) 8.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = c(Palette.INK), fontSize = if (wide) 15.sp else 22.sp)
    }
}
