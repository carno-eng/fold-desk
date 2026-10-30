package io.github.carnoeng.folddesk

import android.content.Context

/** What the concierge says outside the app, built from the latest snapshot. */
object Talk {
    fun lines(c: Context): List<String> {
        val s = Store.snapshot(c) ?: return listOf("アプリを開くと、今日の予定をお伝えできます。")
        val st = Sched.status(s)
        val out = mutableListOf<String>()
        val mins = if (st.target > 0) ((st.target - System.currentTimeMillis()) / 60000).coerceAtLeast(0) else -1
        out.add(st.line + if (mins >= 0) (if (st.prefix.startsWith("あと")) "、あと${mins}分" else "まで${mins}分") else "")
        if (st.next.isNotEmpty() && st.line.startsWith("いま")) out.add(st.next)
        val talk = s.optJSONArray("talk")
        if (talk != null) for (i in 0 until minOf(talk.length(), 2)) out.add(talk.optString(i))
        return out
    }

    private var lastKey = ""
    /** A reminder whose time is within the last 90 seconds, said once. */
    fun dueReminder(c: Context, now: Long): List<String>? {
        val arr = Store.snapshot(c)?.optJSONArray("reminders") ?: return null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i); val at = r.optLong("at")
            if (now >= at && now - at < 90_000 && r.optString("key") != lastKey) {
                lastKey = r.optString("key"); return listOf(r.optString("title"), r.optString("body"))
            }
        }
        return null
    }
}
