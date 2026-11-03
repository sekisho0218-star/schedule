package jp.sekisho.vertical.data

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 端末のカレンダー（CalendarContract）を読み書きする。
 * Android に登録した Google アカウントのカレンダーは OS が自動で Google と同期するため、
 * ここで書き込んだ予定は Google カレンダーにも反映される。
 */
class CalendarRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cr: ContentResolver = appContext.contentResolver

    fun hasReadPermission() = granted(Manifest.permission.READ_CALENDAR)
    fun hasWritePermission() = granted(Manifest.permission.WRITE_CALENDAR)

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<CalendarInfo> {
        if (!hasReadPermission()) return emptyList()
        val proj = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_COLOR,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.IS_PRIMARY,
        )
        val out = mutableListOf<CalendarInfo>()
        runCatching {
            cr.query(Calendars.CONTENT_URI, proj, "${Calendars.VISIBLE} = 1", null, null)?.use { c ->
                while (c.moveToNext()) {
                    out += CalendarInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        accountName = c.getString(2) ?: "",
                        accountType = c.getString(3) ?: "",
                        color = c.getInt(4),
                        writable = c.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                        primary = !c.isNull(6) && c.getInt(6) == 1,
                    )
                }
            }
        }
        return out
    }

    /** 新規予定の保存先。設定で選んだものがなければ Google のメインカレンダーを優先 */
    fun defaultCalendar(preferredId: Long): CalendarInfo? {
        val writable = calendars().filter { it.writable }
        return writable.firstOrNull { it.id == preferredId }
            ?: writable.firstOrNull { it.isGoogle && it.primary }
            ?: writable.firstOrNull { it.isGoogle }
            ?: writable.firstOrNull()
    }

    /** [from, toExclusive) の予定（繰り返し予定は展開済み） */
    fun instances(from: LocalDate, toExclusive: LocalDate): List<CalEvent> {
        if (!hasReadPermission()) return emptyList()
        // 終日予定は UTC 基準なので前後1日広めに取得し、日付判定は eventsForDay で行う
        val begin = Dates.startMillis(from.minusDays(1))
        val end = Dates.startMillis(toExclusive.plusDays(1))
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.DESCRIPTION,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.DISPLAY_COLOR,
            Instances.CALENDAR_ID,
            Instances.RRULE,
            Instances.ORIGINAL_ID,
        )
        val selection = "${Instances.VISIBLE} = 1 AND " +
            "(${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})"
        val out = mutableListOf<CalEvent>()
        runCatching {
            cr.query(uri, proj, selection, null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    out += CalEvent(
                        eventId = c.getLong(0),
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(タイトルなし)",
                        description = c.getString(2) ?: "",
                        begin = c.getLong(3),
                        end = c.getLong(4),
                        allDay = c.getInt(5) == 1,
                        color = c.getInt(6),
                        calendarId = c.getLong(7),
                        recurring = !c.isNull(8) || !c.isNull(9),
                        series = !c.isNull(8),
                    )
                }
            }
        }
        return out
    }

    fun insert(calendarId: Long, draft: EventDraft): Boolean {
        if (!hasWritePermission()) return false
        val values = toValues(draft).apply { put(Events.CALENDAR_ID, calendarId) }
        return runCatching { cr.insert(Events.CONTENT_URI, values) != null }.getOrDefault(false)
    }

    fun update(eventId: Long, draft: EventDraft): Boolean {
        if (!hasWritePermission()) return false
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        return runCatching { cr.update(uri, toValues(draft), null, null) > 0 }.getOrDefault(false)
    }

    fun delete(eventId: Long): Boolean {
        if (!hasWritePermission()) return false
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        return runCatching { cr.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    /** 予定を1回分だけ変更（繰り返し予定はその回だけの例外を作る） */
    fun updateInstance(ev: CalEvent, draft: EventDraft): Boolean {
        if (!ev.series) return update(ev.eventId, draft)
        if (!hasWritePermission()) return false
        val values = toValues(draft).apply { put(Events.ORIGINAL_INSTANCE_TIME, ev.begin) }
        val uri = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, ev.eventId)
        return runCatching { cr.insert(uri, values) != null }.getOrDefault(false)
    }

    /** 予定を1回分だけ削除（繰り返し予定はその回だけ取り消す） */
    fun deleteInstance(ev: CalEvent): Boolean {
        if (!ev.series) return delete(ev.eventId)
        if (!hasWritePermission()) return false
        val values = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, ev.begin)
            put(Events.STATUS, Events.STATUS_CANCELED)
        }
        val uri = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, ev.eventId)
        return runCatching { cr.insert(uri, values) != null }.getOrDefault(false)
    }

    /** Google アカウントの同期を今すぐ実行するよう OS に依頼する */
    fun requestSync(): Int {
        val accounts = calendars().filter { it.isGoogle }
            .map { Account(it.accountName, it.accountType) }
            .distinct()
        accounts.forEach { acc ->
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            runCatching { ContentResolver.requestSync(acc, CalendarContract.AUTHORITY, extras) }
        }
        return accounts.size
    }

    private fun toValues(d: EventDraft) = ContentValues().apply {
        put(Events.TITLE, d.title.trim())
        put(Events.DESCRIPTION, d.description)
        if (d.allDay) {
            val end = if (d.endDate.isBefore(d.startDate)) d.startDate else d.endDate
            put(Events.ALL_DAY, 1)
            put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.DTSTART, d.startDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            put(Events.DTEND, end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        } else {
            val zone = Dates.zone
            val s = LocalDateTime.of(d.startDate, d.startTime).atZone(zone).toInstant().toEpochMilli()
            var e = LocalDateTime.of(d.endDate, d.endTime).atZone(zone).toInstant().toEpochMilli()
            if (e <= s) e = s + 60 * 60_000
            put(Events.ALL_DAY, 0)
            put(Events.EVENT_TIMEZONE, zone.id)
            put(Events.DTSTART, s)
            put(Events.DTEND, e)
        }
    }
}
