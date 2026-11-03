package jp.sekisho.vertical.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import jp.sekisho.vertical.MainActivity
import jp.sekisho.vertical.R
import jp.sekisho.vertical.data.CalendarRepository
import jp.sekisho.vertical.data.DataSignals
import jp.sekisho.vertical.data.Dates
import jp.sekisho.vertical.data.TaskRepository
import jp.sekisho.vertical.data.eventsForDay
import jp.sekisho.vertical.ui.Palette
import java.time.LocalDate
import java.time.LocalTime


class WeekWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version by DataSignals.version.collectAsState()
            WidgetContent(version)
        }
    }
}

private fun cp(argb: Int) = ColorProvider(Color(argb))

/** タスク欄の罫線（薄く目立たない色） */
private const val TASK_RULE = 0xFFEBE6DB.toInt()

@Composable
private fun WidgetContent(version: Long) {
    val context = LocalContext.current
    val size = LocalSize.current
    val today = LocalDate.now()
    val now = LocalTime.now()
    val weekStart = Dates.weekStart(today)
    val days = (0L..6L).map { weekStart.plusDays(it) }

    val calendarRepo = remember { CalendarRepository(context) }
    val taskRepo = remember { TaskRepository(context) }
    val hasPermission = calendarRepo.hasReadPermission()
    val dayEvents = remember(version, weekStart) {
        val ev = calendarRepo.instances(weekStart, weekStart.plusDays(7))
        days.associateWith { eventsForDay(ev, it) }
    }
    val tasks = remember(version, weekStart) { days.associateWith { taskRepo.tasks(it) } }

    // 広い画面（Galaxy Z Fold の内側画面など）では文字と行を大きくする
    val ts = WeekBitmapRenderer.textScale(size.width.value)
    val titleH = (28f * ts.coerceAtMost(1.3f)).dp
    val rowH = (19f * ts).dp
    val axisW = WeekBitmapRenderer.axisDp(size.width.value).dp
    val tasksH = rowH * TaskRepository.PER_DAY + (TaskRepository.PER_DAY - 1).dp
    val imageH = (size.height - titleH - tasksH).coerceAtLeast(60.dp)
    // 画面の解像度どおりに描いて文字のぼやけを防ぐ（RGB_565 でメモリを節約）
    val scale = context.resources.displayMetrics.density.coerceAtMost(3.5f)
    val bitmap = remember(version, size, today, now.hour, now.minute / 15) {
        WeekBitmapRenderer.render(size.width.value, imageH.value, scale, days, dayEvents, today, now)
    }
    val openApp = actionStartActivity<MainActivity>()

    Column(GlanceModifier.fillMaxSize().cornerRadius(12.dp).background(cp(Palette.PAPER))) {
        // タイトル行
        Row(
            GlanceModifier.fillMaxWidth().height(titleH).background(cp(Palette.BG)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Dates.weekTitle(weekStart),
                style = TextStyle(color = cp(Palette.INK), fontSize = (13f * ts.coerceAtMost(1.3f)).sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().clickable(openApp),
            )
            Text(
                "⟳",
                style = TextStyle(color = cp(Palette.INK), fontSize = (16f * ts.coerceAtMost(1.3f)).sp),
                modifier = GlanceModifier.padding(horizontal = 6.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }

        if (!hasPermission) {
            Text(
                "アプリを開いてカレンダーへのアクセスを許可してください",
                style = TextStyle(color = cp(Palette.SUB), fontSize = 10.sp),
                modifier = GlanceModifier.fillMaxWidth().padding(4.dp).clickable(openApp),
            )
        }

        // 週間バーチカル（画像）
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "今週の予定",
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight().clickable(openApp),
        )

        // タスク（各日5件・チェックで削除）。行と日の間に目立たない罫線を引く
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(cp(Palette.LINE))) {}
        Row(GlanceModifier.fillMaxWidth().height(tasksH)) {
            Box(GlanceModifier.width(axisW).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("TASK", style = TextStyle(color = cp(Palette.SUB), fontSize = (8f * ts).sp))
            }
            days.forEach { d ->
              // Glance の Row は子要素10個までなので、罫線と1日分の列をまとめる
              Row(GlanceModifier.defaultWeight().fillMaxHeight()) {
                // 日の区切り（縦の罫線）
                Box(GlanceModifier.width(1.dp).fillMaxHeight().background(cp(TASK_RULE))) {}
                Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
                    val list = tasks[d].orEmpty()
                    for (i in 0 until TaskRepository.PER_DAY) {
                        val t = list.getOrNull(i)
                        Row(
                            GlanceModifier.fillMaxWidth().height(rowH),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (t != null) {
                                Box(
                                    GlanceModifier.size(rowH).clickable(
                                        actionRunCallback<CompleteTaskAction>(
                                            actionParametersOf(KeyDate to d.toString(), KeyTaskId to t.id),
                                        ),
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        provider = ImageProvider(R.drawable.ic_task_box),
                                        contentDescription = "完了",
                                        modifier = GlanceModifier.size((12f * ts).dp),
                                    )
                                }
                                Text(
                                    t.text,
                                    style = TextStyle(color = cp(Palette.INK), fontSize = (10f * ts).sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    modifier = GlanceModifier.defaultWeight().clickable(openApp),
                                )
                            } else {
                                Spacer(GlanceModifier.fillMaxWidth().fillMaxHeight().clickable(openApp))
                            }
                        }
                        // 行の区切り（横の罫線）
                        if (i < TaskRepository.PER_DAY - 1) {
                            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(cp(TASK_RULE))) {}
                        }
                    }
                }
              }
            }
        }
    }
}
