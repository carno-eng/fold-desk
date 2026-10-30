package io.github.carnoeng.folddesk

import android.content.Context
import java.util.Calendar

/** What the concierge says outside the app (home-screen wallpaper and full-screen mode), built from the latest snapshot. */
object Talk {
    /** Said when she is tapped. */
    fun lines(c: Context): List<String> {
        val s = Store.snapshot(c) ?: return listOf("アプリを開くと、今日の予定をお伝えできます。")
        val st = Sched.status(s)
        val out = mutableListOf<String>()
        val mins = if (st.target > 0) ((st.target - System.currentTimeMillis()) / 60000).coerceAtLeast(0) else -1
        out.add(st.line + if (mins >= 0) (if (st.prefix.startsWith("あと")) "、あと${mins}分" else "まで${mins}分") else "")
        if (st.next.isNotEmpty() && st.line.startsWith("いま")) out.add(st.next)
        val talk = s.optJSONArray("talk")
        if (talk != null) for (i in 0 until minOf(talk.length(), 2)) out.add(talk.optString(i))
        if (out.size < 3 || Math.random() < 0.4) out.add(greeting().second)
        return out
    }

    /** A small cheerful line for the time of day, with a key so each kind is said once a day. */
    fun greeting(): Pair<String, String> {
        val cal = Calendar.getInstance()
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val fri = cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        return when {
            m < 8 * 60 + 30 -> "hi:am" to listOf("おはようございます！今日もいっしょにがんばろうね", "おはよう！いい1日になりますように", "おはようございます！今日の予定、見ておく？").random()
            m in (12 * 60 + 20) until (13 * 60 + 30) -> "hi:lunch" to listOf("お昼ごはん、ちゃんと食べた？", "ひと休みできてる？お水も飲んでね", "午後もがんばろうね！").random()
            m in (15 * 60) until (18 * 60) -> "hi:after" to (if (fri) listOf("1週間おつかれさまでした！", "金曜日だ！今週もおつかれさま！") else listOf("今日もおつかれさまです！", "おつかれさま！ひと息つこ", "今日もえらい！")).random()
            m >= 18 * 60 + 30 -> "hi:late" to listOf("まだお仕事？むりしないでね", "だいじょうぶ？げんき？そろそろ帰ろ〜", "遅くまでおつかれさま…ちゃんと休んでね").random()
            else -> "hi:check" to listOf("だいじょうぶ？げんき？", "ちょっと深呼吸しよっか", "いつも見てるよ、がんばってるね", "あれやった？わすれてなーい？").random()
        }
    }

    /** pose for the last thing proactive() returned */
    @Volatile var pose = "wave"

    private fun prefs(c: Context) = c.getSharedPreferences("fd_talk", Context.MODE_PRIVATE)

    /**
     * Something to say on her own, or null. Same rules as in the app: one topic at a time, a gap between topics,
     * a daily cap, each topic once a day, at most two greetings a day, and quiet during class (except "starting soon").
     * Shared by the wallpaper and the full-screen mode so she never repeats herself between them.
     */
    @Synchronized fun proactive(c: Context): List<String>? {
        if (MainActivity.foreground) return null
        val s = Store.snapshot(c) ?: return null
        val (gapMin, cap) = when (s.optString("talkLevel", "normal")) { "often" -> 10 to 10; "low" -> 60 to 3; "off" -> return null; else -> 25 to 6 }
        val p = prefs(c); val today = Sched.todayStr(); val now = System.currentTimeMillis()
        if (p.getString("day", "") != today) p.edit().clear().putString("day", today).apply()
        val seen = p.getStringSet("seen", emptySet())!!.toMutableSet()
        fun take(key: String, lines: List<String>): List<String> {
            pose = if (key == "hi:am" || key == "hi:after") "bow" else if (key.startsWith("hi:")) "cheeks" else "wave"
            seen.add(key)
            p.edit().putStringSet("seen", seen).putLong("last", now).putInt("count", p.getInt("count", 0) + 1).apply()
            return lines
        }
        // 1) the next item starts within 5 minutes (allowed even right after another topic)
        val st = Sched.status(s)
        if (st.target > 0 && !st.now && st.target - now in 0..5 * 60_000) {
            val key = "soon:" + st.line
            if (key !in seen) {
                val what = st.line.removePrefix("次は ")
                val m = ((st.target - now) / 60000).coerceAtLeast(1)
                return take(key, listOf(listOf("もうすぐ「$what」だよ！", "そろそろ「$what」の時間です！", "あと${m}分で「$what」だよ〜").random()))
            }
        }
        if (Quiet.inClass(c)) return null
        if (now - p.getLong("last", 0) < gapMin * 60_000L || p.getInt("count", 0) >= cap) return null
        // 2) topics the app prepared (tasks due, replies, class prep, schedule switched by the school calendar, tomorrow)
        s.optJSONArray("topics")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i); val key = t.optString("key")
                if (key.isNotEmpty() && key !in seen && t.optString("text").isNotEmpty()) return take(key, listOf(t.optString("text")))
            }
        }
        // 3) a greeting for the time of day
        val (gk, gt) = greeting()
        if (gk !in seen && seen.count { it.startsWith("hi:") } < 2) return take(gk, listOf(gt))
        return null
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
