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

    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        val s = Store.snapshot(c)
        m.getAppWidgetIds(ComponentName(c, TodayWidget::class.java)).forEach { m.updateAppWidget(it, today(c, s)) }
        m.getAppWidgetIds(ComponentName(c, NextWidget::class.java)).forEach { m.updateAppWidget(it, next(c, s)) }
        m.getAppWidgetIds(ComponentName(c, TasksWidget::class.java)).forEach { m.updateAppWidget(it, tasks(c, s)) }
    }

    private fun open(c: Context, route: String): PendingIntent =
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

    fun tasks(c: Context, s: JSONObject?): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_tasks)
        v.setOnClickPendingIntent(R.id.root, open(c, "tasks"))
        v.setOnClickPendingIntent(R.id.add, open(c, "ask"))
        v.removeAllViews(R.id.rows)
        val arr = s?.optJSONArray("tasks")
        val n = arr?.length() ?: 0
        v.setTextViewText(R.id.count, if (n > 0) "$n" else "")
        if (arr != null) for (i in 0 until minOf(n, 5)) {
            val t = arr.getJSONObject(i)
            val row = RemoteViews(c.packageName, R.layout.widget_task_row)
            row.setTextViewText(R.id.title, t.optString("title"))
            row.setTextViewText(R.id.label, t.optString("label"))
            row.setTextColor(R.id.label, if (t.optBoolean("warn")) WARN else DIM)
            v.addView(R.id.rows, row)
        }
        v.setViewVisibility(R.id.empty, if (n == 0) View.VISIBLE else View.GONE)
        val prep = s?.optJSONObject("prep")
        val pItems = prep?.optJSONArray("items")
        if (pItems != null && pItems.length() > 0) {
            v.setTextViewText(R.id.prep, "${prep.optString("label")}の準備：" + (0 until minOf(pItems.length(), 3)).joinToString("、") { pItems.optString(it) } + (if (pItems.length() > 3) " ほか" else ""))
            v.setViewVisibility(R.id.prep, View.VISIBLE)
        } else v.setViewVisibility(R.id.prep, View.GONE)
        return v
    }
}
