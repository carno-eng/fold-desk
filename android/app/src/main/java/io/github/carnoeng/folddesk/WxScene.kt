package io.github.carnoeng.folddesk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Window" weather scene, same palette and rules as the app's 今日 background:
 * time of day (深夜/夜明け/朝/昼/午後/夕焼け/薄暮/夜) × weather (clear … heavy rain, snow, fog, thunder),
 * rendered as a short seamless loop of frames for a ViewFlipper.
 */
object WxScene {
    private val SKY = mapOf(
        "late" to intArrayOf(0xFF04060F.toInt(), 0xFF0A0F26.toInt(), 0xFF121A3A.toInt()), "dawn" to intArrayOf(0xFF2B2F5E.toInt(), 0xFFB86A8C.toInt(), 0xFFF4B387.toInt()),
        "morning" to intArrayOf(0xFF5DA9F0.toInt(), 0xFF9CCBF4.toInt(), 0xFFEAF4FC.toInt()), "day" to intArrayOf(0xFF2F7FE6.toInt(), 0xFF6EB3F2.toInt(), 0xFFD2E9FB.toInt()),
        "afternoon" to intArrayOf(0xFF3B7DD8.toInt(), 0xFF8FBDE8.toInt(), 0xFFF3D9A8.toInt()), "sunset" to intArrayOf(0xFF3A2E6E.toInt(), 0xFFC2577A.toInt(), 0xFFF7A65A.toInt()),
        "dusk" to intArrayOf(0xFF141B47.toInt(), 0xFF3C3A7A.toInt(), 0xFF8A5A8E.toInt()), "night" to intArrayOf(0xFF070B1E.toInt(), 0xFF0E1638.toInt(), 0xFF1E2855.toInt()))
    val PHASE_JA = mapOf("late" to "深夜", "dawn" to "夜明け", "morning" to "朝", "day" to "昼", "afternoon" to "午後", "sunset" to "夕焼け", "dusk" to "薄暮", "night" to "夜")

    class State(val phase: String, val kind: String, val lvl: Int, val cover: Float, val wind: Float, val cols: IntArray, val night: Boolean, val m: Int, val sr: Int, val ss: Int) {
        /** A little visitor in the sky (birds, plane, balloon …), picked per quarter hour so it changes through the day. */
        var visitor: String? = null
    }

    fun pickVisitor(st: State, month: Int, salt: Long): String? {
        val wet = st.kind == "rain" || st.kind == "thunder"; val day = !st.night
        val calm = !wet && st.kind != "snow" && st.kind != "fog"; val open = st.cover < .6f
        val l = mutableListOf<Pair<String, Float>>("" to 2.5f)
        if (day && calm) l += listOf("birds" to 4f, "paper" to .8f)
        if (day && !wet && st.kind != "fog") l += "plane" to 2.5f
        if (st.night && st.kind != "fog" && !(wet && st.lvl >= 3)) l += "nightplane" to 2.5f
        if (st.night && open && !wet) l += "ufo" to .4f
        if (day && open && calm) l += "balloon" to 1.5f
        if (!wet && st.kind != "snow" && month in 9..11) l += "leaves" to 2.5f
        if (!wet && st.kind != "snow" && month in 3..4) l += "petals" to 2.5f
        if (wet && st.lvl >= 3) l += "umbrella" to 2.5f
        if (day && ((st.kind == "rain" && st.lvl <= 2) || st.kind == "few" || st.kind == "partly")) l += "rainbow" to (if (st.kind == "rain") 3f else .6f)
        if (st.night && !wet && month in 6..7) l += "fireflies" to 3f
        var r = Random(salt).nextFloat() * l.sumOf { it.second.toDouble() }.toFloat()
        for ((k, w) in l) { r -= w; if (r <= 0) return k.ifEmpty { null } }
        return null
    }

    fun phase(m: Int, sr: Int, ss: Int) = when {
        m >= 23 * 60 || m < 4 * 60 -> "late"; m < sr - 60 -> "night"; m < sr + 25 -> "dawn"; m < 10 * 60 -> "morning"
        m < ss - 120 -> "day"; m < ss - 25 -> "afternoon"; m < ss + 20 -> "sunset"; m < ss + 75 -> "dusk"; else -> "night" }

