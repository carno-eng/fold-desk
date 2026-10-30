package io.github.carnoeng.folddesk

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.updateAll(c)
}
class NextWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.updateAll(c)
}
class TasksWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.updateAll(c)
}
open class FdWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.updateAll(c)
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: android.os.Bundle) = Widgets.updateAll(c)
}
class HeroWidget : FdWidget()
class WeekWidget : FdWidget()
class PrepWidget : FdWidget()
class EventsWidget : FdWidget()
class RemindWidget : FdWidget()
class FollowWidget : FdWidget()
class BriefWidget : FdWidget()
class AddWidget : FdWidget()

/** Schedule helpers shared by widgets and alarms. Times are "HH:MM" on a "yyyy-MM-dd" date, local time. */
object Sched {
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun todayStr(): String = dayFmt.format(Calendar.getInstance().time)
    fun today(s: JSONObject): JSONObject? = day(s, todayStr())
    fun day(s: JSONObject, d: String): JSONObject? {
        val days = s.optJSONArray("days") ?: return null
        for (i in 0 until days.length()) if (days.getJSONObject(i).optString("date") == d) return days.getJSONObject(i)
        return null
    }
    fun at(date: String, hm: String): Long {
        val p = hm.split(":"); val d = date.split("-")
        if (p.size < 2 || d.size < 3) return 0
        return Calendar.getInstance().apply {
            set(d[0].toInt(), d[1].toInt() - 1, d[2].toInt(), p[0].toInt(), p[1].toInt(), 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    fun name(it: JSONObject): String {
        val n = it.optInt("n"); val t = it.optString("t"); val sub = it.optString("sub")
        return when (it.optString("k")) {
            "cancel" -> "${n}時間目 なし"
            "class", "joho", "meeting", "free" -> "${n}時間目 $t" + (if (sub.isNotEmpty()) " $sub" else "")
            else -> t
        }
    }

    class Status(val line: String, val target: Long, val prefix: String, val next: String)

    fun status(s: JSONObject?): Status {
        if (s == null) return Status("アプリを開くと表示されます", 0, "", "")
        val now = System.currentTimeMillis()
        val day = today(s)
        val items = day?.optJSONArray("items")
        if (day != null && items != null && items.length() > 0) {
            var cur: JSONObject? = null; var nxt: JSONObject? = null
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i); if (it.optString("k") == "cancel") continue
                val st = at(day.optString("date"), it.optString("s")); val en = at(day.optString("date"), it.optString("e"))
                if (now in st until en && (cur == null || cur.optString("k") == "block")) cur = it
                if (st > now && nxt == null && it.optString("k") != "block") nxt = it
            }
            val nextLine = nxt?.let { "次は ${name(it)}  ${it.optString("s")}" } ?: ""
            if (cur != null) return Status("いま ${name(cur)}", at(day.optString("date"), cur.optString("e")), "あと %s", nextLine)
            if (nxt != null) return Status("次は ${name(nxt)}", at(day.optString("date"), nxt.optString("s")), "%s 後", "${nxt.optString("s")}〜${nxt.optString("e")}")
            return Status("今日の日程は終わりました", 0, "", "")
        }
        val n = nextDay(s)
        val first = n?.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("k") != "block" } }
        return Status("今日は授業がありません", 0, "", if (n != null && first != null) "次は ${n.optString("label")} ${name(first)}" else "")
    }
    fun nextDay(s: JSONObject): JSONObject? {
        val days = s.optJSONArray("days") ?: return null; val t = todayStr()
        for (i in 0 until days.length()) { val d = days.getJSONObject(i)
            if (d.optString("date") > t && (d.optJSONArray("items")?.length() ?: 0) > 0) return d }
        return null
    }
}

object Widgets {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val DIM = 0x99FFFFFF.toInt()
    private const val WARN = 0xFFFFC9C2.toInt()
    private const val RED = 0xFFE5483B.toInt()

