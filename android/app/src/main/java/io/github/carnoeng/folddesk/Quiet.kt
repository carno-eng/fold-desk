package io.github.carnoeng.folddesk

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Optional: Do Not Disturb (priority only) during class and meeting periods, restored afterwards. */
object Quiet {
    private fun prefs(c: Context) = c.getSharedPreferences("fd", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("quiet", false)

    fun setEnabled(c: Context, on: Boolean): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (on && !nm.isNotificationPolicyAccessGranted) {
            c.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        prefs(c).edit().putBoolean("quiet", on).apply()
        apply(c)
        return true
    }

    fun inClass(c: Context): Boolean {
        val s = Store.snapshot(c) ?: return false
        val day = Sched.today(s) ?: return false
        val items = day.optJSONArray("items") ?: return false
        val now = System.currentTimeMillis()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            if (it.optString("k") !in setOf("class", "joho", "meeting")) continue
            if (now >= Sched.at(day.optString("date"), it.optString("s")) && now < Sched.at(day.optString("date"), it.optString("e"))) return true
        }
        return false
    }

    fun apply(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val p = prefs(c)
        val weSet = p.getBoolean("quietSet", false)
        if (!nm.isNotificationPolicyAccessGranted) return
        val want = enabled(c) && inClass(c)
        if (want && !weSet && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY); p.edit().putBoolean("quietSet", true).apply()
        } else if (!want && weSet) {
            if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            p.edit().putBoolean("quietSet", false).apply()
        }
    }
}
