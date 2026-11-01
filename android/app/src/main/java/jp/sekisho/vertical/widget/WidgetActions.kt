package jp.sekisho.vertical.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import jp.sekisho.vertical.data.CalendarRepository
import jp.sekisho.vertical.data.DataSignals
import jp.sekisho.vertical.data.TaskRepository
import java.time.LocalDate

val KeyDate = ActionParameters.Key<String>("date")
val KeyTaskId = ActionParameters.Key<String>("taskId")

/** ウィジェットのチェックボックス：タスクを完了（削除）する */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val date = parameters[KeyDate] ?: return
        val id = parameters[KeyTaskId] ?: return
        TaskRepository(context).complete(LocalDate.parse(date), id)
        WeekWidget().update(context, glanceId)
    }
}

/** ⟳：Google との同期を依頼して再描画 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CalendarRepository(context).requestSync()
        DataSignals.bump()
        WeekWidget().update(context, glanceId)
    }
}
