package io.github.carnoeng.folddesk

import android.content.Context
import org.json.JSONObject

/** Local copy of the latest snapshot the web app computed (schedule, tasks, reminders). */
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
}
