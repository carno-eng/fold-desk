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
        private val D = intArrayOf(280,110,110,140,140,320,120,120,120,120,120,120,120,220,120,120,120,120,120,120,120,220,140,140,140,280,140,140,140,140,280,140,140,140,140,140,140,140,240,150,150,150,150,150,260,120,120,120,120,120,220,150,150,150,150,150,280,
            130,130,130,130,130,130, 160,140,150,150,150,280, 200,110,110,240,150,260, 130,130,130,130,130,280, 240,170,170,520,190,340)
        private fun r(a: Int, b: Int) = (a..b).toList()
        val CLIPS = mapOf(
            "greet" to r(0, 5), "runR" to r(6, 12), "runL" to r(14, 20), "wave" to r(22, 25) + listOf(23, 24, 25),
            "joy" to r(26, 30), "sorry" to r(31, 39), "cheeks" to r(39, 44), "think" to r(45, 50), "idle" to r(51, 56),
            "hop" to r(57, 62), "wave2" to listOf(63, 64, 65, 66, 65, 66, 67, 68), "jump" to r(69, 74), "spin" to r(75, 80), "bow" to listOf(81, 82, 83, 84, 84, 85, 86))
        private val LOOP = setOf("idle", "runR", "runL", "think", "hop")
        private val FALLBACK = mapOf("hop" to "runR", "wave2" to "wave", "jump" to "joy", "spin" to "idle", "bow" to "greet")
        const val FW = 192; const val FH = 208
        fun artFile(c: Context) = File(c.filesDir, "concierge.img")
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
        atlas = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
        return atlas != null
    }
    val ready get() = atlas != null

    /** frames in the loaded atlas; newer motions are used only when the atlas has them */
    private val frames get() = atlas?.let { (it.height / FH) * 8 } ?: 64
    private fun has(n: String) = (CLIPS[n]?.maxOrNull() ?: 999) < frames
    private fun variant(n: String): String {
        if (!has("bow")) return if (!has(n)) FALLBACK[n] ?: "idle" else n
        val r = Random.nextFloat()
        return when { n == "wave" && r < .5f -> "wave2"; n == "joy" && r < .4f -> "jump"; n == "greet" && r < .3f -> "bow"; else -> n }
    }
    fun play(name0: String) { val name = variant(name0).let { if (has(it)) it else FALLBACK[it] ?: "idle" }; if (clip == name && name in LOOP) return; clip = name; fi = 0; acc = 0 }
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
        acc += dt
        var changed = false
        val dur = if (run) 105 else D[seq[fi]]
        if (acc >= dur) {
            acc = 0; fi++; changed = true
            if (fi >= seq.size) { fi = 0; if (clip !in LOOP) { val r = Random.nextFloat(); clip = if (r < .2f) variant("greet") else if (r < .27f && has("spin")) "spin" else "idle" } }
        }
        if (tx >= 0) {
            val dx = tx - x; val dy = ty - y; val dist = hypot(dx, dy); val v = 150f * density * dt / 1000f
            if (dist <= v) { x = tx; y = ty; tx = -1f; play(if (Random.nextFloat() < .3f) "wave" else if (Random.nextFloat() < .12f) "spin" else "idle"); nextMove = now + 6000 + Random.nextLong(9000) }
            else { x += dx / dist * v; y += dy / dist * v; val want = if (kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.4f && has("hop")) "hop" else if (dx >= 0) "runR" else "runL"; if (clip != want) { clip = want; fi = 0 } }
            changed = true
        } else if (!calm && !talking && now > nextMove && nextMove != 0L) {
            tx = b.left + Random.nextFloat() * (b.width() - w).coerceAtLeast(1); ty = b.top + Random.nextFloat() * (b.height() - h).coerceAtLeast(1)
        } else if (nextMove == 0L) nextMove = now + 4000
        return changed || talking
    }

    fun frameRect(): Rect { val f = (CLIPS[clip] ?: listOf(0)).getOrElse(fi) { 0 }.let { if (it < frames) it else 51 }; return Rect((f % 8) * FW, (f / 8) * FH, (f % 8) * FW + FW, (f / 8) * FH + FH) }

    /** Draws the character (and its bubble) at its own position on the canvas. */
    fun draw(c: Canvas, ox: Float = x, oy: Float = y, withBubble: Boolean = true) {
        val a = atlas ?: return
        c.drawOval(RectF(ox + w * .22f, oy + h - 6 * density, ox + w * .78f, oy + h + 2 * density), shadow)
        c.drawBitmap(a, frameRect(), RectF(ox, oy, ox + w, oy + h), paint)
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
