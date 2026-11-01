package jp.sekisho.vertical.widget

import android.content.Context
import android.provider.CalendarContract
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.sekisho.vertical.data.DataSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.TimeUnit

object WidgetUpdater {
    private const val PERIODIC = "widget-periodic"
    private const val CAL_WATCH = "widget-calendar-watch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** タスク・予定の変更をすぐウィジェットに反映 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        DataSignals.bump()
        scope.launch { runCatching { WeekWidget().updateAll(app) } }
    }

    /** 30分ごとの更新（現在時刻ライン・日付の切り替わり）と、カレンダー変更の監視 */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build(),
        )
        watchCalendar(context, ExistingWorkPolicy.KEEP)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(CAL_WATCH)
    }

    internal fun watchCalendar(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(3))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CAL_WATCH,
            policy,
            OneTimeWorkRequestBuilder<CalendarWatchWorker>().setConstraints(constraints).build(),
        )
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DataSignals.bump()
        WeekWidget().updateAll(applicationContext)
        return Result.success()
    }
}

/** Googleカレンダーの同期などで端末のカレンダーが変わったら起動し、次の変更の監視を再登録する */
class CalendarWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DataSignals.bump()
        WeekWidget().updateAll(applicationContext)
        WidgetUpdater.watchCalendar(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}
