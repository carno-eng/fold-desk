package io.github.carnoeng.folddesk

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pomodoro timer that keeps running when the app is closed: an ongoing notification with a live countdown
 * (lock screen too), an exact alarm at the end, and the same state rules as the web app (pomo in index.html).
 */
object Pomo {
    const val A_END = "io.github.carnoeng.folddesk.POMO_END"
    const val A_PAUSE = "io.github.carnoeng.folddesk.POMO_PAUSE"
    const val A_RESUME = "io.github.carnoeng.folddesk.POMO_RESUME"
    const val A_STOP = "io.github.carnoeng.folddesk.POMO_STOP"
    const val A_GO = "io.github.carnoeng.folddesk.POMO_GO"
    const val A_SKIP = "io.github.carnoeng.folddesk.POMO_SKIP"
    private const val CH = "pomo"; private const val CH_ALERT = "pomo_alert"
    private const val ID = 77; private const val ID_ALERT = 78

    private fun prefs(c: Context) = c.getSharedPreferences("fd", Context.MODE_PRIVATE)
    fun get(c: Context): JSONObject = try { JSONObject(prefs(c).getString("pomo", null) ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun put(c: Context, s: JSONObject) { s.put("at", System.currentTimeMillis()); prefs(c).edit().putString("pomo", s.toString()).apply() }

    /** From the web app: take its state (if newer) and reschedule. */
    fun set(c: Context, json: String) {
        val n = try { JSONObject(json) } catch (_: Exception) { return }
        if (n.optLong("at") < get(c).optLong("at")) return
        prefs(c).edit().putString("pomo", n.toString()).apply(); apply(c)
    }

    private fun cfg(s: JSONObject) = s.optJSONObject("cfg") ?: JSONObject()
    private fun dur(s: JSONObject, m: String) = (when (m) { "focus" -> cfg(s).optInt("f", 25); "long" -> cfg(s).optInt("l", 15); else -> cfg(s).optInt("s", 5) }) * 60_000L
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun remain(s: JSONObject): Long { val e = s.optLong("end"); return if (e > 0) maxOf(0, e - System.currentTimeMillis()) else s.optLong("left") }
    private fun isBrk(s: JSONObject) = s.optString("mode") == "short" || s.optString("mode") == "long"

    /** If the current phase is over, move to the next one. Returns true when something changed. */
    fun due(c: Context, fromApp: Boolean): Boolean {
        val s = get(c); val end = s.optLong("end")
        if (s.optString("mode", "idle") == "idle" || end <= 0 || end > System.currentTimeMillis() + 500) return false
        val now = System.currentTimeMillis()
        val title: String; val body: String
        if (s.optString("mode") == "focus") {
            var log = s.optJSONObject("log") ?: JSONObject()
            if (log.optString("d") != today()) log = JSONObject().put("d", today()).put("n", 0).put("min", 0)
            log.put("n", log.optInt("n") + 1).put("min", log.optInt("min") + (s.optLong("full") / 60_000L).toInt()); s.put("log", log)
            val round = s.optInt("round") + 1; s.put("round", round)
            val next = if (round % cfg(s).optInt("n", 4).coerceAtLeast(1) == 0) "long" else "short"
            s.put("mode", next).put("full", dur(s, next))
            if (cfg(s).optBoolean("auto", true)) s.put("end", now + dur(s, next)).put("left", 0).put("rdy", false)
            else s.put("end", 0).put("left", dur(s, next)).put("rdy", true)
            title = "集中おわり！"; body = "今日${log.optInt("n")}回目。" + (if (next == "long") "長めの休憩" else "休憩") + (if (cfg(s).optBoolean("auto", true)) "が始まりました" else "しましょう")
        } else {
            s.put("mode", "focus").put("full", dur(s, "focus")).put("end", 0).put("left", dur(s, "focus")).put("rdy", true)
            title = "休憩おわり"; body = "次の集中をはじめましょう"
        }
        put(c, s); apply(c)
        if (!fromApp && !MainActivity.foreground) { alert(c, title, body); try { OverlayService.say(c, title, body) } catch (_: Exception) { } }
        return true
    }

    fun action(c: Context, a: String) {
        val s = get(c); val now = System.currentTimeMillis()
        when (a) {
            A_END -> { due(c, false); return }
            A_PAUSE -> if (s.optLong("end") > 0) s.put("left", remain(s)).put("end", 0)
            A_RESUME -> if (s.optLong("end") == 0L && s.optString("mode", "idle") != "idle") s.put("end", now + s.optLong("left")).put("left", 0).put("rdy", false)
            A_GO -> { val m = s.optString("mode", "focus").let { if (it == "idle") "focus" else it }; val f = if (s.optLong("full") > 0) s.optLong("full") else dur(s, m)
                s.put("mode", m).put("full", f).put("end", now + f).put("left", 0).put("rdy", false) }
            A_SKIP -> s.put("mode", "focus").put("full", dur(s, "focus")).put("end", 0).put("left", dur(s, "focus")).put("rdy", true)
            A_STOP -> { s.put("mode", "idle").put("end", 0).put("left", 0).put("full", 0).put("round", 0).put("rdy", false) }
        }
        NotificationManagerCompat.from(c).cancel(ID_ALERT)
        put(c, s); apply(c)
    }

    private fun pi(c: Context, a: String, code: Int) = PendingIntent.getBroadcast(c, code, Intent(c, AlarmReceiver::class.java).setAction(a), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "ポモドーロ（カウント中）", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC; setSound(null, null); enableVibration(false) })
        if (nm.getNotificationChannel(CH_ALERT) == null) nm.createNotificationChannel(NotificationChannel(CH_ALERT, "ポモドーロ（終わりのお知らせ）", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 300, 160, 300, 160, 500); lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC })
    }

    private fun open(c: Context) = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java).putExtra("route", "pomo")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun alert(c: Context, title: String, body: String) {
        channels(c); val s = get(c)
        val b = NotificationCompat.Builder(c, CH_ALERT).setSmallIcon(R.drawable.ic_notify).setColor(if (isBrk(s)) 0xFF12A594.toInt() else 0xFFE5484D.toInt())
            .setContentTitle(title).setContentText(body).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setContentIntent(open(c))
        if (s.optBoolean("rdy")) b.addAction(0, if (isBrk(s)) "休憩をはじめる" else "集中をはじめる", pi(c, A_GO, 71))
        try { NotificationManagerCompat.from(c).notify(ID_ALERT, b.build()) } catch (_: SecurityException) { }
    }

    /** Ongoing notification + the end alarm, from the stored state. */
    fun apply(c: Context) {
        val s = get(c); val nm = NotificationManagerCompat.from(c); val am = c.getSystemService(AlarmManager::class.java)
        val endPi = pi(c, A_END, 70); am.cancel(endPi)
        val mode = s.optString("mode", "idle")
        if (mode == "idle") { nm.cancel(ID); return }
        channels(c)
        val end = s.optLong("end"); val brk = isBrk(s); val task = s.optString("task")
        if (end > 0) {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setAlarmClock(AlarmManager.AlarmClockInfo(end, open(c)), endPi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, endPi)
        }
        val left = remain(s); val mm = (left / 60_000).toInt(); val ss = ((left / 1000) % 60).toInt()
        val title = when { end > 0 && brk -> if (mode == "long") "長い休憩中" else "休憩中"; end > 0 -> "集中中"; s.optBoolean("rdy") -> if (brk) "休憩の準備OK" else "次の集中の準備OK"; else -> "一時停止中  %d:%02d".format(mm, ss) }
        val log = s.optJSONObject("log"); val n = if (log?.optString("d") == today()) log.optInt("n") else 0
        val b = NotificationCompat.Builder(c, CH).setSmallIcon(R.drawable.ic_notify).setColor(if (brk) 0xFF12A594.toInt() else 0xFFE5484D.toInt())
            .setContentTitle(title).setContentText(listOf(if (!brk) task else "", if (n > 0) "今日${n}回" else "").filter { it.isNotEmpty() }.joinToString("　").ifEmpty { "ポモドーロ" })
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH).setPriority(NotificationCompat.PRIORITY_LOW).setContentIntent(open(c))
        val full = s.optLong("full").coerceAtLeast(1)
        if (end > 0) { b.setWhen(end).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true).setProgress(1000, (1000 * (full - left) / full).toInt(), false)
            b.addAction(0, "一時停止", pi(c, A_PAUSE, 72)) }
        else if (s.optBoolean("rdy")) b.addAction(0, "はじめる", pi(c, A_GO, 73)).setShowWhen(false)
        else b.addAction(0, "再開", pi(c, A_RESUME, 74)).setShowWhen(false)
        b.addAction(0, "おわる", pi(c, A_STOP, 75))
        try { nm.notify(ID, b.build()) } catch (_: SecurityException) { }
    }
}

