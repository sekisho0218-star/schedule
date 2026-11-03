package jp.sekisho.vertical.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 新しい予定を保存するカレンダー（-1 = 自動選択） */
    var defaultCalendarId: Long
        get() = sp.getLong("defaultCalendarId", -1L)
        set(v) = sp.edit().putLong("defaultCalendarId", v).apply()

    var viewMode: String
        get() = sp.getString("viewMode", "WEEK") ?: "WEEK"
        set(v) = sp.edit().putString("viewMode", v).apply()

    /* ---- シフト表との連携 ---- */
    var shiftEnabled: Boolean
        get() = sp.getBoolean("shiftEnabled", false)
        set(v) = sp.edit().putBoolean("shiftEnabled", v).apply()
    var shiftUrl: String
        get() = sp.getString("shiftUrl", "") ?: ""
        set(v) = sp.edit().putString("shiftUrl", v).apply()
    var shiftTeacher: String
        get() = sp.getString("shiftTeacher", "赤松") ?: "赤松"
        set(v) = sp.edit().putString("shiftTeacher", v).apply()
    /** 反映先カレンダー（-1 = 新しい予定の保存先と同じ） */
    var shiftCalendarId: Long
        get() = sp.getLong("shiftCalendarId", -1L)
        set(v) = sp.edit().putLong("shiftCalendarId", v).apply()
    /** 初回は授業の変更も確認してから反映 */
    var shiftConfirmedOnce: Boolean
        get() = sp.getBoolean("shiftConfirmedOnce", false)
        set(v) = sp.edit().putBoolean("shiftConfirmedOnce", v).apply()
    /** 「反映しない」を選んだ項目（次回から聞かない） */
    var shiftDismissed: Set<String>
        get() = sp.getStringSet("shiftDismissed", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("shiftDismissed", v).apply()
    /** 確認待ちの内容（JSON） */
    var shiftPending: String
        get() = sp.getString("shiftPending", "") ?: ""
        set(v) = sp.edit().putString("shiftPending", v).commit().let { }
    var shiftLastMessage: String
        get() = sp.getString("shiftLastMessage", "") ?: ""
        set(v) = sp.edit().putString("shiftLastMessage", v).apply()
    var shiftLastRun: Long
        get() = sp.getLong("shiftLastRun", 0L)
        set(v) = sp.edit().putLong("shiftLastRun", v).apply()
    /** 前回通知した確認待ちの内容（同じ内容で何度も通知しない） */
    var shiftNotified: String
        get() = sp.getString("shiftNotified", "") ?: ""
        set(v) = sp.edit().putString("shiftNotified", v).apply()

    /** タスクを Google ToDo リストと同期する */
    var tasksGoogle: Boolean
        get() = sp.getBoolean("tasksGoogle", false)
        set(v) = sp.edit().putBoolean("tasksGoogle", v).commit().let { }
}
