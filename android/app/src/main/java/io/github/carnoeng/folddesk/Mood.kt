package io.github.carnoeng.folddesk

import org.json.JSONObject
import java.util.Calendar

/** Same moods as the app's hero: warn near the end of a class, relax in breaks, dusk and night colours after school. */
object Mood {
    class M(val ring: Int, val dashed: Boolean, val label: String?, val bg: Int, val accent: Int, val key: String)

    fun sky(s: JSONObject?): String {
        val c = Calendar.getInstance(); val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val dis = s?.let { Sched.today(it) }?.optString("dismiss")?.split(":")?.let { if (it.size == 2) it[0].toInt() * 60 + it[1].toInt() else null } ?: (15 * 60)
        return when { m >= 18 * 60 + 30 || m < 5 * 60 -> "night"; (m >= dis && m >= 15 * 60) || m >= 17 * 60 -> "evening"; m < 8 * 60 + 15 -> "morning"; else -> "" }
    }

    fun of(s: JSONObject?): M {
        val sky = sky(s)
        val bg = when (sky) { "night" -> R.drawable.bg_hero_night; "evening" -> R.drawable.bg_hero_evening; "morning" -> R.drawable.bg_hero_morning; else -> R.drawable.bg_hero }
        val now = System.currentTimeMillis()
        val day = s?.let { Sched.today(it) }
        val items = day?.optJSONArray("items")
        if (day == null || items == null || items.length() == 0) return M(0xFFFFFFFF.toInt(), false, null, bg, 0xFF9AA8FF.toInt(), sky)
        var first = Long.MAX_VALUE; var last = 0L; var cls: JSONObject? = null; var blk: JSONObject? = null
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i); if (it.optString("k") == "cancel") continue
            val a = Sched.at(day.optString("date"), it.optString("s")); val e = Sched.at(day.optString("date"), it.optString("e"))
            first = minOf(first, a); last = maxOf(last, e)
            if (now in a until e) { if (it.optString("k") == "block") blk = it else cls = it }
        }
        if (cls != null) {
            val left = Sched.at(day.optString("date"), cls.optString("e")) - now
            return when {
                left <= 60_000 -> M(0xFFFF8F8F.toInt(), false, "もうすぐ", bg, 0xFFFF8F8F.toInt(), "last")
                left <= 300_000 -> M(0xFFFFD66B.toInt(), false, "あと5分", bg, 0xFFFFD66B.toInt(), "soon")
                else -> M(0xFFFFFFFF.toInt(), false, null, bg, 0xFF9AA8FF.toInt(), sky)
            }
        }
        if (now in first until last) return M(0xFFA6F2DA.toInt(), true, "休み時間", bg, 0xFFA6F2DA.toInt(), "brk")
        if (now >= last) return M(0xBFFFFFFF.toInt(), false, "おつかれさま", bg, 0xFF9AA8FF.toInt(), "done")
        return M(0xFFFFFFFF.toInt(), false, null, bg, 0xFF9AA8FF.toInt(), sky)
    }
}
