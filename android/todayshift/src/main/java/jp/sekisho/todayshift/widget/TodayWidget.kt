package jp.sekisho.todayshift.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.sekisho.todayshift.MainActivity
import jp.sekisho.todayshift.ShiftRepository
import jp.sekisho.vertical.shift.ShiftParser
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

private val TITLE_H = 26.dp
private val DOW = arrayOf("月", "火", "水", "木", "金", "土", "日")
private fun cp(argb: Long) = ColorProvider(Color(argb))

/** ホーム画面ウィジェット：今日の全員のシフト */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content() }
    }
}

@Composable
private fun Content() {
    val context = LocalContext.current
    // 描き直すたびに、端末に保存してある最新のシフト表を読む
    val repo = ShiftRepository(context)
    val sheet = repo.cached()
    val myName = repo.myName
    val size = LocalSize.current
    val today = LocalDate.now()
    val now = LocalTime.now()
    val day = sheet?.let { ShiftParser.day(it.grid, it.roster, today) }
    val openApp = actionStartActivity<MainActivity>()
    val dowColor = when (today.dayOfWeek.value) { 6 -> 0xFF3D7FD9; 7 -> 0xFFE0473D; else -> 0xFF2B2B2B }

    Column(GlanceModifier.fillMaxSize().cornerRadius(12.dp).background(cp(0xFFFFFDF7))) {
        Row(
            GlanceModifier.fillMaxWidth().height(TITLE_H).background(cp(0xFFF1EEE6)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${today.monthValue}/${today.dayOfMonth}(${DOW[today.dayOfWeek.value - 1]}) 今日のシフト",
                style = TextStyle(color = cp(dowColor), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().clickable(openApp),
            )
            Text(
                "⟳",
                style = TextStyle(color = cp(0xFF2B2B2B), fontSize = 16.sp),
                modifier = GlanceModifier.padding(horizontal = 6.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }
        val message = when {
            sheet == null -> "アプリを開いてシフト表を読み込んでください"
            day == null || day.rows.all { it.cells.isEmpty() } -> "今日はシフトがありません"
            else -> null
        }
        if (message != null || day == null) {
            Box(GlanceModifier.fillMaxSize().clickable(openApp), contentAlignment = Alignment.Center) {
                Text(message ?: "", style = TextStyle(color = cp(0xFF777777), fontSize = 12.sp))
            }
        } else {
            val bitmap = TableRenderer.render(
                day, myName, now.hour * 60 + now.minute,
                size.width.value, (size.height - TITLE_H).value.coerceAtLeast(40f),
                context.resources.displayMetrics.density,
            )
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = "今日の全員のシフト",
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize().clickable(openApp),
            )
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TodayRefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(TodayRefreshWorker.WORK)
    }
}

/** ⟳：シフト表を読み込み直して描き直す */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching { ShiftRepository(context).fetch() }
        TodayWidget().updateAll(context)
    }
}

/** 30分ごとにシフト表を読み込み直し、今の時間帯の色付け・日付の切り替わりも反映 */
class TodayRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = ShiftRepository(applicationContext)
        if (repo.url.isNotBlank()) runCatching { repo.fetch() }
        TodayWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        const val WORK = "today-widget-refresh"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<TodayRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
