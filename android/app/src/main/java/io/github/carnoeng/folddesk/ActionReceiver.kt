package io.github.carnoeng.folddesk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Taps on widget buttons (complete a task, check a prep item, mark a reply done, switch the day's schedule).
 * The widget changes at once; the change itself is applied by the app, run invisibly, which syncs it to Google.
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != ACT) return
        val a = i.getStringExtra("a") ?: return
        if (a == "nav") { Store.setNav(c, i.getIntExtra("wid", 0), i.getIntExtra("to", 0).coerceIn(0, 7)); Widgets.updateAll(c); return }
        val s = Store.snapshot(c) ?: return
        val act = JSONObject().put("a", a)
        when (a) {
            "task", "follow" -> {
                val id = i.getStringExtra("id") ?: return
                act.put("id", id)
                drop(s.optJSONArray(if (a == "task") "tasks" else "follow")) { it.optString("id") == id }
            }
            "prep" -> {
                val key = i.getStringExtra("key") ?: return
                act.put("key", key)
                val p = s.optJSONObject("prep") ?: return
                val keys = p.optJSONArray("keys") ?: return
                for (k in keys.length() - 1 downTo 0) if (keys.optString(k) == key) { keys.remove(k); p.optJSONArray("items")?.remove(k) }
            }
            "mode" -> {
                val date = i.getStringExtra("date") ?: return; val mode = i.getStringExtra("mode") ?: return
                act.put("date", date).put("mode", mode)
                Sched.day(s, date)?.put("mk", mode)?.put("pending", true)
            }
            else -> return
        }
        Store.saveSnapshot(c, s.toString())
        Store.pushAction(c, act)
        Widgets.updateAll(c)
        Scheduler.runActions(c)
    }

    private fun drop(arr: JSONArray?, f: (JSONObject) -> Boolean) {
        if (arr == null) return
        for (k in arr.length() - 1 downTo 0) if (f(arr.getJSONObject(k))) arr.remove(k)
    }

    companion object {
        const val ACT = "io.github.carnoeng.folddesk.WIDGET_ACT"
        fun pi(c: Context, a: String, key: String, extras: Intent.() -> Unit): PendingIntent {
            val i = Intent(c, ActionReceiver::class.java).setAction(ACT)
                .setData(Uri.parse("folddesk-act://$a/" + Uri.encode(key)))
                .putExtra("a", a).apply(extras)
            return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
