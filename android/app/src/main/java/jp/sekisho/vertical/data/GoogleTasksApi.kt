package jp.sekisho.vertical.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Google ToDo リスト（Tasks API）の「マイタスク」(@default) を読み書きする。
 * タスクの「期限」をその日のタスクとして扱う。
 */
class GoogleTasksApi(private val token: String) {
    data class RemoteTask(val id: String, val title: String, val due: LocalDate, val position: String)

    private val base = "https://tasks.googleapis.com/tasks/v1/lists/@default/tasks"

    /** [from, toExclusive) が期限の未完了タスク */
    fun list(from: LocalDate, toExclusive: LocalDate): List<RemoteTask> {
        val out = mutableListOf<RemoteTask>()
        var pageToken: String? = null
        do {
            val q = buildString {
                append("?showCompleted=false&showHidden=false&maxResults=100")
                append("&dueMin=").append(enc("${from}T00:00:00.000Z"))
                append("&dueMax=").append(enc("${toExclusive.minusDays(1)}T23:59:59.999Z"))
                if (pageToken != null) append("&pageToken=").append(enc(pageToken!!))
            }
            val json = call("GET", base + q, null)
            val items = json.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val due = o.optString("due").takeIf { it.length >= 10 } ?: continue
                    if (o.optString("status") == "completed" || o.optBoolean("deleted")) continue
                    out += RemoteTask(
                        id = o.getString("id"),
                        title = o.optString("title"),
                        due = LocalDate.parse(due.substring(0, 10)),
                        position = o.optString("position"),
                    )
                }
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return out
    }

    fun insert(date: LocalDate, title: String): String {
        val body = JSONObject().put("title", title).put("due", "${date}T00:00:00.000Z")
        return call("POST", base, body).getString("id")
    }

    fun setTitle(id: String, title: String) {
        call("PATCH", "$base/${enc(id)}", JSONObject().put("title", title))
    }

    fun complete(id: String) {
        call("PATCH", "$base/${enc(id)}", JSONObject().put("status", "completed"))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(method: String, url: String, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection は PATCH 非対応のため、Google API の上書きヘッダーを使う
            conn.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("Google Tasks API エラー ($code)")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
