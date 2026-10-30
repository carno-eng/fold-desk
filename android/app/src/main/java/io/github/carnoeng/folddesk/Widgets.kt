package io.github.carnoeng.folddesk

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

open class FdWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.updateAll(c)
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: android.os.Bundle) = Widgets.updateAll(c)
}
class TodayWidget : FdWidget()
class NextWidget : FdWidget()
class TasksWidget : FdWidget()
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
        return try {
            Calendar.getInstance().apply {
                set(d[0].toInt(), d[1].toInt() - 1, d[2].toInt(), p[0].toInt(), p[1].toInt(), 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (_: Exception) { 0 }
    }
    fun name(it: JSONObject): String {
        val n = it.optInt("n"); val t = it.optString("t"); val sub = it.optString("sub")
        return when (it.optString("k")) {
            "cancel" -> "${n}時間目 なし"
            "free" -> "${n}時間目・空き"
            "class", "joho", "meeting" -> "${n}時間目・$t" + (if (sub.isNotEmpty()) " $sub" else "")
            else -> t
        }
    }

    class Status(val line: String, val target: Long, val prefix: String, val next: String, val now: Boolean = false)

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
            val nextLine = nxt?.let { "次は ${name(it)}" } ?: ""
            if (cur != null) return Status("いま ${name(cur)}", at(day.optString("date"), cur.optString("e")), "あと %s", nextLine, true)
            if (nxt != null) return Status("次は ${name(nxt)}", at(day.optString("date"), nxt.optString("s")), "%s 後", "${nxt.optString("s")}〜${nxt.optString("e")}")
            return Status("今日の日程は終わりました", 0, "", "")
        }
        val n = nextDay(s)
        val first = n?.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("k") != "block" && it.optString("k") != "cancel" } }
        return Status("今日は授業がありません", 0, "", if (n != null && first != null) "次は ${n.optString("label")} ${name(first)}" else "")
    }
    fun nextDay(s: JSONObject): JSONObject? {
        val days = s.optJSONArray("days") ?: return null; val t = todayStr()
        for (i in 0 until days.length()) { val d = days.getJSONObject(i)
            if (d.optString("date") > t && (d.optJSONArray("items")?.length() ?: 0) > 0) return d }
        return null
    }
    fun hasItems(d: JSONObject?) = (d?.optJSONArray("items")?.length() ?: 0) > 0
}

object Widgets {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val DIM = 0x99FFFFFF.toInt()
    private const val RED = 0xFFE5483B.toInt()
    private const val BLUE = 0xFF0A62F5.toInt()
    private const val L = 1.32f   // line height used for layout budgeting (CJK fonts run tall)

