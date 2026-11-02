package io.github.carnoeng.folddesk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

const val ACTION_SNOOZE = "io.github.carnoeng.folddesk.SNOOZE"

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Scheduler.ACTION_TICK -> { Widgets.updateAll(c); Scheduler.schedule(c); Quiet.apply(c); NowNotif.update(c) }
            Scheduler.ACTION_NOTIFY -> {
                Widgets.updateAll(c)
                val t = i.getStringExtra("title") ?: ""; val b = i.getStringExtra("body") ?: ""
                OverlayService.say(c, t, b)
                if (!MainActivity.foreground) Notifier.show(c, i.getIntExtra("code", 1000), t, b)
            }
            ACTION_SNOOZE -> {
                val code = i.getIntExtra("code", 1000)
                androidx.core.app.NotificationManagerCompat.from(c).cancel(code)
                val again = Intent(c, AlarmReceiver::class.java).setAction(Scheduler.ACTION_NOTIFY)
                    .putExtra("title", i.getStringExtra("title")).putExtra("body", i.getStringExtra("body")).putExtra("code", code + 500000)
                val pi = PendingIntent.getBroadcast(c, code + 500000, again, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                c.getSystemService(android.app.AlarmManager::class.java).setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10 * 60_000, pi)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Scheduler.schedule(c)
        Scheduler.ensureWorker(c)
        Widgets.updateAll(c)
        Quiet.apply(c)
        NowNotif.update(c)
        if (OverlayService.enabled(c) && android.provider.Settings.canDrawOverlays(c))
            try { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, OverlayService::class.java)) } catch (_: Exception) { }
    }
}

object Notifier {
    private const val CH = "reminders"
    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "授業・予定のお知らせ", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "授業や予定の前、タスクのリマインド"
                enableVibration(true)
            })
    }
    fun show(c: Context, id: Int, title: String, body: String) {
        ensureChannel(c)
        val open = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java).putExtra("route", "today")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF3B5BFF.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "10分後にもう一度", PendingIntent.getBroadcast(c, id + 900000,
                Intent(c, AlarmReceiver::class.java).setAction(ACTION_SNOOZE).putExtra("code", id).putExtra("title", title).putExtra("body", body),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: SecurityException) { }
    }
}
