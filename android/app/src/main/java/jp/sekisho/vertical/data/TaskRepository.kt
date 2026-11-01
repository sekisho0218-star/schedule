package jp.sekisho.vertical.data

import android.content.Context
import jp.sekisho.vertical.widget.WidgetUpdater
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** 日付ごとのタスク（最大5件）。端末内に保存する */
class TaskRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tasks", Context.MODE_PRIVATE)

    fun tasks(date: LocalDate): List<Task> {
        val json = prefs.getString(date.toString(), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Task(o.getString("id"), o.getString("text"))
            }
        }.getOrDefault(emptyList())
    }

    fun add(date: LocalDate, text: String): Boolean {
        val list = tasks(date)
        if (list.size >= PER_DAY || text.isBlank()) return false
        save(date, list + Task(UUID.randomUUID().toString(), text.trim()))
        return true
    }

    fun update(date: LocalDate, id: String, text: String) {
        if (text.isBlank()) return complete(date, id)
        save(date, tasks(date).map { if (it.id == id) it.copy(text = text.trim()) else it })
    }

    /** チェックしたタスクは削除する */
    fun complete(date: LocalDate, id: String) {
        save(date, tasks(date).filterNot { it.id == id })
    }

    private fun save(date: LocalDate, list: List<Task>) {
        val key = date.toString()
        val editor = prefs.edit()
        if (list.isEmpty()) {
            editor.remove(key)
        } else {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text)) }
            editor.putString(key, arr.toString())
        }
        editor.commit()
        WidgetUpdater.refresh(appContext)
    }

    companion object {
        const val PER_DAY = 5
    }
}
