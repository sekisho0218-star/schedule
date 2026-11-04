package jp.sekisho.vertical.data

import android.content.Context
import android.content.SharedPreferences
import jp.sekisho.vertical.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * 日付ごとのタスク（1日最大5件、完了で削除）。
 * - Google 同期オフ：端末内に保存
 * - Google 同期オン：Google ToDo リストの「期限」がその日のタスク。表示は端末内のキャッシュから行い、
 *   変更は先にキャッシュへ反映してから Google に送る（オフラインでも表示が速い）
 */
class TaskRepository(context: Context) {
    private val appContext = context.applicationContext
    private val local = appContext.getSharedPreferences("tasks", Context.MODE_PRIVATE)
    private val cache = appContext.getSharedPreferences("gtasks_cache", Context.MODE_PRIVATE)
    private val prefs = Prefs(appContext)

    val googleMode: Boolean get() = prefs.tasksGoogle
    private val store: SharedPreferences get() = if (googleMode) cache else local

    fun tasks(date: LocalDate): List<Task> = read(store, date)

    fun add(date: LocalDate, text: String): Boolean {
        val list = tasks(date)
        if (text.isBlank()) return false
        // タスクリスト（時刻なし）は1日 PER_DAY 件まで。時刻付きはスケジュールに出るので何件でも
        if (!TaskTime.isTimed(text) && untimedTasks(list).size >= PER_DAY) return false
        val title = text.trim()
        if (!googleMode) {
            write(local, date, list + Task(UUID.randomUUID().toString(), title))
            return true
        }
        // 同じ日・同じ内容の追加が短い間に続いたら、2回目以降は無視（二重登録の防止）
        val key = "$date|$title"
        val now = System.currentTimeMillis()
        synchronized(recentAdds) {
            if ((recentAdds[key] ?: 0L) > now - 10_000) return true
            recentAdds[key] = now
        }
        val tempId = TEMP + UUID.randomUUID()
        write(cache, date, list + Task(tempId, title))
        remote("タスクを追加できませんでした") { api ->
            val id = api.insert(date, title)
            val now = read(cache, date)
            if (now.any { it.id == tempId }) {
                write(cache, date, now.map { if (it.id == tempId) it.copy(id = id) else it })
            } else {
                api.complete(id) // 追加中に完了された
            }
        }
        return true
    }

    fun update(date: LocalDate, id: String, text: String) {
        if (text.isBlank()) return complete(date, id)
        write(store, date, tasks(date).map { if (it.id == id) it.copy(text = text.trim()) else it })
        if (googleMode && !id.startsWith(TEMP)) remote("タスクを変更できませんでした") { it.setTitle(id, text.trim()) }
    }

    /** チェックしたタスクは削除（Google では「完了」にする） */
    fun complete(date: LocalDate, id: String) {
        write(store, date, tasks(date).filterNot { it.id == id })
        if (googleMode && !id.startsWith(TEMP)) remote("タスクを完了にできませんでした") { it.complete(id) }
    }

    /** ウィジェット用：Google への送信まで待つ */
    suspend fun completeAndWait(date: LocalDate, id: String) {
        write(store, date, tasks(date).filterNot { it.id == id })
        if (googleMode && !id.startsWith(TEMP)) {
            val token = GoogleAuth.silentToken(appContext) ?: return DataSignals.error(NEED_LOGIN)
            withContext(Dispatchers.IO) {
                runCatching { GoogleTasksApi(token).complete(id) }.onFailure { DataSignals.error("タスクを完了にできませんでした") }
            }
        }
    }

