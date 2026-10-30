package io.github.carnoeng.folddesk

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONObject

/** Bitmaps for widgets: the progress ring, the day bar, and the weekly grid. */
object Paint2 {
    private fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density
    fun dark(c: Context) = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun ring(c: Context, frac: Float, idle: Boolean, sizeDp: Float = 92f): Bitmap {
        val s = dp(c, sizeDp).toInt(); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val sw = dp(c, sizeDp * 0.095f); val r = RectF(sw / 2 + 2, sw / 2 + 2, s - sw / 2 - 2, s - sw / 2 - 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        p.color = 0x33FFFFFF; cv.drawArc(r, 0f, 360f, false, p)
        p.color = if (idle) 0x59FFFFFF else 0xFFFFFFFF.toInt()
        val sweep = if (idle) 360f else 360f * frac.coerceIn(0f, 1f)
        if (sweep > 0.5f) cv.drawArc(r, -90f, sweep, false, p)
        return b
    }

    fun dayBar(c: Context, day: JSONObject?, widthPx: Int, isToday: Boolean = true): Bitmap? {
        val items = day?.optJSONArray("items") ?: return null
        if (items.length() == 0 || widthPx < 50) return null
        val h = dp(c, 34f).toInt(); val b = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val date = day.optString("date")
        var s0 = Long.MAX_VALUE; var s1 = 0L
        for (i in 0 until items.length()) { val it = items.getJSONObject(i); if (it.optString("k") == "cancel") continue
            s0 = minOf(s0, Sched.at(date, it.optString("s"))); s1 = maxOf(s1, Sched.at(date, it.optString("e"))) }
        if (s0 >= s1) return null
        s0 -= 5 * 60000; s1 += 5 * 60000
        val span = (s1 - s0).toFloat(); val pad = dp(c, 3f); val top = dp(c, 4f); val bot = h - dp(c, 4f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
        cv.drawRoundRect(RectF(0f, top - 2, widthPx.toFloat(), bot + 2), dp(c, 11f), dp(c, 11f), bg)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG); val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(c, 1.5f) }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(c, 11.5f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val now = if (isToday) System.currentTimeMillis() else 0L
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i); val k = it.optString("k"); if (k == "cancel") continue
            val a = Sched.at(date, it.optString("s")); val e = Sched.at(date, it.optString("e"))
            val l = (a - s0) / span * widthPx + pad / 2; val r = (e - s0) / span * widthPx - pad / 2
            if (r <= l) continue
            val past = e <= now
            val alpha = if (past) 0.45f else 1f
            fun col(argb: Long) = ((((argb shr 24) and 0xff) * alpha).toInt() shl 24) or (argb and 0xffffff).toInt()
            when (k) {
                "block" -> { fill.color = col(0x47FFFFFFL); cv.drawRoundRect(RectF(l, h / 2f - dp(c, 2.5f), r, h / 2f + dp(c, 2.5f)), 4f, 4f, fill) }
                "free" -> { stroke.color = col(0x59FFFFFFL); cv.drawRoundRect(RectF(l, top + 2, r, bot - 2), dp(c, 7f), dp(c, 7f), stroke) }
                "extra", "event" -> { stroke.color = col(0xFFFFFFFFL); cv.drawRoundRect(RectF(l, top + dp(c, 4f), r, bot - dp(c, 4f)), dp(c, 7f), dp(c, 7f), stroke) }
                else -> {
                    fill.color = col(when (k) { "joho" -> 0xFFFFFFFFL; "meeting" -> 0x52051446L; else -> 0x38FFFFFFL })
                    cv.drawRoundRect(RectF(l, top + 2, r, bot - 2), dp(c, 7f), dp(c, 7f), fill)
                }
            }
            val n = it.optInt("n")
            if (n > 0 && k != "block" && r - l > dp(c, 12f)) {
                txt.color = if (k == "joho") col(0xFF0A62F5L) else col(0xE6FFFFFFL)
                cv.drawText(n.toString(), (l + r) / 2, h / 2f + dp(c, 4f), txt)
            }
        }
        if (now in s0..s1) {
            val x = (now - s0) / span * widthPx
            val y = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD43B.toInt() }
            cv.drawRoundRect(RectF(x - dp(c, 1.5f), 0f, x + dp(c, 1.5f), h.toFloat()), 3f, 3f, y)
            cv.drawCircle(x, dp(c, 3.5f), dp(c, 3.5f), y)
        }
        return b
    }

    fun week(c: Context, week: JSONObject?, wPx: Int, hPx: Int): android.graphics.Bitmap? {
        val days = week?.optJSONArray("days") ?: return null
        val times = week.optJSONArray("times")
        val np = times?.length() ?: 0
        if (np == 0 || wPx < 100 || hPx < 80) return null
        val dk = dark(c)
        val b = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val headH = dp(c, 30f); val colW0 = dp(c, 26f); val gap = dp(c, 3f)
        val cw = (wPx - colW0) / days.length(); val ch = (hPx - headH) / np
        val fg = if (dk) 0xFFF5F5F7.toInt() else 0xFF0B0B0F.toInt(); val muted = if (dk) 0xFF9A9AA2.toInt() else 0xFF6C6C74.toInt()
        val accent = if (dk) 0xFF3D8BFF.toInt() else 0xFF0A62F5.toInt()
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val f = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until np) {
            t.color = muted; t.textSize = dp(c, 13f); t.typeface = Typeface.DEFAULT_BOLD
            cv.drawText("${i + 1}", colW0 / 2, headH + ch * i + ch / 2 + dp(c, 4f), t)
        }
        for (di in 0 until days.length()) {
            val d = days.getJSONObject(di); val x0 = colW0 + cw * di
            val today = d.optBoolean("today")
            t.color = if (today) accent else muted; t.textSize = dp(c, 12f); t.typeface = Typeface.DEFAULT_BOLD
            cv.drawText(d.optString("label") + " " + d.optString("md"), x0 + cw / 2, dp(c, 13f), t)
            if (d.optString("mode").isNotEmpty()) { t.textSize = dp(c, 9.5f); t.color = 0xFFF2382B.toInt(); cv.drawText(d.optString("mode"), x0 + cw / 2, dp(c, 25f), t) }
            val cells = d.optJSONArray("cells") ?: continue
            for (i in 0 until minOf(np, cells.length())) {
                val cell = cells.getJSONObject(i); val k = cell.optString("k")
                val r = RectF(x0 + gap, headH + ch * i + gap, x0 + cw - gap, headH + ch * (i + 1) - gap)
                val rad = dp(c, 9f)
                var tc = fg
                when (k) {
                    "joho" -> { f.color = accent; f.style = Paint.Style.FILL; cv.drawRoundRect(r, rad, rad, f); tc = 0xFFFFFFFF.toInt() }
                    "meeting" -> { f.color = if (dk) 0xFF26272B.toInt() else 0xFFECEDF1.toInt(); f.style = Paint.Style.FILL; cv.drawRoundRect(r, rad, rad, f); tc = muted }
                    "class" -> { f.color = if (today) (if (dk) 0xFF11264A.toInt() else 0xFFE5EDFF.toInt()) else (if (dk) 0xFF2A2A2E.toInt() else 0xFFEFEFF4.toInt()); f.style = Paint.Style.FILL; cv.drawRoundRect(r, rad, rad, f) }
                    "none" -> { continue }
                    else -> { f.color = if (dk) 0x33FFFFFF else 0x22000000; f.style = Paint.Style.STROKE; f.strokeWidth = dp(c, 1.2f); cv.drawRoundRect(r, rad, rad, f); tc = muted }
                }
                f.style = Paint.Style.FILL
                val title = cell.optString("t"); val sub = cell.optString("sub")
                t.color = tc; t.typeface = Typeface.DEFAULT_BOLD
                t.textSize = dp(c, if (r.width() < dp(c, 54f)) 10.5f else 12f)
                val tt = if (t.measureText(title) > r.width() - dp(c, 4f)) title.take(maxOf(2, (title.length * (r.width() - dp(c, 6f)) / t.measureText(title)).toInt())) else title
                cv.drawText(tt, r.centerX(), r.centerY() + (if (sub.isNotEmpty()) -dp(c, 1f) else dp(c, 4f)), t)
                if (sub.isNotEmpty()) { t.textSize = dp(c, 9.5f); t.typeface = Typeface.DEFAULT; cv.drawText(sub, r.centerX(), r.centerY() + dp(c, 11f), t) }
                if (cell.optBoolean("c")) { f.color = 0xFFFF9500.toInt(); cv.drawCircle(r.right - dp(c, 6f), r.top + dp(c, 6f), dp(c, 3.5f), f) }
            }
        }
        return b
    }
}
