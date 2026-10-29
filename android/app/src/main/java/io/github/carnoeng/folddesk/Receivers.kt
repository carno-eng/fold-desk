package io.github.carnoeng.folddesk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Scheduler.ACTION_TICK -> { Widgets.updateAll(c); Scheduler.schedule(c) }
            Scheduler.ACTION_NOTIFY -> {
                Widgets.updateAll(c)
                if (!MainActivity.foreground) Notifier.show(c, i.getIntExtra("code", 1000), i.getStringExtra("title") ?: "", i.getStringExtra("body") ?: "")
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Scheduler.schedule(c)
        Scheduler.ensureWorker(c)
        Widgets.updateAll(c)
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
            .setColor(0xFF0A62F5.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: SecurityException) { }
    }
}
