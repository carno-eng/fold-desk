package io.github.carnoeng.folddesk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import kotlin.math.hypot
import kotlin.random.Random

/** The concierge character: same clips and timing as the web app, drawn on any Canvas. */
class Sprite(private val ctx: Context, heightDp: Float = 92f) {
    companion object {
        private val D = IntArray(201) { 200 }.also { d ->
            intArrayOf(280,110,110,140,140,320,120,120,120,120,120,120,120,220,120,120,120,120,120,120,120,220,140,140,140,280,140,140,140,140,280,140,140,140,140,140,140,140,240,150,150,150,150,150,260,120,120,120,120,120,220,150,150,150,150,150,280,
            130,130,130,130,130,130, 160,140,150,150,150,280, 200,110,110,240,150,260, 130,130,130,130,130,280, 240,170,170,520,190,340).copyInto(d)
            listOf(Triple(95, 102, 120), Triple(103, 109, 90), Triple(111, 118, 150), Triple(127, 131, 170), Triple(135, 143, 170), Triple(168, 175, 320))
                .forEach { (a, b, ms) -> for (f in a..b) d[f] = ms }
            mapOf(110 to 300, 118 to 320, 126 to 420, 143 to 320, 151 to 600, 159 to 600, 167 to 600, 175 to 700, 176 to 700, 177 to 450, 178 to 260, 179 to 260,
                180 to 700, 181 to 700, 182 to 700, 183 to 700, 191 to 600, 199 to 700, 200 to 900, 147 to 320, 148 to 320, 156 to 320, 157 to 320).forEach { (f, ms) -> d[f] = ms }
        }
        private fun r(a: Int, b: Int) = (a..b).toList()
        val CLIPS = mapOf(
            "greet" to r(0, 5), "runR" to r(6, 12), "runL" to r(14, 20), "wave" to r(22, 25) + listOf(23, 24, 25),
            "joy" to r(26, 30), "sorry" to r(31, 39), "cheeks" to r(39, 44), "think" to r(45, 50), "idle" to r(51, 56),
            "hop" to r(57, 62), "wave2" to listOf(63, 64, 65, 66, 65, 66, 67, 68), "jump" to r(69, 74), "spin" to r(75, 80), "bow" to listOf(81, 82, 83, 84, 84, 85, 86),
            "blink" to r(87, 94), "walkR" to r(95, 102), "walkL" to r(95, 102), "dashR" to r(103, 109), "dashL" to r(103, 109), "hello" to r(111, 118), "stretch" to r(119, 126),
            "giggle" to listOf(127, 128, 129, 130, 131, 130, 131), "ponder" to listOf(132, 133, 134, 133), "bow2" to r(135, 143),
            "strawberry" to r(144, 151), "cake" to r(152, 159), "paint" to r(160, 167), "read" to r(168, 175) + listOf(174, 175), "surprise" to listOf(176, 176),
            "nervous" to listOf(177, 177, 177), "cry" to listOf(178, 179, 178, 179, 178, 179), "pout" to listOf(180, 180), "relief" to listOf(181, 181),
            "sparkle" to listOf(182, 182), "heart" to listOf(183, 183), "bunny" to r(184, 191), "garden" to r(192, 199), "tea" to listOf(200, 200, 200))
        private val LOOP = setOf("idle", "runR", "runL", "think", "hop", "walkR", "walkL", "dashR", "dashL", "ponder")
        private val FLIP = setOf("walkL", "dashL")
        private val FALLBACK = mapOf("hop" to "runR", "wave2" to "wave", "jump" to "joy", "spin" to "idle", "bow" to "greet", "blink" to "idle", "walkR" to "runR", "walkL" to "runL",
            "dashR" to "runR", "dashL" to "runL", "hello" to "wave2", "stretch" to "joy", "giggle" to "cheeks", "ponder" to "think", "bow2" to "bow", "strawberry" to "joy",
            "cake" to "jump", "paint" to "idle", "read" to "idle", "surprise" to "wave", "nervous" to "sorry", "cry" to "sorry", "pout" to "think", "relief" to "joy",
            "sparkle" to "jump", "heart" to "cheeks", "bunny" to "idle", "garden" to "idle", "tea" to "idle")
        private val ACTIVITIES = listOf("paint", "read", "bunny", "garden", "tea", "strawberry", "stretch")
        const val FW = 192; const val FH = 208
        fun artFile(c: Context) = File(c.filesDir, "concierge.img")
        @Volatile private var shared: Bitmap? = null
        private var sharedStamp = 0L
        fun hasArt(c: Context) = artFile(c).length() > 0
    }