    /** Google から [from, toExclusive) のタスクを取り直してキャッシュを更新 */
    suspend fun refresh(from: LocalDate, toExclusive: LocalDate): Boolean {
        if (!googleMode) return false
        val token = GoogleAuth.silentToken(appContext) ?: return false
        // 通信は必ずメインスレッド以外で行う（Android ではメインスレッドでの通信は禁止）
        val remote = withContext(Dispatchers.IO) {
            runCatching { GoogleTasksApi(token).list(from, toExclusive) }
        }.getOrElse { return false }
        val byDate = remote.groupBy { it.due }
        val editor = cache.edit()
        var d = from
        while (d.isBefore(toExclusive)) {
            // 位置の値が大きいほど先に作られたタスク（Google は新しいタスクを先頭に追加する）
            val fetched = byDate[d].orEmpty().sortedByDescending { it.position }.map { Task(it.id, it.title) }
            val pending = read(cache, d).filter { it.id.startsWith(TEMP) }
            val list = (fetched + pending).distinctBy { it.id }
            if (list.isEmpty()) editor.remove(d.toString()) else editor.putString(d.toString(), toJson(list))
            d = d.plusDays(1)
        }
        editor.commit()
        notifyChanged()
        return true
    }

    /**
     * Google ToDo リストの重複を整理：同じ日に同じ名前の未完了タスクが複数あれば、1件を残して削除する。
     * @return 削除した件数
     */
    suspend fun removeDuplicates(from: LocalDate, toExclusive: LocalDate): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val token = GoogleAuth.silentToken(appContext) ?: throw IllegalStateException(NEED_LOGIN)
            val api = GoogleTasksApi(token)
            var removed = 0
            api.list(from, toExclusive)
                .groupBy { it.due to it.title.replace(Regex("[\\s\u3000]"), "") }
                .values.filter { it.size > 1 }
                .forEach { g ->
                    // 先に作られたもの（位置の値が大きい）を残す
                    g.sortedByDescending { it.position }.drop(1).forEach { api.delete(it.id); removed++ }
                }
            removed
        }.also { refresh(from, toExclusive) }
    }

    /** Google 同期を始める：端末内のタスクを Google に移してから切り替える */
    suspend fun enableGoogle(token: String): Result<Int> = withContext(Dispatchers.IO) {
      runCatching {
        val api = GoogleTasksApi(token)
        var moved = 0
        local.all.keys.toList().sorted().forEach { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@forEach
            read(local, date).forEach { api.insert(date, it.text); moved++ }
            local.edit().remove(key).commit()
        }
        cache.edit().clear().commit()
        prefs.tasksGoogle = true
        moved
      }
    }

    fun disableGoogle() {
        prefs.tasksGoogle = false
        cache.edit().clear().commit()
        notifyChanged()
    }

    private fun remote(errorMessage: String, block: (GoogleTasksApi) -> Unit) {
        AppScope.launch {
            val token = GoogleAuth.silentToken(appContext)
            if (token == null) {
                DataSignals.error(NEED_LOGIN)
                return@launch
            }
            runCatching { block(GoogleTasksApi(token)) }.onFailure { DataSignals.error(errorMessage) }
            notifyChanged()
        }
    }

    private fun read(sp: SharedPreferences, date: LocalDate): List<Task> {
        val json = sp.getString(date.toString(), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Task(o.getString("id"), o.getString("text"))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(sp: SharedPreferences, date: LocalDate, list: List<Task>) {
        val key = date.toString()
        val editor = sp.edit()
        if (list.isEmpty()) editor.remove(key) else editor.putString(key, toJson(list))
        editor.commit()
        notifyChanged()
    }

    private fun toJson(list: List<Task>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text)) }
        return arr.toString()
    }

    private fun notifyChanged() = WidgetUpdater.refresh(appContext)

    companion object {
        /** タスクリストに表示する件数（時刻のないタスク） */
        const val PER_DAY = 3

        /** 直近に追加したタスク（日付|内容 → 時刻）。二重登録の防止用 */
        private val recentAdds = HashMap<String, Long>()
        private const val TEMP = "tmp-"
        const val NEED_LOGIN = "Google ToDo リストに接続できません。設定（⚙）から再接続してください"
    }
}
