package jp.sekisho.vertical.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 週間バーチカルのウィジェット。中身は同じで、置くときの大きさだけが違う3種類
 * （ウィジェットの大きさを後から変えられないランチャー（Xiaomi のタブレットなど）向けに、縦長・大を用意）
 */
open class WeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdater.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // ほかの種類のウィジェットが残っていれば、定期更新は止めない
        if (!hasAnyWidget(context)) WidgetUpdater.cancel(context)
    }

    companion object {
        val receivers = listOf(WeekWidgetReceiver::class.java, WeekWidgetTallReceiver::class.java, WeekWidgetLargeReceiver::class.java)

        fun hasAnyWidget(context: Context): Boolean {
            val m = AppWidgetManager.getInstance(context)
            return receivers.any { m.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
        }
    }
}

/** 縦長（タブレット向け） */
class WeekWidgetTallReceiver : WeekWidgetReceiver()

/** 大（タブレット向け） */
class WeekWidgetLargeReceiver : WeekWidgetReceiver()
