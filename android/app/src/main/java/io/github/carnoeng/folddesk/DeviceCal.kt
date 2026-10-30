package io.github.carnoeng.folddesk

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Calendars of the Google accounts on this phone (personal and school), read through Android's calendar provider.
 * Nothing leaves the phone: the app shows these events but never saves them to its own data.
 */
object DeviceCal {
    fun granted(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    private fun canWrite(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(c: Context): String {
        val out = JSONArray()
        if (!granted(c)) return out.toString()
        val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.SYNC_EVENTS)
        try {
            c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, null, null, null)?.use { cu ->
                while (cu.moveToNext()) {
                    out.put(JSONObject().put("id", cu.getLong(0)).put("name", cu.getString(1) ?: "").put("account", cu.getString(2) ?: "")
                        .put("type", cu.getString(3) ?: "").put("color", "#%06X".format(cu.getInt(4) and 0xFFFFFF))
                        .put("write", cu.getInt(5) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR && canWrite(c))
                        .put("owner", cu.getString(6) ?: "").put("sync", cu.getInt(7) == 1))
                }
            }
        } catch (_: Exception) { }
        return out.toString()
    }

    private val dayF get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val hmF get() = SimpleDateFormat("HH:mm", Locale.US)

    /** Events from [fromDate] for [days] days in the given calendars. */
    fun events(c: Context, fromDate: String, days: Int, idsJson: String): String {
        val out = JSONArray()
        if (!granted(c)) return out.toString()
        val ids = try { JSONArray(idsJson).let { a -> (0 until a.length()).map { a.getLong(it) } } } catch (_: Exception) { emptyList() }
        if (ids.isEmpty()) return out.toString()
        val start = try { dayF.parse(fromDate)!!.time } catch (_: Exception) { System.currentTimeMillis() }
        val end = start + days.coerceIn(1, 120) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, start - 86_400_000L); ContentUris.appendId(it, end) }.build()
        val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.STATUS)
        val sel = "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",")})"
        val utc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        try {
            c.contentResolver.query(uri, cols, sel, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cu ->
                while (cu.moveToNext() && out.length() < 800) {
                    if (cu.getInt(8) == CalendarContract.Events.STATUS_CANCELED) continue
                    val evId = cu.getLong(0); val b = cu.getLong(1); val e = cu.getLong(2); val all = cu.getInt(4) == 1
                    val base = JSONObject().put("eid", evId).put("title", (cu.getString(3) ?: "").take(120)).put("place", (cu.getString(5) ?: "").take(80))
                        .put("cal", cu.getLong(6)).put("calendar", (cu.getString(7) ?: "").take(40))
                    if (all) {
                        // all-day: UTC midnights, end exclusive; one entry per day
                        var d = b
                        while (d < e && out.length() < 800) {
                            val ds = utc.format(d)
                            if (d + 86_400_000L > start) out.put(JSONObject(base.toString()).put("id", "$evId-$ds").put("date", ds).put("time", "").put("endTime", "").put("allDay", true))
                            d += 86_400_000L
                        }
                    } else {
                        if (e < start) continue
                        val sameDay = dayF.format(b) == dayF.format(e - 1)
                        out.put(JSONObject(base.toString()).put("id", "$evId-$b").put("date", dayF.format(b)).put("time", hmF.format(b))
                            .put("endTime", if (sameDay) hmF.format(e) else "").put("allDay", false))
                    }
                }
            }
        } catch (_: Exception) { }
        return out.toString()
    }

    /** Adds an event to a calendar on the phone (it syncs to that Google account). */
    fun add(c: Context, calId: Long, json: String): Boolean {
        if (!canWrite(c)) return false
        return try {
            val ev = JSONObject(json)
            val title = ev.optString("title").trim().take(200); val date = ev.optString("date")
            if (title.isEmpty() || !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) return false
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.EVENT_LOCATION, ev.optString("place").take(200)); put(CalendarContract.Events.DESCRIPTION, ev.optString("note").take(2000))
            }
            val time = ev.optString("time")
            if (Regex("^\\d{2}:\\d{2}$").matches(time)) {
                val st = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$date $time")!!.time
                var en = st + 3_600_000L
                val et = ev.optString("endTime")
                if (Regex("^\\d{2}:\\d{2}$").matches(et)) { val x = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("$date $et")!!.time; if (x > st) en = x }
                v.put(CalendarContract.Events.DTSTART, st); v.put(CalendarContract.Events.DTEND, en); v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            } else {
                val utc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val st = utc.parse(date)!!.time
                v.put(CalendarContract.Events.DTSTART, st); v.put(CalendarContract.Events.DTEND, st + 86_400_000L)
                v.put(CalendarContract.Events.ALL_DAY, 1); v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            }
            val uri = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return false
            val rem = ev.optInt("remindMin", 0)
            if (rem > 0 && v.getAsInteger(CalendarContract.Events.ALL_DAY) == null) {
                c.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri)); put(CalendarContract.Reminders.MINUTES, rem.coerceAtMost(1440))
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
            true
        } catch (_: Exception) { false }
    }
}
