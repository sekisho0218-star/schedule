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
}
