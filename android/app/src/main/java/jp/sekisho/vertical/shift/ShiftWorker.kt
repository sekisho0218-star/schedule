package jp.sekisho.vertical.shift

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.sekisho.vertical.MainActivity
import jp.sekisho.vertical.data.Prefs
import java.util.concurrent.TimeUnit

/** 30分ごとにシフト表と照合する（スマホが使われていないときも） */
class ShiftWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.shiftEnabled || prefs.shiftUrl.isBlank()) return Result.success()
        runCatching { ShiftSync(applicationContext).run() }
            .onSuccess { r -> if (!r.pending.isEmpty) notifyPending(applicationContext, r.pending) }
            .onFailure { prefs.shiftLastMessage = "エラー：${it.message ?: it.javaClass.simpleName}" }
        return Result.success()
    }

    companion object {
        private const val WORK = "shift-sync"
        private const val CHANNEL = "shift"
        const val EXTRA_OPEN_SHIFT = "openShift"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ShiftWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)

        /** 確認が必要な項目があることを通知（同じ内容では何度も通知しない） */
        fun notifyPending(context: Context, pending: ShiftSync.Pending) {
            val prefs = Prefs(context)
            val sig = (pending.lessons.map { it.desc } + pending.proposals.map { it.key }).sorted().joinToString("\n")
            if (sig == prefs.shiftNotified) return
            prefs.shiftNotified = sig
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "シフト表", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_SHIFT, true)
            val pi = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("シフト表")
                .setContentText("反映するか確認が必要な予定が${pending.lessons.size + pending.proposals.size}件あります")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(1001, n) }
        }
    }
}