    private inline fun <reified T : Any> ids(c: Context, m: AppWidgetManager) = m.getAppWidgetIds(ComponentName(c, T::class.java))

    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        val s = Store.snapshot(c)
        fun each(ids: IntArray, f: (Int) -> RemoteViews) = ids.forEach { id -> try { m.updateAppWidget(id, f(id)) } catch (_: Exception) { } }
        each(ids<HeroWidget>(c, m)) { heroViews(c, s, sizes(m, it)) }
        each(ids<TodayWidget>(c, m)) { today(c, s, it, minH(m, it)) }
        each(ids<NextWidget>(c, m)) { next(c, s) }
        each(ids<TasksWidget>(c, m)) { tasks(c, s, minH(m, it)) }
        each(ids<WeekWidget>(c, m)) { val z = sizes(m, it).first(); week(c, s, z.width, z.height) }
        each(ids<PrepWidget>(c, m)) { prep(c, s, minH(m, it)) }
        each(ids<EventsWidget>(c, m)) { events(c, s, minH(m, it)) }
        each(ids<RemindWidget>(c, m)) { reminders(c, s, minH(m, it)) }
        each(ids<FollowWidget>(c, m)) { follow(c, s, minH(m, it)) }
        each(ids<BriefWidget>(c, m)) { brief(c, s, minH(m, it)) }
        each(ids<AddWidget>(c, m)) { RemoteViews(c.packageName, R.layout.widget_add).apply { setOnClickPendingIntent(R.id.root, open(c, "ask")) } }
    }

    fun hasHero(c: Context): Boolean {
        val m = AppWidgetManager.getInstance(c)
        return ids<HeroWidget>(c, m).isNotEmpty() || ids<TodayWidget>(c, m).isNotEmpty() || ids<NextWidget>(c, m).isNotEmpty()
    }

    /** The sizes (dp) the launcher may show this widget at — e.g. portrait and landscape, cover and main screen. */
    @Suppress("DEPRECATION")
    fun sizes(m: AppWidgetManager, id: Int): List<SizeF> {
        val o = m.getAppWidgetOptions(id)
        if (Build.VERSION.SDK_INT >= 31) {
            val l = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!l.isNullOrEmpty()) return l.filter { it.width > 40 && it.height > 40 }.distinct().take(4).ifEmpty { listOf(SizeF(340f, 180f)) }
        }
        val minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0); val maxW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val minH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0); val maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        if (minW <= 0 || maxH <= 0) return listOf(SizeF(340f, 180f))
        return listOf(SizeF(minW.toFloat(), maxH.toFloat()), SizeF(maxW.toFloat(), minH.toFloat())).distinct()
    }
    private fun minH(m: AppWidgetManager, id: Int) = sizes(m, id).minOf { it.height }

    fun open(c: Context, route: String): PendingIntent =
        PendingIntent.getActivity(c, route.hashCode() and 0xffff,
            Intent(c, MainActivity::class.java).putExtra("route", route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun stale(s: JSONObject?): String {
        val at = s?.optLong("at") ?: return ""
        val h = (System.currentTimeMillis() - at) / 3_600_000
        return if (h >= 6) "更新 ${h}時間前" else ""
    }
    private fun hhmm(t: Long): String { val c = Calendar.getInstance().apply { timeInMillis = t }; return "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }
    private fun items(d: JSONObject?): List<JSONObject> = d?.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private fun RemoteViews.size(id: Int, dp: Float) = setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, dp)
    private fun RemoteViews.show(id: Int, on: Boolean) = setViewVisibility(id, if (on) View.VISIBLE else View.GONE)

    /* ===================== the blue "today" sheet ===================== */

    fun heroViews(c: Context, s: JSONObject?, sz: List<SizeF>): RemoteViews =
        if (Build.VERSION.SDK_INT >= 31 && sz.size > 1) RemoteViews(sz.associateWith { hero(c, s, it.width, it.height) })
        else sz.first().let { hero(c, s, it.width, it.height) }

    private class Plan(val seg: Boolean, val bar: Boolean, val labels: Boolean, val sub: Boolean, val sub2: Boolean) { var ds = 0f; var rows = 0 }

    /** Lays the app's hero out for a widget of w × h dp: what fits is shown, sized to the widget. */
    fun hero(c: Context, s: JSONObject?, w: Float, h: Float): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_hero)
        v.setOnClickPendingIntent(R.id.root, open(c, "today"))
        val cal = Calendar.getInstance()
        val heroJ = s?.optJSONObject("hero")
        val dToday = s?.let { Sched.today(it) }
        val todayHas = Sched.hasItems(dToday)
        val shown = if (todayHas) dToday else s?.let { Sched.nextDay(it) }
        val isToday = todayHas
        val segDay = if (dToday != null && dToday.optString("mk").isNotEmpty()) dToday else shown
        val st = Sched.status(s)
        val now = System.currentTimeMillis()

        // text lines
        val holiday = heroJ?.optString("holiday") ?: ""
        val subA: String; val subB: String
        when {
            st.target > 0 && st.now -> { subA = "${hhmm(st.target)}まで" + (if (w >= 430 && st.next.isNotEmpty()) "　" + st.next else ""); subB = if (w < 430) st.next else "" }
            st.target > 0 -> { subA = st.next; subB = "" }
            else -> { subA = st.next; subB = if (holiday.isNotEmpty()) "今日は $holiday" else "" }
        }

        // sizes from the width, then decide what fits in the height
        val stz = (w * 0.05f).coerceIn(15f, 22f); val sbz = stz * 0.84f; val eyz = (stz * 0.8f).coerceIn(12f, 16f)
        val dsMax = (w * 0.12f).coerceIn(28f, 62f)
        fun top(p: Plan) = 29f + eyz * L + 2f + p.ds * 1.18f + 7f + stz * L + (if (p.sub && subA.isNotEmpty()) 4f + sbz * L else 0f) + (if (p.sub2 && subB.isNotEmpty()) 3f + sbz * L else 0f)
        fun need(p: Plan) = top(p) + (if (p.seg) 50f else 0f) + (if (p.bar) 44f else 0f) + (if (p.labels) 19f else 0f) + (if (p.rows > 0) 10f + p.rows * 33f else 0f)
        val canBar = Sched.hasItems(shown)
        val canSeg = segDay != null && segDay.optString("mk").isNotEmpty()
        val plans = listOf(
            Plan(canSeg, canBar, canBar, true, true), Plan(false, canBar, canBar, true, true), Plan(false, canBar, canBar, true, false),
            Plan(false, canBar, false, false, false), Plan(false, false, false, true, false), Plan(false, false, false, false, false))
        var plan = plans.last()
        for (p in plans) { p.ds = dsMax; while (need(p) > h && p.ds > 26f) p.ds -= 1f; if (need(p) <= h) { plan = p; break } }
        if (plan.ds == 0f) plan.ds = 26f
        val rowItems = items(shown).filter { it.optString("k") != "block" }.let { all ->
            if (!isToday) all else all.filter { Sched.at(shown!!.optString("date"), it.optString("e")) > now }.ifEmpty { all } }
        if (plan.seg) { val extra = h - need(plan); if (extra >= 10f + 2 * 33f) plan.rows = minOf(rowItems.size, ((extra - 10f) / 33f).toInt()) }

        // header text
        val modeLbl = segDay?.optString("mode") ?: ""
        val eyebrow = (heroJ?.optString("eyebrow")?.takeIf { it.isNotEmpty() } ?: "${cal.get(Calendar.YEAR)}年") +
            (if (!plan.seg && modeLbl.isNotEmpty() && modeLbl != "通常" && isToday) "  ・  $modeLbl" else "")
        v.setTextViewText(R.id.eyebrow, eyebrow); v.size(R.id.eyebrow, eyz)
        v.setTextViewText(R.id.date, "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"); v.size(R.id.date, plan.ds)
        v.setTextViewText(R.id.status, st.line); v.size(R.id.status, stz)
        v.setTextViewText(R.id.sub, subA); v.size(R.id.sub, sbz); v.show(R.id.sub, plan.sub && subA.isNotEmpty())
        v.setTextViewText(R.id.sub2, subB); v.size(R.id.sub2, sbz); v.show(R.id.sub2, plan.sub2 && subB.isNotEmpty())

        // ring with a live countdown (or the clock when nothing is running)
        val ringDp = minOf(top(plan) - 26f, w * 0.3f).coerceIn(64f, 150f)
        var frac = 0f; var idle = true
        if (st.target > 0 && dToday != null) {
            idle = false
            if (st.now) {
                val cur = findAround(dToday, now)
                frac = if (cur != null) ((now - cur.first).toFloat() / (cur.second - cur.first).coerceAtLeast(1)) else 0f
            } else frac = 1f - ((st.target - now) / 600_000f).coerceIn(0f, 1f)
            v.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() + (st.target - now), "%s", true)
            v.setChronometerCountDown(R.id.chrono, true)
            v.show(R.id.chrono, true); v.show(R.id.clock, false)
            v.setTextViewText(R.id.ringlabel, if (st.now) "のこり" else "まで")
        } else {
            v.show(R.id.chrono, false); v.show(R.id.clock, true)
            v.setTextViewText(R.id.ringlabel, "いま")
        }
        v.size(R.id.chrono, ringDp * 0.22f); v.size(R.id.clock, ringDp * 0.23f); v.size(R.id.ringlabel, (ringDp * 0.115f).coerceAtLeast(10f))
        v.setImageViewBitmap(R.id.ring, Paint2.ring(c, frac, idle, ringDp))

        // 通常 / 短縮 / 朝会 / 休み — tap to switch, like the app
        v.show(R.id.seg, plan.seg)
        if (plan.seg && segDay != null) {
            val modes = s?.optJSONArray("modes") ?: DEFAULT_MODES
            val date = segDay.optString("date"); val mk = segDay.optString("mk")
            val segIds = intArrayOf(R.id.seg0, R.id.seg1, R.id.seg2, R.id.seg3)
            val segSize = (stz * 0.86f).coerceIn(13f, 18f)
            for (k in segIds.indices) {
                val id = segIds[k]
                if (k >= modes.length()) { v.show(id, false); continue }
                val mo = modes.getJSONObject(k); val key = mo.optString("k"); val on = key == mk
                v.show(id, true); v.setTextViewText(id, mo.optString("l")); v.size(id, segSize)
                v.setInt(id, "setBackgroundResource", if (on) R.drawable.bg_seg_on else 0)
                v.setTextColor(id, if (on) BLUE else 0xE6FFFFFF.toInt())
                v.setOnClickPendingIntent(id, ActionReceiver.pi(c, "mode", "$date/$key") { putExtra("date", date); putExtra("mode", key) })
            }
        }

        // the day bar (next school day on holidays and weekends)
        val d = c.resources.displayMetrics.density
        val bar = if (plan.bar) Paint2.dayBar(c, shown, ((w - 36f) * d).toInt(), isToday) else null
        v.show(R.id.bar, bar != null); v.show(R.id.barlabels, bar != null && plan.labels)
        if (bar != null) {
            v.setImageViewBitmap(R.id.bar, bar)
            val first = items(shown).firstOrNull()?.optString("s") ?: ""
            v.setTextViewText(R.id.barstart, if (isToday) first else "${shown!!.optString("label")}  $first")
            val dis = shown!!.optString("dismiss")
            v.setTextViewText(R.id.barend, listOf(if (dis.isNotEmpty()) "下校 $dis" else "", if (segDay?.optBoolean("pending") == true) "切り替え中…" else stale(s)).filter { it.isNotEmpty() }.joinToString("  ・  "))
        }

        // remaining periods when the widget is tall
        v.removeAllViews(R.id.rows)
        v.show(R.id.rows, plan.rows > 0); v.show(R.id.gap, plan.rows == 0)
        if (plan.rows > 0 && shown != null) {
            val date = shown.optString("date")
            rowItems.take(plan.rows).forEach { it ->
                val row = RemoteViews(c.packageName, R.layout.widget_row)
                val k = it.optString("k")
                val a = Sched.at(date, it.optString("s")); val e = Sched.at(date, it.optString("e"))
                row.setTextViewText(R.id.no, if (it.optInt("n") > 0) it.optInt("n").toString() else "・")
                row.setTextViewText(R.id.title, rowTitle(it))
                row.setTextViewText(R.id.time, if (w >= 300) "${it.optString("s")}–${it.optString("e")}" else it.optString("s"))
                val col = if (k == "free" || k == "cancel" || (isToday && e <= now)) DIM else WHITE
                row.setTextColor(R.id.no, col); row.setTextColor(R.id.title, col); row.setTextColor(R.id.time, col)
                if (isToday && now in a until e) row.setInt(R.id.row, "setBackgroundResource", R.drawable.bg_row_now)
                if (it.optBoolean("c") && k != "extra") row.show(R.id.changed, true)
                v.addView(R.id.rows, row)
            }
        }
        return v
    }
    private val DEFAULT_MODES = JSONArray("""[{"k":"normal","l":"通常"},{"k":"short","l":"短縮"},{"k":"assembly","l":"朝会"},{"k":"off","l":"休み"}]""")

    private fun rowTitle(it: JSONObject): CharSequence {
        val k = it.optString("k")
        return if (k == "cancel") SpannableString(it.optString("sub").ifEmpty { "授業" }).apply { setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        else when (k) { "free" -> "空きコマ"; else -> it.optString("t") + (if (it.optString("sub").isNotEmpty() && k != "extra" && k != "event") "  " + it.optString("sub") else "") }
    }

    private fun findAround(day: JSONObject, now: Long): Pair<Long, Long>? {
        var best: Pair<Long, Long>? = null
        for (it in items(day)) { if (it.optString("k") == "cancel") continue
            val a = Sched.at(day.optString("date"), it.optString("s")); val e = Sched.at(day.optString("date"), it.optString("e"))
            if (now in a until e && (best == null || it.optString("k") != "block")) best = Pair(a, e) }
        return best
    }

    /* ===================== 今日の日程 (card with day arrows) ===================== */

    fun today(c: Context, s: JSONObject?, wid: Int, h: Float): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_today)
        v.setOnClickPendingIntent(R.id.root, open(c, "today"))
        v.setOnClickPendingIntent(R.id.add, open(c, "ask"))
        val fg = c.getColor(R.color.card_fg); val muted = c.getColor(R.color.card_muted); val accent = c.getColor(R.color.accent)
        val days = s?.optJSONArray("days")
        val base = days?.let { a -> (0 until a.length()).firstOrNull { a.getJSONObject(it).optString("date") == Sched.todayStr() } } ?: 0
        val maxOff = ((days?.length() ?: 1) - 1 - base).coerceAtLeast(0)
        val off = Store.nav(c, wid).coerceIn(0, maxOff)
        val day = days?.optJSONObject(base + off)
        v.setTextViewText(R.id.date, when (off) { 0 -> "今日の日程"; 1 -> "明日の日程"; else -> "${day?.optString("label") ?: ""}の日程" })
        val mk = day?.optString("mk") ?: ""
        v.setTextViewText(R.id.mode, listOf(
            if (off <= 1) day?.optString("label") ?: "" else "",
            when { mk == "off" -> "休み"; mk.isEmpty() -> "授業なし"; else -> (day?.optString("mode") ?: "") + "時程" },
            day?.optString("dismiss")?.takeIf { it.isNotEmpty() }?.let { "下校 $it" } ?: "").filter { it.isNotEmpty() }.joinToString("  ・  "))
        // arrows
        v.setOnClickPendingIntent(R.id.prev, ActionReceiver.pi(c, "nav", "$wid/prev") { putExtra("wid", wid); putExtra("to", off - 1) })
        v.setOnClickPendingIntent(R.id.next, ActionReceiver.pi(c, "nav", "$wid/next") { putExtra("wid", wid); putExtra("to", off + 1) })
        v.setInt(R.id.prev, "setImageAlpha", if (off > 0) 255 else 70)
        v.setInt(R.id.next, "setImageAlpha", if (off < maxOff) 255 else 70)
        // now / next line (today only)
        val st = Sched.status(s)
        val showNow = off == 0 && Sched.hasItems(day)
        v.show(R.id.nowbox, showNow)
        if (showNow) {
            v.setTextViewText(R.id.status, st.line)
            if (st.target > 0) {
                v.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() + (st.target - System.currentTimeMillis()), st.prefix, true)
                v.setChronometerCountDown(R.id.chrono, true); v.show(R.id.chrono, true)
            } else v.show(R.id.chrono, false)
        }
        v.setTextViewText(R.id.foot, stale(s)); v.show(R.id.foot, stale(s).isNotEmpty())
        // rows
        v.removeAllViews(R.id.rows)
        val list = items(day).filter { it.optString("k") != "block" }
        val cap = ((h - (if (showNow) 108f else 84f)) / 33f).toInt().coerceIn(1, 12)
        val now = System.currentTimeMillis(); val date = day?.optString("date") ?: ""
        if (list.isEmpty()) {
            v.show(R.id.sub, true); v.setTextViewText(R.id.sub, if (mk == "off") "授業はありません" else "この日の日程はありません")
        } else v.show(R.id.sub, false)
        list.take(cap).forEach { it ->
            val row = RemoteViews(c.packageName, R.layout.widget_row)
            val k = it.optString("k")
            val a = Sched.at(date, it.optString("s")); val e = Sched.at(date, it.optString("e"))
            row.setTextViewText(R.id.no, if (it.optInt("n") > 0) it.optInt("n").toString() else "・")
            row.setTextViewText(R.id.title, rowTitle(it))
            row.setTextViewText(R.id.time, "${it.optString("s")}–${it.optString("e")}")
            val past = off == 0 && e <= now
            val col = if (past || k == "free" || k == "cancel") muted else fg
            row.setTextColor(R.id.no, if (k == "joho" && !past) accent else col); row.setTextColor(R.id.title, col); row.setTextColor(R.id.time, muted)
            if (off == 0 && now in a until e) row.setInt(R.id.row, "setBackgroundResource", R.drawable.bg_row_now_card)
            if (it.optBoolean("c") && k != "extra") row.show(R.id.tag, true)
            v.addView(R.id.rows, row)
        }
        return v
    }

    fun next(c: Context, s: JSONObject?): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_next)
        v.setOnClickPendingIntent(R.id.root, open(c, "today"))
        val st = Sched.status(s)
        v.setTextViewText(R.id.status, st.line)
        if (st.target > 0) {
            v.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() + (st.target - System.currentTimeMillis()), st.prefix, true)
            v.setChronometerCountDown(R.id.chrono, true); v.show(R.id.chrono, true)
        } else v.show(R.id.chrono, false)
        val sub = if (st.now) st.next else st.next
        v.setTextViewText(R.id.sub, sub); v.show(R.id.sub, sub.isNotEmpty())
        return v
    }

    fun week(c: Context, s: JSONObject?, w: Float, h: Float): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_week)
        v.setOnClickPendingIntent(R.id.root, open(c, "timetable"))
        val d = c.resources.displayMetrics.density
        val bmp = Paint2.week(c, s?.optJSONObject("week"), ((w - 20) * d).toInt(), ((h - 20) * d).toInt())
        if (bmp != null) { v.setImageViewBitmap(R.id.grid, bmp); v.show(R.id.empty, false) } else v.show(R.id.empty, true)
        return v
    }

    /* ===================== card-style lists (with the same buttons as the app) ===================== */
    private class Row(val left: String, val title: String, val sub: String = "", val right: String = "", val warn: Boolean = false,
                      val check: PendingIntent? = null, val btn: String = "", val btnPi: PendingIntent? = null)

    private fun list(c: Context, h: Float, title: String, count: String, rows: List<Row>, empty: String, route: String, add: Boolean = false): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_list)
        v.setOnClickPendingIntent(R.id.root, open(c, route))
        v.setTextViewText(R.id.title, title)
        v.setTextViewText(R.id.count, count)
        v.show(R.id.add, add); if (add) v.setOnClickPendingIntent(R.id.add, open(c, "ask"))
        v.removeAllViews(R.id.rows)
        val cap = ((h - 62f) / 44f).toInt().coerceIn(1, 10)
        rows.take(cap).forEach { r ->
            val row = RemoteViews(c.packageName, R.layout.widget_list_row)
            row.setTextViewText(R.id.left, r.left); row.show(R.id.left, r.left.isNotEmpty())
            row.setTextViewText(R.id.title, r.title)
            row.setTextViewText(R.id.sub, r.sub); row.show(R.id.sub, r.sub.isNotEmpty())
            row.setTextViewText(R.id.right, r.right); row.show(R.id.right, r.right.isNotEmpty())
            if (r.warn) row.setTextColor(R.id.right, RED)
            if (r.check != null) { row.show(R.id.check, true); row.setOnClickPendingIntent(R.id.check, r.check) }
            if (r.btnPi != null) { row.show(R.id.btn, true); row.setTextViewText(R.id.btn, r.btn); row.setOnClickPendingIntent(R.id.btn, r.btnPi) }
            v.addView(R.id.rows, row)
        }
        v.setTextViewText(R.id.empty, empty)
        v.show(R.id.empty, rows.isEmpty())
        return v
    }
    private fun arr(s: JSONObject?, k: String) = s?.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    fun tasks(c: Context, s: JSONObject?, h: Float): RemoteViews {
        val t = arr(s, "tasks")
        return list(c, h, "タスク", if (t.isNotEmpty()) "${t.size}" else "",
            t.map { val id = it.optString("id")
                Row("", it.optString("title"), right = it.optString("label"), warn = it.optBoolean("warn"),
                    check = if (id.isNotEmpty()) ActionReceiver.pi(c, "task", id) { putExtra("id", id) } else null) },
            "期限の近いタスクはありません", "tasks", add = true)
    }

    fun prep(c: Context, s: JSONObject?, h: Float): RemoteViews {
        val p = s?.optJSONObject("prep"); val items = p?.optJSONArray("items"); val keys = p?.optJSONArray("keys")
        val rows = if (items != null) (0 until items.length()).map { i -> val key = keys?.optString(i) ?: ""
            Row("", items.optString(i), check = if (key.isNotEmpty()) ActionReceiver.pi(c, "prep", key) { putExtra("key", key) } else null) } else emptyList()
        val total = p?.optInt("total") ?: 0
        return list(c, h, "${p?.optString("label")?.ifEmpty { null } ?: "次"}の準備",
            if (rows.isNotEmpty()) "残り ${rows.size}" else "", rows,
            if (total > 0) "準備ばっちりです" else "準備するものはありません", "prep")
    }

    fun events(c: Context, s: JSONObject?, h: Float) = list(c, h, "これからの予定", "",
        arr(s, "events").map { Row(it.optString("label").replace(Regex("\\(.\\)"), ""), it.optString("title"), listOf(it.optString("time"), it.optString("place")).filter { x -> x.isNotEmpty() }.joinToString(" ・ "), it.optString("account")) },
        "2週間以内の予定はありません", "events")

    fun reminders(c: Context, s: JSONObject?, h: Float): RemoteViews {
        val now = System.currentTimeMillis()
        val rows = arr(s, "reminders").filter { it.optLong("at") > now }.map {
            val cal = Calendar.getInstance().apply { timeInMillis = it.optLong("at") }
            val tomorrow = cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            Row((if (tomorrow) "明日 " else "") + "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)), it.optString("title"), it.optString("body").substringBefore(" ・ "))
        }
        return list(c, h, "お知らせ予定", "", rows, "これからのお知らせはありません", "settings")
    }

    fun follow(c: Context, s: JSONObject?, h: Float): RemoteViews {
        val f = arr(s, "follow")
        return list(c, h, "返信待ち", if (f.isNotEmpty()) "${f.size}" else "",
            f.map { val id = it.optString("id")
                Row(if (it.optBoolean("me")) "返す" else "待ち", it.optString("subject"), it.optString("who") + "  ・  ${it.optInt("days")}日",
                    warn = it.optInt("days") >= 3 && it.optBoolean("me"),
                    btn = "済み", btnPi = if (id.isNotEmpty()) ActionReceiver.pi(c, "follow", id) { putExtra("id", id) } else null) },
            "返信待ちはありません", "follow")
    }

    fun brief(c: Context, s: JSONObject?, h: Float): RemoteViews {
        val b = s?.optJSONObject("brief"); val lines = b?.optJSONArray("lines")
        val rows = if (lines != null) (0 until lines.length()).map { Row("・", lines.optString(it)) } else emptyList()
        return list(c, h, "今朝のまとめ", b?.optString("time")?.takeIf { it.isNotEmpty() }?.let { "$it 更新" } ?: "", rows, "今朝のまとめはまだありません", "today")
    }
}