    fun kindLevel(code: Int, precip: Double): Pair<String, Int> {
        var k = "clear"; var l = 0
        when {
            code == 1 -> k = "few"; code == 2 -> k = "partly"; code == 3 -> k = "overcast"; code == 45 || code == 48 -> k = "fog"
            code in 51..57 -> { k = "rain"; l = if (code >= 55) 2 else 1 }
            code == 61 || code == 80 -> { k = "rain"; l = 2 }; code == 63 || code == 81 || code == 66 -> { k = "rain"; l = 3 }
            code == 65 || code == 82 || code == 67 -> { k = "rain"; l = 4 }
            code in 71..77 || code == 85 || code == 86 -> { k = "snow"; l = if (code == 75 || code == 86) 3 else if (code == 73 || code == 85) 2 else 1 }
            code >= 95 -> { k = "thunder"; l = 4 }
        }
        if (k == "rain" && precip >= 4) l = max(l, 4) else if (k == "rain" && precip >= 1.5) l = max(l, 3)
        return Pair(k, l)
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    fun lum(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255

    fun state(w: JSONObject?): State {
        val cal = Calendar.getInstance(); val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val sr = w?.optInt("sr", -1)?.takeIf { it > 0 } ?: (6 * 60 + 5); val ss = w?.optInt("ss", -1)?.takeIf { it > 0 } ?: (16 * 60 + 45)
        val ph = phase(m, sr, ss)
        val (kind, lvl) = kindLevel(w?.optInt("code", 1) ?: 1, w?.optDouble("precip", 0.0) ?: 0.0)
        val cover = when (kind) { "clear" -> 0f; "few" -> .25f; "partly" -> .5f; "fog" -> .7f; "overcast" -> .9f; else -> .85f }
        val night = ph == "night" || ph == "late" || ph == "dusk"
        val tgt = when (kind) {
            "rain", "thunder" -> if (night) 0xFF151A26.toInt() else 0xFF4A5468.toInt(); "snow" -> if (night) 0xFF2C3448.toInt() else 0xFFB9C3D3.toInt()
            "fog" -> if (night) 0xFF3A3F4C.toInt() else 0xFFC8CED8.toInt(); else -> if (night) 0xFF262B3A.toInt() else 0xFF8792A4.toInt() }
        val k = when (kind) { "rain", "thunder" -> .55f + lvl * .08f; "snow" -> .55f; "fog" -> .6f; else -> cover * .62f }
        val cols = SKY[ph]!!.map { mix(it, tgt, min(.9f, k)) }.toIntArray()
        return State(ph, kind, lvl, cover, (w?.optDouble("wind", 0.0) ?: 0.0).toFloat(), cols, night, m, sr, ss).also {
            it.visitor = pickVisitor(it, cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_YEAR) * 100L + m / 15) }
    }

    fun frameCount(st: State) = when (st.kind) { "rain", "thunder" -> 10; "snow" -> 12; "fog" -> 8; else -> if (st.visitor != null) 8 else if (st.night) 8 else 6 }
    fun interval(st: State) = when (st.kind) { "rain", "thunder" -> 90; "snow" -> 180; else -> if (st.visitor != null) 170 else 260 }

    /** One frame (index f of n) of a w×h scene. */
    fun frame(st: State, w: Int, h: Int, f: Int, n: Int, seed: Int = 7): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val t = f.toFloat() / n
        val s = min(w, h) / 200f
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), st.cols, floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        // sun / moon
        val frac = ((st.m - st.sr).toFloat() / max(1, st.ss - st.sr)).coerceIn(0f, 1f)
        if (!st.night && st.kind != "rain" && st.kind != "thunder") {
            val x = w * (.15f + .7f * frac); val y = h * (.6f - .5f * sin(PI * frac).toFloat())
            val warm = st.phase == "sunset" || st.phase == "dawn" || st.phase == "afternoon"
            p.shader = RadialGradient(x, y, w * .7f, intArrayOf(if (warm) 0xBFFFC88C.toInt() else 0xA6FFFAE1.toInt(), if (warm) 0x59FFAA6E else 0x47FFF5D2, 0x00FFFFFF), floatArrayOf(0f, .14f, 1f), Shader.TileMode.CLAMP)
            p.alpha = ((1 - st.cover * .75f) * 255).toInt(); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null; p.alpha = 255
            if (st.cover < .6f) { p.color = if (warm) 0xFFFFD9A0.toInt() else 0xFFFFF6D8.toInt(); c.drawCircle(x, y, 13 * s, p) }
        } else if (st.night && st.cover < .6f) {
            val x = w * .78f; val y = h * .2f; val r = 11 * s
            p.shader = RadialGradient(x, y, r * 6, intArrayOf(0x59DCE1FF, 0x00DCE1FF), null, Shader.TileMode.CLAMP); c.drawCircle(x, y, r * 6, p); p.shader = null
            p.color = 0xFFE9ECFF.toInt(); c.drawCircle(x, y, r, p)
            p.color = st.cols[0]; c.drawCircle(x + r * .45f, y - r * .25f, r * .9f, p)
        }
        if (st.kind == "rain" && st.lvl <= 1 && !st.night && st.phase != "late") {
            p.style = Paint.Style.STROKE; p.strokeWidth = 5 * s; val r0 = maxOf(w, h) * .8f
            intArrayOf(0x52FF5A5A, 0x52FFA24C, 0x52FFE35A, 0x526BE38A, 0x525AB8FF, 0x528A6BFF).forEachIndexed { i, col ->
                p.color = col; val r = r0 - i * 5 * s; c.drawArc(RectF(w / 2f - r, h * 1.05f - r, w / 2f + r, h * 1.05f + r), 180f, 180f, false, p) }
            p.style = Paint.Style.FILL
        }
        val rnd = Random(seed)
        // stars twinkle
        if (st.night && st.cover < .7f) repeat((70 * w * h / (400f * 300f)).toInt().coerceIn(20, 140)) {
            val sx = rnd.nextFloat() * w; val sy = rnd.nextFloat() * h * .65f; val ph = rnd.nextFloat() * 6.28f
            p.color = Color.argb((190 * (.55 + .45 * sin(2 * PI * t + ph))).toInt(), 255, 255, 255); c.drawCircle(sx, sy, (rnd.nextFloat() * .9f + .4f) * s, p) }
        // clouds (gentle bob)
        val nc = (st.cover * 6).toInt() + if (st.kind == "rain" || st.kind == "snow") 2 else 0
        repeat(nc) { i ->
            val cx = rnd.nextFloat() * w * 1.2f - w * .1f; val cy = h * (.05f + rnd.nextFloat() * .4f) + sin(2 * PI * (t + i * .13)).toFloat() * 1.5f * s
            val sc = (rnd.nextFloat() * .8f + .6f) * 60 * s
            val base = if (st.night) 0x46 else if (st.kind == "rain" || st.kind == "thunder") 0x5A else 0xFF
            val rgb = if (st.night) Color.rgb(70, 78, 100) else if (st.kind == "rain" || st.kind == "thunder") Color.rgb(90, 100, 120) else Color.WHITE
            for ((dx, dy, k) in listOf(Triple(0f, 0f, 1f), Triple(-.55f, .12f, .7f), Triple(.55f, .1f, .75f), Triple(.2f, -.25f, .7f), Triple(-.25f, -.18f, .6f))) {
                val r = sc * .55f * k; val x = cx + dx * sc; val y = cy + dy * sc
                val a = ((if (st.night) .35f else if (st.kind == "rain") .55f else .5f) * 255).toInt()
                p.shader = RadialGradient(x, y, r, intArrayOf(Color.argb(a, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Color.argb(0, Color.red(rgb), Color.green(rgb), Color.blue(rgb))), null, Shader.TileMode.CLAMP)
                c.drawCircle(x, y, r, p) }
            p.shader = null; if (base == 0) Unit
        }
        // fog bands drift (period = width)
        if (st.kind == "fog") repeat(3) { i ->
            val y = h * (.3f + i * .22f); val off = (t * w + i * w / 3f) % w
            p.shader = LinearGradient(0f, y - 30 * s, 0f, y + 30 * s, intArrayOf(0x00E6EAF0, if (st.night) 0x1FE6EAF0 else 0x47E6EAF0, 0x00E6EAF0), null, Shader.TileMode.CLAMP)
            c.drawRect(-off, y - 30 * s, w * 2f - off, y + 30 * s, p); p.shader = null }
        st.visitor?.let { visitor(c, p, st, it, w, h, t, s) }
        // rain: a tiled field that falls exactly one tile per loop (seamless)
        if (st.kind == "rain" || st.kind == "thunder") {
            val tile = h / 2f; val count = intArrayOf(0, 40, 80, 140, 220)[st.lvl] * w / 400
            val sl = min(.35f, .08f + st.wind / 120)
            p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = (1f + st.lvl * .2f) * s * .8f
            repeat(count) {
                val x0 = rnd.nextFloat() * w * 1.2f; val y0 = rnd.nextFloat() * tile; val len = (10 + rnd.nextFloat() * 12) * s * (1 + st.lvl * .15f)
                p.color = if (st.night) Color.argb((60 + rnd.nextInt(70)), 190, 205, 235) else Color.argb((60 + rnd.nextInt(80)), 235, 242, 255)
                var y = (y0 + t * tile) % tile - tile
                while (y < h + len) { val x = x0 - (y + tile) * sl * .3f; c.drawLine(x, y, x - len * sl, y - len, p); y += tile }
            }
            if (st.kind == "thunder" && f == n / 2) { p.color = 0x55EBF0FF; c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p) }
        }
        // snow: tiled field, one tile per loop, with sway
        if (st.kind == "snow") {
            val tile = h / 3f
            repeat(intArrayOf(0, 30, 60, 100)[st.lvl] * w / 400) {
                val x0 = rnd.nextFloat() * w; val y0 = rnd.nextFloat() * tile; val r = (rnd.nextFloat() * 1.6f + 1f) * s; val ph = rnd.nextFloat() * 6.28f
                p.color = 0xD9FFFFFF.toInt()
                var y = (y0 + t * tile) % tile - tile
                while (y < h + r) { c.drawCircle(x0 + sin(2 * PI * t + ph + y / 40).toFloat() * 4 * s, y, r, p); y += tile }
            }
        }
        // drops on the glass (fixed) + one sliding down per loop
        if (st.kind == "rain" || st.kind == "thunder" || st.kind == "fog") {
            val nd = (if (st.kind == "fog") 8 else intArrayOf(0, 10, 18, 28, 40)[st.lvl]) * w / 400
            repeat(nd) { drop(c, p, rnd.nextFloat() * w, rnd.nextFloat() * h, (rnd.nextFloat() * 3.5f + 1.6f) * s, false) }
            if (st.kind != "fog") repeat(2) { i -> val x = w * (.25f + .45f * i); val y = ((t + i * .5f) % 1f) * (h + 20 * s) - 10 * s
                for (k in 1..4) { p.color = 0x2EFFFFFF; c.drawCircle(x + sin(k * 1.7f) * s, y - k * 7 * s, 1.1f * s, p) }
                drop(c, p, x, y, 4.2f * s, true) }
        }
        return b
    }

    /** Visitors move in seamless loops (flap, bob, blink, spin) so the short frame cycle never jumps. */
    private fun visitor(c: Canvas, p: Paint, st: State, k: String, w: Int, h: Int, t: Float, s: Float) {
        val r = Random(k.hashCode() + st.m / 15); val T = (2 * PI).toFloat()
        val dk = if (st.night) Color.argb(215, 15, 18, 30) else if (st.phase == "sunset" || st.phase == "dusk") Color.argb(205, 40, 25, 45) else Color.argb(185, 35, 42, 60)
        p.style = Paint.Style.FILL; p.shader = null
        when (k) {
            "birds" -> { val cx = w * (.2f + r.nextFloat() * .5f) + sin(T * t) * 5 * s; val cy = h * (.15f + r.nextFloat() * .2f)
                val n = 3 + r.nextInt(4); p.style = Paint.Style.STROKE; p.color = dk; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 1.6f * s
                for (i in 0 until n) { val off = r.nextFloat() * T; val sz = (6 + r.nextFloat() * 3) * s
                    val x = cx - kotlin.math.abs(i - n / 2) * 15 * s; val y = cy + (i - n / 2) * 8 * s; val wg = sin(T * t * 2 + off)
                    val path = android.graphics.Path(); path.moveTo(x - sz, y - sz * .45f * wg); path.quadTo(x - sz * .45f, y - sz * .2f * wg - sz * .15f, x, y + wg * s)
                    path.quadTo(x + sz * .45f, y - sz * .2f * wg - sz * .15f, x + sz, y - sz * .45f * wg); c.drawPath(path, p) }
                p.style = Paint.Style.FILL }
            "plane" -> { val x = w * (.35f + r.nextFloat() * .4f); val y = h * (.1f + r.nextFloat() * .15f); val L = w * .45f
                p.shader = LinearGradient(x, 0f, x - L, 0f, 0xB3FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP); p.strokeWidth = 2f * s; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(x - 6 * s, y - 1.6f * s, x - L, y - 1.6f * s, p); c.drawLine(x - 6 * s, y + 1.6f * s, x - L, y + 1.6f * s, p); p.shader = null
                p.color = 0xF2F5F8FF.toInt(); c.drawOval(RectF(x - 9 * s, y - 1.8f * s, x + 9 * s, y + 1.8f * s), p)
                val wing = android.graphics.Path(); wing.moveTo(x + s, y); wing.lineTo(x - 3 * s, y - 6 * s); wing.lineTo(x - 5 * s, y - 6 * s); wing.lineTo(x - 2 * s, y)
                wing.lineTo(x - 5 * s, y + 6 * s); wing.lineTo(x - 3 * s, y + 6 * s); wing.close(); c.drawPath(wing, p) }
            "nightplane" -> { val x = w * (.25f + r.nextFloat() * .45f); val y = h * (.12f + r.nextFloat() * .15f)
                p.color = 0x59C8D2EB; c.drawRect(x - 7 * s, y - .6f * s, x + 7 * s, y + .6f * s, p)
                if (t < .6f) { p.color = 0xF2FF5050.toInt(); c.drawCircle(x - 3 * s, y - 4 * s, 1.5f * s, p); p.color = 0xF25AFF8C.toInt(); c.drawCircle(x - 3 * s, y + 4 * s, 1.5f * s, p) }
                if (t < .15f || (t > .5f && t < .62f)) { p.shader = RadialGradient(x, y, 10 * s, 0xF2FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP); c.drawCircle(x, y, 10 * s, p); p.shader = null } }
            "ufo" -> { val x = w * (.3f + r.nextFloat() * .4f) + sin(T * t) * 8 * s; val y = h * (.18f + r.nextFloat() * .1f) + sin(T * t * 2) * 2 * s
                p.shader = LinearGradient(0f, y, 0f, y + h * .45f, Color.argb((70 + 30 * sin(T * t * 2)).toInt(), 180, 255, 200), 0x00B4FFC8, Shader.TileMode.CLAMP)
                val beam = android.graphics.Path(); beam.moveTo(x - 7 * s, y + 3 * s); beam.lineTo(x + 7 * s, y + 3 * s); beam.lineTo(x + 30 * s, y + h * .45f); beam.lineTo(x - 30 * s, y + h * .45f); beam.close(); c.drawPath(beam, p); p.shader = null
                p.color = 0xBFBEE6FF.toInt(); c.drawArc(RectF(x - 7 * s, y - 10 * s, x + 7 * s, y + 2 * s), 180f, 180f, true, p)
                p.color = 0xFF9AA3B8.toInt(); c.drawOval(RectF(x - 18 * s, y - 5 * s, x + 18 * s, y + 5 * s), p)
                for (i in -2..2) { p.color = if (((t * 8).toInt() + i) % 2 == 0) 0xFFFFE36E.toInt() else 0xFFFF7AA8.toInt(); c.drawCircle(x + i * 7 * s, y + s, 1.5f * s, p) } }
            "balloon" -> { val x = w * (.55f + r.nextFloat() * .3f); val y = h * (.22f + r.nextFloat() * .15f) + sin(T * t) * 3 * s; val z = 15 * s
                val cols = listOf(intArrayOf(0xFFFF6B6B.toInt(), 0xFFFFD166.toInt()), intArrayOf(0xFF4ECDC4.toInt(), -1), intArrayOf(0xFF7B61FF.toInt(), 0xFFFFB3C7.toInt()), intArrayOf(0xFF3B82F6.toInt(), 0xFFFDE68A.toInt()))[r.nextInt(4)]
                p.color = 0xCC503C28.toInt(); p.strokeWidth = s; c.drawLine(x - z * .45f, y + z * .95f, x - z * .22f, y + z * 1.55f, p); c.drawLine(x + z * .45f, y + z * .95f, x + z * .22f, y + z * 1.55f, p)
                p.color = 0xFF8B5E3C.toInt(); c.drawRect(x - z * .25f, y + z * 1.5f, x + z * .25f, y + z * 1.88f, p)
                val env = android.graphics.Path(); env.moveTo(x, y - z * 1.2f); env.cubicTo(x + z * 1.25f, y - z * 1.2f, x + z * 1.15f, y + z * .3f, x + z * .4f, y + z)
                env.lineTo(x - z * .4f, y + z); env.cubicTo(x - z * 1.15f, y + z * .3f, x - z * 1.25f, y - z * 1.2f, x, y - z * 1.2f); env.close()
                p.color = cols[0]; c.drawPath(env, p); c.save(); c.clipPath(env); p.color = cols[1]
                c.drawRect(x - z * .55f, y - z * 1.3f, x - z * .13f, y + z * 1.1f, p); c.drawRect(x + z * .1f, y - z * 1.3f, x + z * .52f, y + z * 1.1f, p); c.restore() }
            "paper" -> { val x = w * (.3f + r.nextFloat() * .4f) + sin(T * t) * 10 * s; val y = h * (.3f + r.nextFloat() * .15f) + kotlin.math.cos(T * t) * 6 * s
                c.save(); c.translate(x, y); c.rotate(sin(T * t) * 12f)
                val a = android.graphics.Path(); a.moveTo(12 * s, 0f); a.lineTo(-10 * s, -6 * s); a.lineTo(-6 * s, 0f); a.close(); p.color = 0xF2FFFFFF.toInt(); c.drawPath(a, p)
                val b = android.graphics.Path(); b.moveTo(12 * s, 0f); b.lineTo(-6 * s, 0f); b.lineTo(-10 * s, 4 * s); b.close(); p.color = 0xF2D2DCF0.toInt(); c.drawPath(b, p); c.restore() }
            "leaves", "petals" -> { val pet = k == "petals"; val tile = h / 2f
                val pal = if (pet) intArrayOf(0xFFF9C9D6.toInt(), 0xFFFBD9E2.toInt(), 0xFFF4B4C6.toInt()) else intArrayOf(0xFFE07A2F.toInt(), 0xFFC9452B.toInt(), 0xFFE9B53B.toInt(), 0xFFB85C2C.toInt())
                repeat(7 * w / 300 + 3) { val x0 = r.nextFloat() * w; val y0 = r.nextFloat() * tile; val ph = r.nextFloat() * T; val z = (if (pet) 3.5f else 5.5f) * s; val col = pal[r.nextInt(pal.size)]
                    var y = (y0 + t * tile) % tile - tile
                    while (y < h + z) { c.save(); c.translate(x0 + sin(T * t + ph + y / 30) * 6 * s, y); c.rotate((t * 360 + ph * 57).toFloat()); c.scale(1f, .55f + .45f * sin(T * t * 2 + ph)); p.color = col
                        if (pet) c.drawOval(RectF(-z, -z * .62f, z, z * .62f), p) else { val lf = android.graphics.Path(); lf.moveTo(-z, 0f); lf.quadTo(0f, -z * .75f, z, 0f); lf.quadTo(0f, z * .75f, -z, 0f); c.drawPath(lf, p) }
                        c.restore(); y += tile } } }
            "umbrella" -> { val x = w * (.3f + r.nextFloat() * .4f) + sin(T * t) * 10 * s; val y = h * (.3f + r.nextFloat() * .2f) + sin(T * t * 2) * 5 * s; val z = 15 * s
                c.save(); c.translate(x, y); c.rotate(t * 360f)
                val u = android.graphics.Path(); u.moveTo(-z, 0f); u.quadTo(0f, -z * 1.3f, z, 0f); for (i in 0 until 4) { val a = z - i * z / 2; u.quadTo(a - z / 4, -z * .22f, a - z / 2, 0f) }; u.close()
                p.color = intArrayOf(0xFFEF4444.toInt(), 0xFFF59E0B.toInt(), 0xFF3B82F6.toInt(), 0xFF10B981.toInt())[r.nextInt(4)]; c.drawPath(u, p)
                p.style = Paint.Style.STROKE; p.color = 0xE63C3228.toInt(); p.strokeWidth = 1.5f * s; c.drawLine(0f, -z * .62f, 0f, z * .95f, p)
                c.drawArc(RectF(-z * .44f, z * .73f, 0f, z * 1.17f), 0f, 180f, false, p); p.style = Paint.Style.FILL; c.restore() }
            "rainbow" -> { val cx = w * .7f; val cy = h * 1.05f; val r0 = max(w, h) * .7f; p.style = Paint.Style.STROKE; p.strokeWidth = 5f * s
                intArrayOf(0xFFFF5A5A.toInt(), 0xFFFF9A3C.toInt(), 0xFFFFE15A.toInt(), 0xFF5AD27A.toInt(), 0xFF4AA8FF.toInt(), 0xFF6A6CFF.toInt(), 0xFFB26BFF.toInt()).forEachIndexed { i, col ->
                    p.color = col; p.alpha = if (st.kind == "rain") 95 else 70; val rr = r0 - i * 4.6f * s; c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), 189f, 126f, false, p) }
                p.alpha = 255; p.style = Paint.Style.FILL }
            "fireflies" -> repeat(8) { val x = r.nextFloat() * w + sin(T * t + it) * 4 * s; val y = h * (.45f + r.nextFloat() * .5f); val gl = .5f + .5f * sin(T * t + r.nextFloat() * T)
                p.shader = RadialGradient(x, y, 7 * s, Color.argb((230 * gl).toInt(), 230, 255, 140), 0x00E6FF8C, Shader.TileMode.CLAMP); c.drawCircle(x, y, 7 * s, p); p.shader = null }
        }
    }

    private fun drop(c: Canvas, p: Paint, x: Float, y: Float, r: Float, slide: Boolean) {
        p.shader = RadialGradient(x - r * .35f, y - r * .4f, r * 1.2f, intArrayOf(0x8CFFFFFF.toInt(), 0x1FFFFFFF, 0x2E14203A, 0x40FFFFFF), floatArrayOf(0f, .35f, .85f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(RectF(x - r * .9f, y - r * (if (slide) 1.25f else 1f), x + r * .9f, y + r * (if (slide) 1.25f else 1f)), p); p.shader = null
    }

    /* ---------- visitors that drift across the window (birds, planes, balloons ...) ---------- */
    class Visitor(val kind: String, val frames: List<Bitmap>, val interval: Int, val top: Float)

    fun visitorPool(st: State, month: Int): List<Pair<String, Int>> {
        val day = !st.night && st.phase != "late"; val wet = st.kind == "rain" || st.kind == "thunder"
        val heavy = (wet && st.lvl >= 3) || st.kind == "thunder" || (st.kind == "snow" && st.lvl >= 3)
        val p = mutableListOf<Pair<String, Int>>()
        if (day && !heavy && st.kind != "fog") p += "birds" to 5
        if (st.kind != "thunder" && st.kind != "fog") p += "plane" to 4
        if (day && st.cover <= .5f && !wet && st.kind != "snow") p += "balloon" to 2
        if (day && !wet && st.kind != "snow" && month in 8..11) p += "dragonfly" to 3
        if (day && !wet) p += "paper" to 1
        if (st.kind == "rain" && st.lvl <= 3) p += "umbrella" to 2
        if (st.night) p += "ufo" to 1
        if (st.night && month == 12) p += "sleigh" to 3
        return p
    }

    /** Up to n distinct visitors for this update, picked by weight. */
    fun pickVisitors(st: State, month: Int, n: Int, rnd: Random): List<String> {
        val pool = visitorPool(st, month).toMutableList(); val out = mutableListOf<String>()
        while (out.size < n && pool.isNotEmpty()) {
            var r = rnd.nextInt(pool.sumOf { it.second }); var pick = pool.last()
            for (x in pool) { r -= x.second; if (r < 0) { pick = x; break } }
            out += pick.first; pool.remove(pick)
        }
        return out
    }

    /** Small sprite frames drawn at s px per dp (bitmap density is set so it shows at dp size). */
    fun visitor(st: State, kind: String, s: Float, rnd: Random): Visitor {
        fun bmp(wd: Int, ht: Int, f: (Canvas, Paint) -> Unit): Bitmap {
            val b = Bitmap.createBitmap((wd * s).toInt(), (ht * s).toInt(), Bitmap.Config.ARGB_8888); b.density = (160 * s).toInt()
            val c = Canvas(b); c.scale(s, s); f(c, Paint(Paint.ANTI_ALIAS_FLAG)); return b }
        val warm = st.phase == "sunset" || st.phase == "dawn"
        val top = .06f + rnd.nextFloat() * .16f
        return when (kind) {
            "birds" -> {
                val n = 3 + rnd.nextInt(4); val ph = FloatArray(n) { rnd.nextFloat() * 6f }
                val frames = (0 until 3).map { f -> bmp(90, 48) { c, p ->
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.9f; p.strokeCap = Paint.Cap.ROUND; p.color = if (warm) 0xCC2D1928.toInt() else 0xA0232A3C.toInt()
                    for (i in 0 until n) { val k = (i + 1) / 2; val x = 80f - k * 17f; val y = 25f + (if (i % 2 == 1) 1 else -1) * k * 6.5f
                        val wv = sin(ph[i] + f * 2.1f); val up = 8f * (.15f + .55f * wv)
                        val path = android.graphics.Path(); path.moveTo(x - 8, y - up); path.quadTo(x - 3.6f, y - up * .2f - 1, x, y); path.quadTo(x + 3.6f, y - up * .2f - 1, x + 8, y - up); c.drawPath(path, p) } } }
                Visitor(kind, frames, 170, top)
            }
            "plane" -> if (st.night) Visitor(kind, (0 until 2).map { f -> bmp(30, 14) { c, p ->
                    p.color = 0xE61E2230.toInt(); c.drawOval(RectF(7f, 6f, 23f, 8.8f), p)
                    p.color = if (f == 0) 0xFFFF4D4D.toInt() else 0x55FF4D4D; c.drawCircle(13f, 10f, 1.6f, p)
                    if (f == 0) { p.color = Color.WHITE; c.drawCircle(24f, 7f, 1.8f, p) } } }, 600, top)
                else Visitor(kind, listOf(bmp(150, 18) { c, p ->
                    if (st.cover < .5f) { p.shader = LinearGradient(0f, 0f, 128f, 0f, 0x00FFFFFF, 0x99FFFFFF.toInt(), Shader.TileMode.CLAMP); p.strokeWidth = 2.2f; c.drawLine(0f, 9f, 128f, 9f, p); p.shader = null }
                    p.color = 0xF2F6F8FF.toInt(); c.drawOval(RectF(128f, 7.3f, 148f, 10.7f), p)
                    val w = android.graphics.Path(); w.moveTo(140f, 9f); w.lineTo(134f, 16f); w.lineTo(131.5f, 16f); w.lineTo(136f, 9f); w.close(); c.drawPath(w, p)
                    val t = android.graphics.Path(); t.moveTo(131f, 9f); t.lineTo(128f, 4f); t.lineTo(127f, 4f); t.lineTo(128f, 9f); t.close(); c.drawPath(t, p) }), 1000, top)
            "balloon" -> { val C = listOf(0xFFFF6B6B to 0xFFFFD166, 0xFF4ECDC4 to 0xFFFFFFFF, 0xFF9B5DE5 to 0xFFF15BB5, 0xFFFFB347 to 0xFF5BC0EB)[rnd.nextInt(4)]
                Visitor(kind, listOf(bmp(36, 48) { c, p -> val r = 14f; c.translate(18f, 18f)
                    val e = android.graphics.Path(); e.moveTo(-r * .35f, r * 1.05f); e.cubicTo(-r * 1.4f, r * .2f, -r, -r * 1.25f, 0f, -r * 1.25f); e.cubicTo(r, -r * 1.25f, r * 1.4f, r * .2f, r * .35f, r * 1.05f); e.close()
                    p.color = C.first.toInt(); c.drawPath(e, p); c.save(); c.clipPath(e); p.color = C.second.toInt(); c.drawOval(RectF(-r * .36f, -r * 1.5f, r * .36f, r * 1.5f), p); c.restore()
                    p.color = 0xCC50403A.toInt(); p.strokeWidth = .8f; c.drawLine(-r * .35f, r * 1.05f, -r * .2f, r * 1.45f, p); c.drawLine(r * .35f, r * 1.05f, r * .2f, r * 1.45f, p)
                    p.color = 0xFF8B5A2B.toInt(); c.drawRect(-r * .22f, r * 1.45f, r * .22f, r * 1.75f, p) }), 1000, .25f + rnd.nextFloat() * .15f) }
            "dragonfly" -> Visitor(kind, (0 until 2).map { f -> bmp(60, 26) { c, p ->
                    for ((x, y) in listOf(18f to 12f, 46f to 18f)) {
                        p.color = if (f == 0) 0x99FFFFFF.toInt() else 0x44FFFFFF
                        for ((an, o) in listOf(30f to 2f, -30f to 2f, 48f to -1f, -48f to -1f)) { c.save(); c.rotate(an, x + o, y - 1); c.drawOval(RectF(x + o - 8, y - 3.2f, x + o + 8, y + 1.2f), p); c.restore() }
                        p.color = 0xFFD8452E.toInt(); p.strokeWidth = 2.2f; p.strokeCap = Paint.Cap.ROUND; c.drawLine(x - 10, y, x + 5, y, p)
                        p.color = 0xFFB8321F.toInt(); c.drawCircle(x + 6.5f, y, 2f, p) } } }, 70, top + .08f)
            "paper" -> Visitor(kind, (0 until 4).map { f -> bmp(30, 22) { c, p -> c.translate(15f, 11f); c.rotate(-12f + f * 8f)
                    val t = android.graphics.Path(); t.moveTo(13f, 0f); t.lineTo(-10f, -6f); t.lineTo(-5f, 0f); t.lineTo(-10f, 6f); t.close()
                    p.color = 0xF5FFFFFF.toInt(); c.drawPath(t, p); p.style = Paint.Style.STROKE; p.strokeWidth = .8f; p.color = 0x403C465A; c.drawPath(t, p); c.drawLine(13f, 0f, -5f, 0f, p) } }, 260, top)
            "umbrella" -> { val col = intArrayOf(0xFFFF6B8B.toInt(), 0xFFFFD166.toInt(), 0xFF5BC0EB.toInt(), 0xFF9B5DE5.toInt())[rnd.nextInt(4)]
                Visitor(kind, (0 until 6).map { f -> bmp(40, 40) { c, p -> c.translate(20f, 18f); c.rotate(f * 60f)
                    p.color = col; c.drawArc(RectF(-14f, -14f, 14f, 14f), 180f, 180f, true, p)
                    p.style = Paint.Style.STROKE; p.color = 0xFF444444.toInt(); p.strokeWidth = 1.4f; c.drawLine(0f, 0f, 0f, 14f, p); c.drawArc(RectF(-6f, 11f, 0f, 17f), 0f, 180f, false, p) } }, 140, top + .05f) }
            "ufo" -> Visitor(kind, (0 until 3).map { f -> bmp(44, 40) { c, p ->
                    if (f != 2) { p.shader = LinearGradient(0f, 12f, 0f, 40f, 0x66AAFFD2, 0x00AAFFD2, Shader.TileMode.CLAMP); val b = android.graphics.Path(); b.moveTo(18f, 12f); b.lineTo(26f, 12f); b.lineTo(36f, 40f); b.lineTo(8f, 40f); b.close(); c.drawPath(b, p); p.shader = null }
                    p.color = 0xD9B4E6FF.toInt(); c.drawArc(RectF(15f, 2f, 29f, 14f), 180f, 180f, true, p)
                    p.color = 0xFFAEB6C8.toInt(); c.drawOval(RectF(4f, 6f, 40f, 15f), p)
                    intArrayOf(0xFFFF5A5A.toInt(), 0xFFFFE35A.toInt(), 0xFF5AB8FF.toInt()).forEachIndexed { i, col -> p.color = if (i == f) col else 0x80FFFFFF.toInt(); c.drawCircle(13f + i * 9, 11.5f, 1.5f, p) } } }, 240, top)
            else -> Visitor("sleigh", (0 until 2).map { f -> bmp(80, 26) { c, p ->
                    p.color = 0xEBFFE296.toInt(); p.strokeWidth = 1.2f
                    for (i in 0 until 8) { p.alpha = 200 - i * 22; c.drawCircle(8f + i * 3f + (f * 1.5f), 18f + (i % 3), 1.1f, p) }; p.alpha = 235
                    c.drawRect(32f, 10f, 46f, 16f, p); p.style = Paint.Style.STROKE; c.drawLine(29f, 19f, 50f, 19f, p); c.drawLine(47f, 12f, 63f, 11f, p); p.style = Paint.Style.FILL
                    for (o in listOf(56f, 70f)) { val ry = 9f + (if (f == 0) 0f else 1f); c.drawOval(RectF(o - 5, ry - 2.4f, o + 5, ry + 2.4f), p); c.drawCircle(o + 5, ry - 3, 1.7f, p)
                        c.drawLine(o + 5, ry - 4, o + 4, ry - 8, p); c.drawLine(o + 6, ry - 4, o + 8, ry - 8, p)
                        c.drawLine(o - 3, ry + 2, o - 4 + f * 2, ry + 6, p); c.drawLine(o + 3, ry + 2, o + 4 - f * 2, ry + 6, p) } } }, 220, top)
        }
    }

    /** Window frame and a soft reflection, drawn once over the frames. */
    fun glass(w: Int, h: Int, d: Float): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0x22FFFFFF, 0x00FFFFFF, 0x10FFFFFF), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.color = 0x55FFFFFF; p.strokeWidth = 2.5f * d
        c.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), p); c.drawLine(0f, h * .42f, w.toFloat(), h * .42f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f * d; p.color = 0x66FFFFFF
        c.drawRoundRect(RectF(2.5f * d, 2.5f * d, w - 2.5f * d, h - 2.5f * d), 22 * d, 22 * d, p)
        return b
    }
}
