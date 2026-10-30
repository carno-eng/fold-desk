package io.github.carnoeng.folddesk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Renders the widgets at real launcher sizes into PNGs (build/shots) so their layout can be checked without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi")
class WidgetShots {
    private val c: Context = ApplicationProvider.getApplicationContext()
    private val out = File("build/shots").apply { mkdirs() }

    private fun hm(cal: Calendar) = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    private fun day(offset: Int) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().apply { add(Calendar.DATE, offset) }.time)

    /** a school day whose 3rd period is running now */
    private fun items(now: Boolean): JSONArray {
        val base = Calendar.getInstance().apply { if (now) add(Calendar.MINUTE, -125) else { set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 35) } }
        val a = JSONArray()
        fun add(n: Int, t: String, sub: String, k: String, len: Int, gap: Int, ch: Boolean = false) {
            val s = base.clone() as Calendar; base.add(Calendar.MINUTE, len); val e = base.clone() as Calendar; base.add(Calendar.MINUTE, gap)
            a.put(JSONObject().put("s", hm(s)).put("e", hm(e)).put("n", n).put("t", t).put("sub", sub).put("k", k).put("c", ch))
        }
        add(0, "朝のHR", "", "block", 5, 5); add(1, "空き", "", "free", 45, 10); add(2, "補教", "2-1", "class", 45, 20, true)
        add(3, "教科主任会", "", "meeting", 45, 10); add(4, "国語", "", "class", 45, 60); add(5, "情報探究", "4-1", "joho", 45, 10)
        add(0, "研修会", "放課後", "extra", 50, 0)
        return a
    }
    private fun snap(holiday: Boolean): JSONObject {
        val days = JSONArray()
        for (i in 0 until 8) {
            val its = if (i == 0 && holiday) JSONArray() else if (i == 0) items(true) else items(false)
            days.put(JSONObject().put("date", day(i)).put("label", if (i == 0) "今日" else "11/${i + 1}(月)").put("mode", if (i == 0 && holiday) "休み" else "通常")
                .put("mk", if (i == 0 && holiday) "off" else "normal").put("dismiss", "14:50").put("items", its))
        }
        return JSONObject().put("v", 3).put("at", System.currentTimeMillis()).put("days", days)
            .put("hero", JSONObject().put("eyebrow", "2026年 金曜日").put("modeLabel", "通常").put("holiday", if (holiday) "開校記念日" else ""))
            .put("modes", JSONArray("""[{"k":"normal","l":"通常"},{"k":"short","l":"短縮"},{"k":"assembly","l":"朝会"},{"k":"off","l":"休み"}]"""))
            .put("tasks", JSONArray("""[{"id":"t1","title":"情報探究 4年 単元計画","label":"今日まで","warn":true},{"id":"t2","title":"ICT部会 資料","label":"あと5日","warn":false},{"id":"t3","title":"研修の振り返りを書く","label":"","warn":false}]"""))
            .put("prep", JSONObject("""{"label":"今日","items":["国語 拡大コピー","国語 音読カード"],"keys":["g3:0","g3:1"],"total":2}"""))
            .put("follow", JSONArray("""[{"id":"f1","who":"○○さん","subject":"研修会の講師依頼について","days":6,"me":true},{"id":"f2","who":"△△社","subject":"見積もりのご確認","days":2,"me":false}]"""))
    }

    private fun shot(name: String, rv: RemoteViews, wDp: Int, hDp: Int) {
        try { shot0(name, rv, wDp, hDp) } catch (e: Throwable) {
            var t: Throwable? = e; while (t != null) { println("SHOT $name: ${t.javaClass.name}: ${t.message}"); t.stackTrace.take(6).forEach { println("   at $it") }; t = t.cause }
        }
    }
    private fun shot0(name: String, rv: RemoteViews, wDp: Int, hDp: Int) {
        val d = c.resources.displayMetrics.density
        val host = FrameLayout(c)
        val v = rv.apply(c, host)
        host.addView(v)
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w + 40, h + 40, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.drawColor(0xFF6F8FB0.toInt()); cv.translate(20f, 20f); host.draw(cv)
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun render() {
        val s = snap(false); val hol = snap(true)
        for ((w, h) in listOf(340 to 170, 340 to 210, 340 to 280, 340 to 420, 560 to 250, 600 to 400)) {
            shot("hero-${w}x$h", Widgets.hero(c, s, w.toFloat(), h.toFloat()), w, h)
        }
        shot("hero-holiday-340x210", Widgets.hero(c, hol, 340f, 210f), 340, 210)
        shot("hero-holiday-340x300", Widgets.hero(c, hol, 340f, 300f), 340, 300)
        shot("today-340x380", Widgets.today(c, s, 1, 380f), 340, 380)
        shot("tasks-340x220", Widgets.tasks(c, s, 220f), 340, 220)
        shot("prep-340x180", Widgets.prep(c, s, 180f), 340, 180)
        shot("follow-340x200", Widgets.follow(c, s, 200f), 340, 200)
        shot("next-250x110", Widgets.next(c, s), 250, 110)
    }
}
