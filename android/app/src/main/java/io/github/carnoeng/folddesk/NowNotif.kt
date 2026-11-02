package io.github.carnoeng.folddesk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * Lock screen "いま・次": a quiet, ongoing notification during the school day with what is on now,
 * a live countdown, and how far through the day you are. Gone outside school hours.
 */
object NowNotif {
    private const val CH = "now"
    private const val ID = 88
    private fun prefs(c: Context) = c.getSharedPreferences("fd", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("nowNotif", false)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("nowNotif", on).apply(); update(c); Scheduler.schedule(c) }

    /** start/end of the window in which the notification is shown today, or null */
    fun window(s: JSONObject?): Pair<Long, Long>? {
        val day = s?.let { Sched.today(it) } ?: return null
        val items = day.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        var a = Long.MAX_VALUE; var b = 0L
        for (i in 0 until items.length()) { val it = items.getJSONObject(i); if (it.optString("k") == "cancel") continue
            a = minOf(a, Sched.at(day.optString("date"), it.optString("s"))); b = maxOf(b, Sched.at(day.optString("date"), it.optString("e"))) }
        if (a >= b) return null
        return Pair(a - 30 * 60_000L, b)
    }

    fun update(c: Context) {
        val nm = NotificationManagerCompat.from(c)
        val s = Store.snapshot(c)
        val w = window(s); val now = System.currentTimeMillis()
        if (!enabled(c) || w == null || now !in w.first..w.second) { nm.cancel(ID); return }
        val sys = c.getSystemService(NotificationManager::class.java)
        if (sys.getNotificationChannel(CH) == null)
            sys.createNotificationChannel(NotificationChannel(CH, "いま・次（ロック画面）", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC; setSound(null, null); enableVibration(false) })
        val st = Sched.status(s)
        val dayStart = w.first + 30 * 60_000L
        val pct = (((now - dayStart).toFloat() / (w.second - dayStart).coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
        val mode = s?.let { Sched.today(it) }?.optString("mode") ?: ""
        val text = when {
            st.target > 0 && st.now -> listOf("${hm(st.target)}まで", st.next).filter { it.isNotEmpty() }.joinToString("  ・  ")
            else -> st.next
        }
        val open = PendingIntent.getActivity(c, 5, Intent(c, MainActivity::class.java).putExtra("route", "today")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_notify).setColor(0xFF3B5BFF.toInt())
            .setContentTitle(st.line).setContentText(text)
            .setSubText(if (mode.isNotEmpty() && mode != "通常") "$mode 時程" else null)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(st.target > 0)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW).setContentIntent(open)
            .setProgress(100, pct, false)
        if (st.target > 0) b.setWhen(st.target).setUsesChronometer(true).setChronometerCountDown(true)
        try { nm.notify(ID, b.build()) } catch (_: SecurityException) { }
    }
    private fun hm(t: Long): String { val c = java.util.Calendar.getInstance().apply { timeInMillis = t }; return "%d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE)) }
}