    private val density = ctx.resources.displayMetrics.density
    val h = (heightDp * density).toInt()
    val w = (h * FW / FH.toFloat()).toInt()
    var x = 0f; var y = 0f
    private var atlas: Bitmap? = null
    private var clip = "greet"; private var fi = 0; private var acc = 0L
    private var tx = -1f; private var ty = -1f
    private var nextMove = 0L
    private var dash = false
    var calm = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2E0A1432 }

    // speech bubble
    private var bubble: List<String> = emptyList(); private var bubbleUntil = 0L
    private val bubbleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt(); setShadowLayer(12f * density, 0f, 4f * density, 0x33000000) }
    // follows the phone's font-size setting (sp), and is larger than in-app text so it reads at a glance
    private fun sp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B0F.toInt(); textSize = sp(19f); isFakeBoldText = true }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A62F5.toInt(); textSize = sp(13f); isFakeBoldText = true }

    fun load(): Boolean {
        val f = artFile(ctx)
        if (!f.exists()) return false
        // one shared copy for the wallpaper and the full-screen mode (the atlas is large)
        atlas = synchronized(Companion) {
            if (shared == null || sharedStamp != f.lastModified() + f.length()) {
                shared = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }; sharedStamp = f.lastModified() + f.length()
            }
            shared
        }
        return atlas != null
    }
    val ready get() = atlas != null

    /** frames in the loaded atlas; newer motions are used only when the atlas has them */
    private val frames get() = atlas?.let { (it.height / FH) * 8 } ?: 64
    private fun has(n: String) = (CLIPS[n]?.maxOrNull() ?: 999) < frames
    private fun variant(n: String): String {
        if (!has("bow")) return n
        val r = Random.nextFloat(); val more = has("hello")
        return when {
            n == "wave" -> if (more) (if (r < .35f) "hello" else if (r < .65f) "wave2" else "wave") else if (r < .5f) "wave2" else "wave"
            n == "joy" -> if (more) (if (r < .25f) "jump" else if (r < .45f) "relief" else if (r < .6f) "sparkle" else "joy") else if (r < .4f) "jump" else "joy"
            n == "greet" -> if (more) (if (r < .2f) "bow" else if (r < .35f) "hello" else "greet") else if (r < .3f) "bow" else "greet"
            n == "bow" && more && r < .5f -> "bow2"
            n == "cheeks" && more -> if (r < .35f) "giggle" else if (r < .5f) "heart" else "cheeks"
            n == "think" && more && r < .5f -> "ponder"
            else -> n
        }
    }
    private fun resolve(n0: String): String { var n = n0; repeat(4) { if (!has(n)) n = FALLBACK[n] ?: "idle" }; return if (has(n)) n else "idle" }
    fun play(name0: String) { val name = resolve(variant(name0)); if (clip == name && name in LOOP) return; clip = name; fi = 0; acc = 0 }
    fun say(lines: List<String>, ms: Long = 7000, pose: String = "wave") {
        bubble = lines.filter { it.isNotBlank() }.take(4); bubbleUntil = System.currentTimeMillis() + ms; tx = -1f; play(pose); nextMove = System.currentTimeMillis() + ms + 2000
    }
    val talking get() = System.currentTimeMillis() < bubbleUntil
    fun hush() { bubbleUntil = 0 }
    fun hit(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
    fun place(px: Float, py: Float, b: Rect) { x = px.coerceIn(b.left.toFloat(), (b.right - w).toFloat()); y = py.coerceIn(b.top.toFloat(), (b.bottom - h).toFloat()) }

    /** Advances animation and walking. Returns true while something visible changed. */
    fun step(dt: Long, b: Rect): Boolean {
        val now = System.currentTimeMillis()
        val seq = CLIPS[clip] ?: return false
        val run = clip == "runR" || clip == "runL"
        val pace = if (clip.startsWith("walk")) .7f else if (clip.startsWith("dash")) 1.45f else 1f
        acc += dt
        var changed = false
        val dur = if (run) 105 else D[seq[fi]]
        if (acc >= dur) {
            acc = 0; fi++; changed = true
            if (fi >= seq.size) { fi = 0; if (clip !in LOOP) { val r = Random.nextFloat()
                clip = if (r < .06f && tx < 0 && !talking && has("paint")) ACTIVITIES.filter { has(it) }.random()
                    else if (r < .2f) resolve(variant("greet")) else if (r < .27f && has("spin")) "spin" else if (r < .45f && has("blink")) "blink" else "idle" } }
        }
        if (tx >= 0) {
            val dx = tx - x; val dy = ty - y; val dist = hypot(dx, dy); val v = 150f * pace * density * dt / 1000f
            if (dist <= v) { x = tx; y = ty; tx = -1f; play(if (Random.nextFloat() < .3f) "wave" else if (Random.nextFloat() < .12f) "spin" else "idle"); nextMove = now + 6000 + Random.nextLong(9000) }
            else { x += dx / dist * v; y += dy / dist * v; val gait = if (has("walkR")) (if (dash) "dash" else "walk") else "run"
                val want = if (kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.4f && has("hop")) "hop" else gait + if (dx >= 0) "R" else "L"; if (clip != want) { clip = want; fi = 0 } }
            changed = true
        } else if (!calm && !talking && now > nextMove && nextMove != 0L) {
            tx = b.left + Random.nextFloat() * (b.width() - w).coerceAtLeast(1); ty = b.top + Random.nextFloat() * (b.height() - h).coerceAtLeast(1)
            dash = hypot(tx - x, ty - y) > 260 * density && Random.nextFloat() < .6f
        } else if (nextMove == 0L) nextMove = now + 4000
        return changed || talking
    }

    fun frameRect(): Rect { val f = (CLIPS[clip] ?: listOf(0)).getOrElse(fi) { 0 }.let { if (it < frames) it else 51 }; return Rect((f % 8) * FW, (f / 8) * FH, (f % 8) * FW + FW, (f / 8) * FH + FH) }

    /** Draws the character (and its bubble) at its own position on the canvas. */
    fun draw(c: Canvas, ox: Float = x, oy: Float = y, withBubble: Boolean = true) {
        val a = atlas ?: return
        c.drawOval(RectF(ox + w * .22f, oy + h - 6 * density, ox + w * .78f, oy + h + 2 * density), shadow)
        if (clip in FLIP) { c.save(); c.scale(-1f, 1f, ox + w / 2f, oy + h / 2f); c.drawBitmap(a, frameRect(), RectF(ox, oy, ox + w, oy + h), paint); c.restore() }
        else c.drawBitmap(a, frameRect(), RectF(ox, oy, ox + w, oy + h), paint)
        if (withBubble && talking) drawBubble(c, ox, oy, c.width, c.height)
    }

    fun drawBubble(c: Canvas, ox: Float, oy: Float, cw: Int, ch: Int) {
        val maxW = minOf((320 * density).toInt(), (cw - 32 * density).toInt())
        val text = bubble.joinToString("\n")
        val lay = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, maxW).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4 * density, 1f).build()
        val pad = 16 * density
        val bw = maxOf((0 until lay.lineCount).maxOf { lay.getLineWidth(it) }, namePaint.measureText("コンシェルジュ")) + pad * 2
        val nameH = namePaint.textSize + 8 * density
        val bh = lay.height + pad * 2 + nameH
        var bx = ox + w / 2f - bw / 2f; bx = bx.coerceIn(8 * density, cw - bw - 8 * density)
        var by = oy - bh - 8 * density; if (by < 8 * density) by = oy + h + 8 * density
        c.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 22 * density, 22 * density, bubbleBg)
        c.drawText("コンシェルジュ", bx + pad, by + pad + namePaint.textSize * .85f, namePaint)
        c.save(); c.translate(bx + pad, by + pad + nameH); lay.draw(c); c.restore()
    }
}