    private inline fun <reified T : Any> ids(c: Context, m: AppWidgetManager) = m.getAppWidgetIds(ComponentName(c, T::class.java))

    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        val s = Store.snapshot(c)
        ids<TodayWidget>(c, m).forEach { m.updateAppWidget(it, today(c, s)) }
        ids<NextWidget>(c, m).forEach { m.updateAppWidget(it, next(c, s)) }
        ids<TasksWidget>(c, m).forEach { m.updateAppWidget(it, tasks(c, s)) }
        ids<HeroWidget>(c, m).forEach { m.updateAppWidget(it, hero(c, s, widthPx(c, m, it))) }
        ids<WeekWidget>(c, m).forEach { val (w, h) = sizePx(c, m, it); m.updateAppWidget(it, week(c, s, w, h)) }
        ids<PrepWidget>(c, m).forEach { m.updateAppWidget(it, prep(c, s)) }
        ids<EventsWidget>(c, m).forEach { m.updateAppWidget(it, events(c, s)) }
        ids<RemindWidget>(c, m).forEach { m.updateAppWidget(it, reminders(c, s)) }
        ids<FollowWidget>(c, m).forEach { m.updateAppWidget(it, follow(c, s)) }
        ids<BriefWidget>(c, m).forEach { m.updateAppWidget(it, brief(c, s)) }
        ids<AddWidget>(c, m).forEach { m.updateAppWidget(it, RemoteViews(c.packageName, R.layout.widget_add).apply { setOnClickPendingIntent(R.id.root, open(c, "ask")) }) }
    }

    fun hasHero(c: Context): Boolean {
        val m = AppWidgetManager.getInstance(c)
        return ids<HeroWidget>(c, m).isNotEmpty() || ids<TodayWidget>(c, m).isNotEmpty() || ids<NextWidget>(c, m).isNotEmpty()
    }

    private fun sizePx(c: Context, m: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = m.getAppWidgetOptions(id); val d = c.resources.displayMetrics.density
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300).coerceAtLeast(120)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200).coerceAtLeast(100)
        return Pair((w * d).toInt(), (h * d).toInt())
    }
    private fun widthPx(c: Context, m: AppWidgetManager, id: Int) = sizePx(c, m, id).first

    fun open(c: Context, route: String): PendingIntent =
        PendingIntent.getActivity(c, route.hashCode() and 0xffff,
            Intent(c, MainActivity::class.java).putExtra("route", route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun headerDate(): String {
        val cal = Calendar.getInstance()
        val wd = arrayOf("日", "月", "火", "水", "木", "金", "土")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日（$wd）"
    }

    private fun applyStatus(v: RemoteViews, st: Sched.Status, lineId: Int, chronoId: Int, subId: Int) {
        v.setTextViewText(lineId, st.line)
        if (st.target > 0) {
            val base = SystemClock.elapsedRealtime() + (st.target - System.currentTimeMillis())
            v.setChronometer(chronoId, base, st.prefix, true)
            v.setChronometerCountDown(chronoId, true)
            v.setViewVisibility(chronoId, View.VISIBLE)
        } else v.setViewVisibility(chronoId, View.GONE)
        v.setTextViewText(subId, st.next)
        v.setViewVisibility(subId, if (st.next.isEmpty()) View.GONE else View.VISIBLE)
    }

    private fun stale(s: JSONObject?): String {
        val at = s?.optLong("at") ?: return ""
        val h = (System.currentTimeMillis() - at) / 3_600_000
        return if (h >= 6) "更新 ${h}時間前" else ""
    }

    /** The blue "today" sheet from the app: date, now/next, live countdown inside the ring, and the day bar. */
    fun hero(c: Context, s: JSONObject?, widthPx: Int): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_hero)
        v.setOnClickPendingIntent(R.id.root, open(c, "today"))
        val cal = Calendar.getInstance()
        val hero = s?.optJSONObject("hero")
        v.setTextViewText(R.id.eyebrow, hero?.optString("eyebrow") ?: "${cal.get(Calendar.YEAR)}年")
        v.setTextViewText(R.id.date, "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日")
        val st = Sched.status(s)
        v.setTextViewText(R.id.status, st.line)
        val subLine = when {
            st.target > 0 && st.line.startsWith("いま") -> "${hhmm(st.target)}まで" + (if (st.next.isNotEmpty()) "　" + st.next else "")
            else -> st.next.ifEmpty { hero?.optString("holiday") ?: "" }
        }
        v.setTextViewText(R.id.sub, subLine)
        val mode = hero?.optString("modeLabel") ?: ""
        v.setTextViewText(R.id.mode, mode); v.setViewVisibility(R.id.mode, if (mode.isEmpty()) View.GONE else View.VISIBLE)
        // ring + countdown
        val day = s?.let { Sched.today(it) }
        var frac = 0f; var idle = true
        if (st.target > 0 && day != null) {
            idle = false
            val now = System.currentTimeMillis()
            if (st.line.startsWith("いま")) {
                val cur = findAround(day, now)
                frac = if (cur != null) ((now - cur.first).toFloat() / (cur.second - cur.first).coerceAtLeast(1)) else 0f
            } else frac = 1f - ((st.target - now) / 600_000f).coerceIn(0f, 1f)
            v.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() + (st.target - now), "%s", true)
            v.setChronometerCountDown(R.id.chrono, true)
            v.setViewVisibility(R.id.chrono, View.VISIBLE); v.setViewVisibility(R.id.clock, View.GONE)
            v.setTextViewText(R.id.ringlabel, if (st.line.startsWith("いま")) "のこり" else "まで")
        } else {
            v.setViewVisibility(R.id.chrono, View.GONE); v.setViewVisibility(R.id.clock, View.VISIBLE)
            v.setTextViewText(R.id.ringlabel, "いま")
        }
        v.setImageViewBitmap(R.id.ring, Paint2.ring(c, frac, idle))
        // day bar
        val d = c.resources.displayMetrics.density
        val bar = Paint2.dayBar(c, day, (widthPx - 36 * d).toInt())
        if (bar != null) {
            v.setImageViewBitmap(R.id.bar, bar); v.setViewVisibility(R.id.bar, View.VISIBLE)
            val items = day!!.optJSONArray("items")!!
            v.setTextViewText(R.id.barstart, items.getJSONObject(0).optString("s"))
            val dis = day.optString("dismiss")
            v.setTextViewText(R.id.barend, listOf(if (dis.isNotEmpty()) "下校 $dis" else "", stale(s)).filter { it.isNotEmpty() }.joinToString("  ・  "))
            v.setViewVisibility(R.id.barlabels, View.VISIBLE)
        } else { v.setViewVisibility(R.id.bar, View.GONE); v.setViewVisibility(R.id.barlabels, View.GONE) }
        return v
    }
    private fun hhmm(t: Long): String { val c = Calendar.getInstance().apply { timeInMillis = t }; return "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }
    private fun findAround(day: JSONObject, now: Long): Pair<Long, Long>? {
        val items = day.optJSONArray("items") ?: return null
        var best: Pair<Long, Long>? = null
        for (i in 0 until items.length()) { val it = items.getJSONObject(i); if (it.optString("k") == "cancel") continue
            val a = Sched.at(day.optString("date"), it.optString("s")); val e = Sched.at(day.optString("date"), it.optString("e"))
            if (now in a until e && (best == null || it.optString("k") != "block")) best = Pair(a, e) }
        return best
    }

    fun today(c: Context, s: JSONObject?): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_today)
        v.setOnClickPendingIntent(R.id.root, open(c, "today"))
        v.setOnClickPendingIntent(R.id.add, open(c, "ask"))
        v.setTextViewText(R.id.date, headerDate())
        applyStatus(v, Sched.status(s), R.id.status, R.id.chrono, R.id.sub)
        v.removeAllViews(R.id.rows)
        val day0 = s?.let { Sched.today(it) }
        val day = if ((day0?.optJSONArray("items")?.length() ?: 0) > 0) day0 else s?.let { Sched.nextDay(it) }
        v.setTextViewText(R.id.mode, day?.let { (if (it !== day0) it.optString("label") + " " else "") + it.optString("mode") } ?: "")
        v.setTextViewText(R.id.foot, listOf(day?.optString("dismiss")?.takeIf { it.isNotEmpty() }?.let { "下校 $it" } ?: "", stale(s)).filter { it.isNotEmpty() }.joinToString("  ・  "))
        val items = day?.optJSONArray("items")
        val date = day?.optString("date") ?: ""
        val now = System.currentTimeMillis()
        if (items != null) {
            var shown = 0
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                if (it.optString("k") == "block" || shown >= 9) continue
                shown++
                val row = RemoteViews(c.packageName, R.layout.widget_row)
                val st = Sched.at(date, it.optString("s")); val en = Sched.at(date, it.optString("e"))
                val k = it.optString("k")
                row.setTextViewText(R.id.no, if (it.optInt("n") > 0) it.optInt("n").toString() else "・")
                val title = if (k == "cancel") SpannableString(it.optString("sub").ifEmpty { "授業" }).apply { setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                            else SpannableString(when (k) { "free" -> "空き"; else -> it.optString("t") + (if (it.optString("sub").isNotEmpty() && k != "extra" && k != "event") "  " + it.optString("sub") else "") })
                row.setTextViewText(R.id.title, title)
                row.setTextViewText(R.id.time, it.optString("s"))
                val past = day === day0 && en <= now
                val isNow = day === day0 && now in st until en
                val col = if (past || k == "free" || k == "cancel") DIM else WHITE
                row.setTextColor(R.id.no, col); row.setTextColor(R.id.title, col); row.setTextColor(R.id.time, col)
                if (isNow) row.setInt(R.id.row, "setBackgroundResource", R.drawable.bg_row_now)
                if (it.optBoolean("c")) row.setViewVisibility(R.id.changed, View.VISIBLE)
                v.addView(R.id.rows, row)
            }
        }
        return v
    }

    fun next(c: Context, s: JSONObject?): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_next)
        v.setOnClickPendingIntent(R.id.root, open(c, "today"))
        applyStatus(v, Sched.status(s), R.id.status, R.id.chrono, R.id.sub)
        return v
    }

    fun week(c: Context, s: JSONObject?, w: Int, h: Int): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_week)
        v.setOnClickPendingIntent(R.id.root, open(c, "timetable"))
        val d = c.resources.displayMetrics.density
        val bmp = Paint2.week(c, s?.optJSONObject("week"), (w - 20 * d).toInt(), (h - 20 * d).toInt())
        if (bmp != null) { v.setImageViewBitmap(R.id.grid, bmp); v.setViewVisibility(R.id.empty, View.GONE) }
        else v.setViewVisibility(R.id.empty, View.VISIBLE)
        return v
    }

    /* ---------- card-style list widgets ---------- */
    private class Row(val left: String, val title: String, val sub: String = "", val right: String = "", val warn: Boolean = false)

    private fun list(c: Context, title: String, rows: List<Row>, empty: String, route: String, add: Boolean = false, max: Int = 6): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_list)
        v.setOnClickPendingIntent(R.id.root, open(c, route))
        v.setTextViewText(R.id.title, title)
        v.setTextViewText(R.id.count, if (rows.isNotEmpty()) rows.size.toString() else "")
        if (add) { v.setViewVisibility(R.id.add, View.VISIBLE); v.setOnClickPendingIntent(R.id.add, open(c, "ask")) } else v.setViewVisibility(R.id.add, View.GONE)
        v.removeAllViews(R.id.rows)
        rows.take(max).forEach { r ->
            val row = RemoteViews(c.packageName, R.layout.widget_list_row)
            row.setTextViewText(R.id.left, r.left); row.setViewVisibility(R.id.left, if (r.left.isEmpty()) View.GONE else View.VISIBLE)
            row.setTextViewText(R.id.title, r.title)
            row.setTextViewText(R.id.sub, r.sub); row.setViewVisibility(R.id.sub, if (r.sub.isEmpty()) View.GONE else View.VISIBLE)
            row.setTextViewText(R.id.right, r.right)
            if (r.warn) row.setTextColor(R.id.right, RED)
            v.addView(R.id.rows, row)
        }
        v.setTextViewText(R.id.empty, empty)
        v.setViewVisibility(R.id.empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
        return v
    }
    private fun arr(s: JSONObject?, k: String) = s?.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    fun tasks(c: Context, s: JSONObject?) = list(c, "タスク",
        arr(s, "tasks").map { Row("", it.optString("title"), right = it.optString("label"), warn = it.optBoolean("warn")) },
        "期限の近いタスクはありません", "tasks", add = true)

    fun prep(c: Context, s: JSONObject?): RemoteViews {
        val p = s?.optJSONObject("prep"); val items = p?.optJSONArray("items")
        val rows = if (items != null) (0 until items.length()).map { Row("□", items.optString(it)) } else emptyList()
        return list(c, "${p?.optString("label") ?: "次"}の授業準備", rows, "準備するものはありません", "prep", max = 7)
    }

    fun events(c: Context, s: JSONObject?) = list(c, "これからの予定",
        arr(s, "events").map { Row(it.optString("label").replace(Regex("\\(.\\)"), ""), it.optString("title"), listOf(it.optString("time"), it.optString("place")).filter { x -> x.isNotEmpty() }.joinToString(" ・ "), it.optString("account")) },
        "2週間以内の予定はありません", "events")

    fun reminders(c: Context, s: JSONObject?): RemoteViews {
        val now = System.currentTimeMillis()
        val rows = arr(s, "reminders").filter { it.optLong("at") > now }.map {
            val cal = Calendar.getInstance().apply { timeInMillis = it.optLong("at") }
            val tomorrow = cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            Row((if (tomorrow) "明日 " else "") + "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)), it.optString("title"), it.optString("body").substringBefore(" ・ "))
        }
        return list(c, "お知らせ予定", rows, "これからのお知らせはありません", "settings")
    }

    fun follow(c: Context, s: JSONObject?) = list(c, "返信待ち",
        arr(s, "follow").map { Row(if (it.optBoolean("me")) "返す" else "待ち", it.optString("subject"), it.optString("who"), "${it.optInt("days")}日", it.optInt("days") >= 3 && it.optBoolean("me")) },
        "返信待ちはありません", "follow")

    fun brief(c: Context, s: JSONObject?): RemoteViews {
        val b = s?.optJSONObject("brief"); val lines = b?.optJSONArray("lines")
        val rows = if (lines != null) (0 until lines.length()).map { Row("・", lines.optString(it)) } else emptyList()
        return list(c, "今朝のまとめ" + (b?.optString("time")?.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: ""), rows, "今朝のまとめはまだありません", "today")
    }
}
