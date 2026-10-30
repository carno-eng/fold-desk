package io.github.carnoeng.folddesk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local copy of the latest snapshot the web app computed (schedule, tasks, reminders), plus widget-side state. */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("fd", Context.MODE_PRIVATE)

    fun saveSnapshot(c: Context, json: String) {
        if (json.length > 400_000) return
        try { JSONObject(json) } catch (_: Exception) { return }
        prefs(c).edit().putString("snapshot", json).apply()
    }

    fun snapshot(c: Context): JSONObject? =
        prefs(c).getString("snapshot", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }

    fun codes(c: Context): Set<String> = prefs(c).getStringSet("codes", emptySet()) ?: emptySet()
    fun setCodes(c: Context, s: Set<String>) { prefs(c).edit().putStringSet("codes", s).apply() }

    /* actions taken on widgets, waiting for the app (run invisibly) to apply them */
    private val lock = Any()
    fun pushAction(c: Context, a: JSONObject) = synchronized(lock) {
        val arr = try { JSONArray(prefs(c).getString("actions", "[]")) } catch (_: Exception) { JSONArray() }
        if (arr.length() < 100) arr.put(a)
        prefs(c).edit().putString("actions", arr.toString()).commit()
    }
    fun takeActions(c: Context): String = synchronized(lock) {
        val s = prefs(c).getString("actions", "[]") ?: "[]"
        prefs(c).edit().putString("actions", "[]").commit()
        s
    }
    fun hasActions(c: Context) = (prefs(c).getString("actions", "[]") ?: "[]").length > 2

    /* which day a "today" widget shows (0 = today) */
    fun nav(c: Context, wid: Int): Int {
        val p = prefs(c); val day = p.getString("nav_day_$wid", "")
        return if (day == Sched.todayStr()) p.getInt("nav_$wid", 0) else 0
    }
    fun setNav(c: Context, wid: Int, v: Int) { prefs(c).edit().putInt("nav_$wid", v).putString("nav_day_$wid", Sched.todayStr()).apply() }
}
