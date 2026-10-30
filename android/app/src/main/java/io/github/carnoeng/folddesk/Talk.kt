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
        if (out.size < 3 || Math.random() < 0.4) out.add(greeting())
        return out
    }

    /** A small cheerful line that depends on the time of day. */
    fun greeting(): String {
        val cal = java.util.Calendar.getInstance()
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val fri = cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY
        val pool = when {
            m < 8 * 60 + 30 -> listOf("おはようございます！今日もいっしょにがんばろうね", "おはよう！いい1日になりますように", "おはようございます！")
            m in (12 * 60 + 20) until (13 * 60 + 30) -> listOf("お昼ごはん、ちゃんと食べた？", "ひと休みできてる？お水も飲んでね", "午後もがんばろうね！")
            m in (15 * 60) until (18 * 60) -> if (fri) listOf("1週間おつかれさまでした！", "金曜日だ！今週もおつかれさま！") else listOf("今日もおつかれさまです！", "おつかれさま！ひと息つこ", "今日もえらい！")
            m >= 18 * 60 + 30 -> listOf("まだお仕事？むりしないでね", "だいじょうぶ？げんき？そろそろ帰ろ〜", "遅くまでおつかれさま…ちゃんと休んでね")
            else -> listOf("だいじょうぶ？げんき？", "ちょっと深呼吸しよっか", "いつも見てるよ、がんばってるね", "あれやった？わすれてなーい？")
        }
        return pool.random()
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
