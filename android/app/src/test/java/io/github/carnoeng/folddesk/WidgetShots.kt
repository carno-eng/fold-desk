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

    /** Picker previews: the real widgets with sample content, on a transparent background (copied into res/drawable-nodpi). */
    private fun preview(name: String, rv: RemoteViews, wDp: Int, hDp: Int) {
        try {
            val d = c.resources.displayMetrics.density; val host = FrameLayout(c); host.addView(rv.apply(c, host))
            val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
            host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); host.layout(0, 0, w, h)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); host.draw(Canvas(b))
            File(out, "preview_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Throwable) { println("PREVIEW $name: $e") }
    }

    @Test fun previews() {
        val s = snap(false)
        val wx = JSONObject(s.toString()).put("weather", JSONObject("""{"t":18,"feels":17,"hum":62,"uv":6,"uvLabel":"強い","label":"晴れ時々くもり","kind":"part","day":true,"hi":21,"lo":11,"pop":60,"place":"横浜","code":2,"precip":0,"wind":8,"sr":370,"ss":1005,"tip":"","hours":[{"h":"いま","t":18,"kind":"part","pop":10},{"h":"10時","t":19,"kind":"sun","pop":0},{"h":"11時","t":20,"kind":"sun","pop":0},{"h":"12時","t":21,"kind":"cloud","pop":30},{"h":"13時","t":20,"kind":"rain","pop":50}]}"""))
        val now = System.currentTimeMillis()
        val full = JSONObject(wx.toString())
            .put("events", JSONArray("""[{"label":"今日","title":"校内研修","time":"15:30","place":"多目的室","account":""},{"label":"11/6(金)","title":"漢字検定","time":"15:35","place":"","account":"学校"},{"label":"11/10(火)","title":"保護者会","time":"14:00","place":"教室","account":""}]"""))
            .put("reminders", JSONArray().put(JSONObject().put("at", now + 25 * 60_000).put("title", "3時間目 教科主任会").put("body", "会議室"))
                .put(JSONObject().put("at", now + 95 * 60_000).put("title", "校内研修").put("body", "多目的室")))
            .put("brief", JSONObject("""{"time":"7:30","lines":["通常時程・授業4コマ","今日までのタスク 1件","15:30 校内研修","午後から雨。傘をわすれずに"]}"""))
            .put("docs", JSONArray("""[{"id":"d1","title":"漢検 実施計画","label":"今日","ev":"15:35 漢字検定","cat":"漢検","pages":3},{"id":"d2","title":"保護者会 しおり","label":"明日","ev":"14:00 保護者会","cat":"","pages":4},{"id":"d3","title":"研修資料 ICT活用","label":"11/10","ev":"","cat":"研修","pages":12}]"""))
            .put("week", JSONObject().put("times", JSONArray("[1,2,3,4,5]")).put("days", JSONArray().apply {
                val subj = listOf(listOf("国語","算数","生活","体育","音楽"), listOf("算数","国語","図工","図工","学活"), listOf("国語","体育","算数","生活",""), listOf("算数","国語","音楽","道徳","情報"), listOf("国語","算数","体育","生活",""))
                listOf("月","火","水","木","金").forEachIndexed { i, l -> put(JSONObject().put("label", l).put("md", "${2 + i}").put("today", i == 1).put("cells", JSONArray().apply {
                    subj[i].forEach { t -> put(JSONObject().put("k", if (t.isEmpty()) "free" else if (t == "情報") "joho" else "class").put("t", t)) } })) } }))
        preview("hero", Widgets.hero(c, wx, 300f, 190f), 300, 190)
        preview("today", Widgets.today(c, s, 1, 300f), 260, 300)
        preview("next", Widgets.next(c, s), 250, 110)
        preview("tasks", Widgets.tasks(c, full, 180f), 260, 180)
        preview("prep", Widgets.prep(c, full, 150f), 260, 150)
        preview("events", Widgets.events(c, full, 180f), 260, 180)
        preview("remind", Widgets.reminders(c, full, 150f), 260, 150)
        preview("follow", Widgets.follow(c, full, 160f), 260, 160)
        preview("brief", Widgets.brief(c, full, 200f), 260, 200)
        preview("docs", Widgets.docs(c, full, 200f), 260, 200)
        preview("week", Widgets.week(c, full, 300f, 220f), 300, 220)
        preview("weather", Widgets.weather(c, full, 260f, 160f), 260, 160)
        preview("window", Widgets.window(c, full, 220f, 150f), 220, 150)
        preview("add", RemoteViews(c.packageName, R.layout.widget_add), 160, 70)
    }

    @Test fun render() {
        val s = snap(false); val hol = snap(true)
        for ((w, h) in listOf(340 to 170, 340 to 190, 340 to 240, 340 to 300, 340 to 440, 560 to 250, 600 to 420)) {
            shot("hero-${w}x$h", Widgets.hero(c, s, w.toFloat(), h.toFloat()), w, h)
        }
        shot("hero-holiday-340x210", Widgets.hero(c, hol, 340f, 210f), 340, 210)
        shot("hero-holiday-340x300", Widgets.hero(c, hol, 340f, 300f), 340, 300)
        shot("today-340x380", Widgets.today(c, s, 1, 380f), 340, 380)
        shot("tasks-340x220", Widgets.tasks(c, s, 220f), 340, 220)
        shot("prep-340x180", Widgets.prep(c, s, 180f), 340, 180)
        shot("follow-340x200", Widgets.follow(c, s, 200f), 340, 200)
        shot("next-250x110", Widgets.next(c, s), 250, 110)

        // moods: a class ending in 3 minutes, and a break between two classes
        fun one(a: Int, b: Int, a2: Int, b2: Int): JSONObject {
            val t = Calendar.getInstance()
            fun at(m: Int) = (t.clone() as Calendar).apply { add(Calendar.MINUTE, m) }
            val its = JSONArray().put(JSONObject().put("s", hm(at(a))).put("e", hm(at(b))).put("n", 2).put("t", "国語").put("sub", "").put("k", "class"))
                .put(JSONObject().put("s", hm(at(a2))).put("e", hm(at(b2))).put("n", 3).put("t", "情報探究").put("sub", "4-1").put("k", "joho"))
            val x = JSONObject(s.toString()); x.getJSONArray("days").getJSONObject(0).put("items", its); return x
        }
        shot("hero-mood-soon", Widgets.hero(c, one(-40, 3, 13, 58), 340f, 210f), 340, 210)
        shot("hero-mood-break", Widgets.hero(c, one(-50, -3, 7, 52), 340f, 210f), 340, 210)
        shot("next-mood-soon", Widgets.next(c, one(-40, 3, 13, 58)), 250, 110)

        val wx = JSONObject(s.toString()).put("weather", JSONObject("""{"t":18,"feels":17,"hum":62,"uv":6,"uvLabel":"強い","label":"晴れ時々くもり","kind":"part","day":true,"hi":21,"lo":11,"pop":60,"place":"横浜","tip":"16時ごろ雨・傘を","hours":[{"h":9,"t":18,"p":0,"kind":"part","day":true},{"h":10,"t":19,"p":10,"kind":"sun","day":true},{"h":11,"t":20,"p":10,"kind":"sun","day":true},{"h":12,"t":21,"p":30,"kind":"cloud","day":true},{"h":13,"t":20,"p":50,"kind":"rain","day":true},{"h":14,"t":19,"p":60,"kind":"rain","day":true}]}"""))
        shot("weather-250x120", Widgets.weather(c, wx, 250f, 120f), 250, 120)
        shot("weather-340x200", Widgets.weather(c, wx, 340f, 200f), 340, 200)
        shot("hero-weather-340x210", Widgets.hero(c, wx, 340f, 210f), 340, 210)
        // window scenes: a few frames for several kinds of weather
        for ((nm, code) in listOf("rain" to 63, "heavy" to 65, "clear" to 0, "snow" to 73, "fog" to 45, "thunder" to 95)) {
            val ww = JSONObject(wx.getJSONObject("weather").toString()).put("code", code)
            val st = WxScene.state(ww); val n = WxScene.frameCount(st)
            for (f in listOf(0, n / 2)) {
                val b = WxScene.frame(st, 510, 320, f, n); val g = WxScene.glass(510, 320, 1.6f)
                Canvas(b).drawBitmap(g, 0f, 0f, null)
                File(out, "window-$nm-$f.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        for ((nm, code) in listOf("birds" to 0, "plane" to 2, "nightplane" to 0, "ufo" to 0, "balloon" to 0, "paper" to 1, "leaves" to 0, "petals" to 2, "umbrella" to 65, "rainbow" to 61, "fireflies" to 0)) {
            val ww = JSONObject(wx.getJSONObject("weather").toString()).put("code", code)
            if (nm in listOf("nightplane", "ufo", "fireflies")) ww.put("sr", 23 * 60 + 50).put("ss", 23 * 60 + 55)
            val st = WxScene.state(ww); st.visitor = nm; val n = WxScene.frameCount(st)
            val b = WxScene.frame(st, 400, 250, 1, n); Canvas(b).drawBitmap(WxScene.glass(400, 250, 1.25f), 0f, 0f, null)
            File(out, "visitor-$nm.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot("window-widget-340x200", Widgets.window(c, wx, 340f, 200f), 340, 200)
        val dsnap = JSONObject(wx.toString()).put("docs", JSONArray()
            .put(JSONObject("""{"id":"d1","title":"漢検 実施計画","date":"x","label":"今日","ev":"15:35 漢字検定","cat":"漢検","pages":3,"inked":true}"""))
            .put(JSONObject("""{"id":"d2","title":"保護者会 しおり（配布用）","date":"x","label":"明日","ev":"14:00 保護者会","cat":"","pages":4}"""))
            .put(JSONObject("""{"id":"d3","title":"研修資料 ICT活用","date":"x","label":"11/10","ev":"","cat":"研修","pages":12}""")))
        shot("docs-340x220", Widgets.docs(c, dsnap, 220f), 340, 220)
        shot("docs-empty-340x160", Widgets.docs(c, JSONObject(wx.toString()).put("docs", JSONArray()), 160f), 340, 160)
        // visitor sprites, all kinds, day and night
        run {
            val kinds = listOf("birds", "plane", "balloon", "dragonfly", "paper", "umbrella", "ufo", "sleigh")
            val sheet = Bitmap.createBitmap(8 * 160, 2 * 80, Bitmap.Config.ARGB_8888); val cv = Canvas(sheet)
            for ((row, m) in listOf(720, 1290).withIndex()) {
                val ww = JSONObject(wx.getJSONObject("weather").toString()).put("code", 1)
                val st = WxScene.state(ww).let { WxScene.State(WxScene.phase(m, it.sr, it.ss), it.kind, it.lvl, it.cover, it.wind, it.cols, m >= 1100, m, it.sr, it.ss) }
                cv.drawRect(0f, row * 80f, 8 * 160f, row * 80f + 80f, android.graphics.Paint().apply { color = if (row == 0) 0xFF6EB3F2.toInt() else 0xFF0E1638.toInt() })
                kinds.forEachIndexed { i, k -> val v = WxScene.visitor(st, k, 1.5f, kotlin.random.Random(3)); val b = v.frames[0]
                    cv.save(); cv.clipRect(i * 160f, row * 80f, i * 160f + 158f, row * 80f + 80f); cv.drawBitmap(b, i * 160f + 4, row * 80f + 8, null); cv.restore() }
            }
            File(out, "window-visitors.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // always-on desk view
        Store.saveSnapshot(c, one(-40, 3, 13, 58).toString())
        renderView("aod-portrait", AodView(c), 400, 880)
        renderView("aod-landscape", AodView(c), 880, 400)

        // larger system font
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        shot("big-today-340x380", Widgets.today(c, s, 1, 380f), 340, 380)
        shot("big-tasks-340x220", Widgets.tasks(c, s, 220f), 340, 220)
        shot("big-follow-340x200", Widgets.follow(c, s, 200f), 340, 200)
        shot("big-next-250x110", Widgets.next(c, s), 250, 110)
        shot("big-hero-340x210", Widgets.hero(c, s, 340f, 210f), 340, 210)
        org.robolectric.RuntimeEnvironment.setFontScale(1f)
    }

    private fun renderView(name: String, v: android.view.View, wDp: Int, hDp: Int) {
        try {
            val d = c.resources.displayMetrics.density; val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Throwable) { println("SHOT $name: $e"); e.stackTrace.take(6).forEach { println("   at $it") } }
    }
}