/** Countdown timer (タイマー tab): live countdown in the notification shade, an alarm-style alert at the end. */
object Timer {
    const val A_END = "io.github.carnoeng.folddesk.TIMER_END"
    const val A_PAUSE = "io.github.carnoeng.folddesk.TIMER_PAUSE"
    const val A_RESUME = "io.github.carnoeng.folddesk.TIMER_RESUME"
    const val A_STOP = "io.github.carnoeng.folddesk.TIMER_STOP"
    private const val ID = 79; private const val ID_ALERT = 80
    private fun prefs(c: Context) = c.getSharedPreferences("fd", Context.MODE_PRIVATE)
    fun get(c: Context): JSONObject = try { JSONObject(prefs(c).getString("timer", null) ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun put(c: Context, s: JSONObject) { s.put("at", System.currentTimeMillis()); prefs(c).edit().putString("timer", s.toString()).apply() }
    fun set(c: Context, json: String) {
        val n = try { JSONObject(json) } catch (_: Exception) { return }
        if (n.optLong("at") < get(c).optLong("at")) return
        prefs(c).edit().putString("timer", n.toString()).apply(); apply(c)
    }
    fun due(c: Context, fromApp: Boolean): Boolean {
        val s = get(c); val end = s.optLong("end")
        if (end <= 0 || end > System.currentTimeMillis() + 500) return false
        s.put("end", 0).put("left", 0).put("done", true); put(c, s); apply(c)
        if (!fromApp && !MainActivity.foreground) {
            val t = (s.optString("label").ifEmpty { "タイマー" }) + " 終了"
            val b = NotificationCompat.Builder(c, "pomo_alert").setSmallIcon(R.drawable.ic_notify).setColor(0xFF2C6A55.toInt())
                .setContentTitle(t).setContentText("時間になりました").setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setContentIntent(open(c))
            try { NotificationManagerCompat.from(c).notify(ID_ALERT, b.build()) } catch (_: SecurityException) { }
            try { OverlayService.say(c, t, "時間になりました") } catch (_: Exception) { }
        }
        return true
    }
    fun action(c: Context, a: String) {
        val s = get(c); val now = System.currentTimeMillis()
        when (a) {
            A_END -> { due(c, false); return }
            A_PAUSE -> if (s.optLong("end") > 0) s.put("left", maxOf(1000, s.optLong("end") - now)).put("end", 0)
            A_RESUME -> if (s.optLong("end") == 0L && s.optLong("left") > 0) s.put("end", now + s.optLong("left")).put("left", 0)
            A_STOP -> s.put("end", 0).put("left", 0).put("done", false)
        }
        NotificationManagerCompat.from(c).cancel(ID_ALERT); put(c, s); apply(c)
    }
    private fun pi(c: Context, a: String, code: Int) = PendingIntent.getBroadcast(c, code, Intent(c, AlarmReceiver::class.java).setAction(a), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun open(c: Context) = PendingIntent.getActivity(c, 8, Intent(c, MainActivity::class.java).putExtra("route", "cd")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun apply(c: Context) {
        val s = get(c); val nm = NotificationManagerCompat.from(c); val am = c.getSystemService(AlarmManager::class.java)
        val endPi = pi(c, A_END, 90); am.cancel(endPi)
        val end = s.optLong("end"); val left = s.optLong("left")
        if (end <= 0 && left <= 0) { nm.cancel(ID); return }
        Pomo.channels(c)
        if (end > 0) {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setAlarmClock(AlarmManager.AlarmClockInfo(end, open(c)), endPi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, endPi)
        }
        val full = s.optLong("full").coerceAtLeast(1); val rem = if (end > 0) maxOf(0, end - System.currentTimeMillis()) else left
        val b = NotificationCompat.Builder(c, "pomo").setSmallIcon(R.drawable.ic_notify).setColor(0xFF2C6A55.toInt())
            .setContentTitle(if (end > 0) (s.optString("label").ifEmpty { "タイマー" }) else "タイマー 一時停止中  %d:%02d".format(rem / 60_000, (rem / 1000) % 60))
            .setContentText(if (end > 0) "のこり時間" else "再開で続きから").setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_STOPWATCH).setPriority(NotificationCompat.PRIORITY_LOW).setContentIntent(open(c))
        if (end > 0) b.setWhen(end).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true).setProgress(1000, (1000 * (full - rem) / full).toInt(), false).addAction(0, "一時停止", pi(c, A_PAUSE, 91))
        else b.setShowWhen(false).addAction(0, "再開", pi(c, A_RESUME, 92))
        b.addAction(0, "やめる", pi(c, A_STOP, 93))
        try { nm.notify(ID, b.build()) } catch (_: SecurityException) { }
    }
}
