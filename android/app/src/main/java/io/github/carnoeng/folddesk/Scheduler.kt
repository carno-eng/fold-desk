package io.github.carnoeng.folddesk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Exact alarms for widget refresh at period boundaries and for reminder notifications. */
object Scheduler {
    const val ACTION_TICK = "io.github.carnoeng.folddesk.TICK"
    const val ACTION_NOTIFY = "io.github.carnoeng.folddesk.NOTIFY"

    fun ensureWorker(c: Context) {
        val req = PeriodicWorkRequestBuilder<SnapshotWorker>(2, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("snapshot", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun schedule(c: Context) {
        val s = Store.snapshot(c) ?: return
        scheduleTick(c, s)
        scheduleReminders(c, s)
    }

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun exact(c: Context, at: Long, pi: PendingIntent) {
        val am = am(c)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun pending(c: Context, code: Int, i: Intent) =
        PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun scheduleTick(c: Context, s: JSONObject) {
        val now = System.currentTimeMillis()
        var next = Long.MAX_VALUE
        Sched.today(s)?.let { day ->
            val items = day.optJSONArray("items") ?: return@let
            for (k in 0 until items.length()) {
                val it = items.getJSONObject(k)
                for (key in listOf("s", "e")) {
                    val t = Sched.at(day.optString("date"), it.optString(key))
                    if (t > now + 1000 && t < next) next = t
                }
            }
        }
        // keep the ring and day bar fresh every 5 minutes between the first and last item of the day
        Sched.today(s)?.let { day ->
            val items = day.optJSONArray("items")
            if (items != null && items.length() > 0) {
                val first = Sched.at(day.optString("date"), items.getJSONObject(0).optString("s")) - 3_600_000
                var lastEnd = 0L
                for (k in 0 until items.length()) lastEnd = maxOf(lastEnd, Sched.at(day.optString("date"), items.getJSONObject(k).optString("e")))
                if (now in first..lastEnd && Widgets.hasHero(c)) next = minOf(next, now + 5 * 60_000)
            }
        }
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5)
        }.timeInMillis
        if (next > midnight) next = midnight
        exact(c, next + 500, pending(c, 1, Intent(c, AlarmReceiver::class.java).setAction(ACTION_TICK)))
    }

    private fun scheduleReminders(c: Context, s: JSONObject) {
        val am = am(c)
        Store.codes(c).forEach { code ->
            am.cancel(pending(c, code.toInt(), Intent(c, AlarmReceiver::class.java).setAction(ACTION_NOTIFY)))
        }
        val now = System.currentTimeMillis()
        val codes = mutableSetOf<String>()
        val arr = s.optJSONArray("reminders") ?: return Store.setCodes(c, codes)
        for (k in 0 until minOf(arr.length(), 40)) {
            val r = arr.getJSONObject(k)
            val at = r.optLong("at")
            if (at <= now) continue
            val code = 1000 + (r.optString("key").hashCode() and 0x7ffff)
            val i = Intent(c, AlarmReceiver::class.java).setAction(ACTION_NOTIFY)
                .putExtra("title", r.optString("title")).putExtra("body", r.optString("body")).putExtra("code", code)
            exact(c, at, pending(c, code, i))
            codes.add(code.toString())
        }
        Store.setCodes(c, codes)
    }
}
