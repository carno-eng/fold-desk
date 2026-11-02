package io.github.carnoeng.folddesk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.service.dreams.DreamService
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Calendar

/**
 * Always-on style "now / next" for a phone resting on the desk: black background, dim light text,
 * the time, what is on now with a live countdown, what is next, the day's progress, and a sleepy concierge.
 * Everything drifts a few pixels every minute so nothing burns into the screen.
 */
class AodView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val sprite = Sprite(ctx, 64f).also { it.load(); it.calm = true; it.play("blink") }
    private val timeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9E8EAF6.toInt(); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val dateP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99B8BCD0.toInt(); typeface = Typeface.create("sans-serif", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCE8EAF6.toInt(); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val cdP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
    private val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8CB8BCD0.toInt(); textAlign = Paint.Align.CENTER }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val barFg = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = SystemClock.uptimeMillis()

    init { setBackgroundColor(Color.BLACK); keepScreenOn = true }

    private fun shift(): Pair<Float, Float> {
        val m = (System.currentTimeMillis() / 60_000).toInt()
        return Pair(((m * 37) % 25 - 12) * d, ((m * 53) % 25 - 12) * d)
    }

    override fun onDraw(c: Canvas) {
        val s = Store.snapshot(context)
        val w = width.toFloat(); val h = height.toFloat()
        val (sx, sy) = shift()
        val unit = minOf(w, h)
        val cx = w / 2 + sx
        val cal = Calendar.getInstance()
        val wd = arrayOf("日", "月", "火", "水", "木", "金", "土")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val st = Sched.status(s)
        val mood = Mood.of(s)
        val now = System.currentTimeMillis()

        var y = h * (if (w > h) 0.30f else 0.27f) + sy
        timeP.textSize = unit * 0.24f
        c.drawText("%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)), cx, y, timeP)
        dateP.textSize = unit * 0.045f
        y += unit * 0.085f
        c.drawText("${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日（$wd）" + (s?.let { Sched.today(it) }?.optString("mode")?.takeIf { it.isNotEmpty() && it != "通常" }?.let { "  ・  $it" } ?: ""), cx, y, dateP)

        y += unit * 0.15f
        lineP.textSize = unit * 0.058f
        c.drawText(fit(st.line, lineP, w * 0.9f), cx, y, lineP)
        if (st.target > 0) {
            val left = ((st.target - now) / 1000).coerceAtLeast(0)
            cdP.color = (mood.accent and 0x00FFFFFF) or (0xE6 shl 24)
            cdP.textSize = unit * 0.11f
            y += unit * 0.13f
            val txt = if (left >= 600) "${(left + 59) / 60} 分" else "%d:%02d".format(left / 60, left % 60)
            c.drawText(txt, cx, y, cdP)
            subP.textSize = unit * 0.038f
            y += unit * 0.06f
            c.drawText(if (st.now) "のこり" else "はじまるまで", cx, y, subP)
        }
        if (st.next.isNotEmpty()) {
            subP.textSize = unit * 0.045f
            y += unit * 0.09f
            c.drawText(fit(st.next, subP, w * 0.9f), cx, y, subP)
        }
        // day progress
        NowNotif.window(s)?.let { (a0, b) ->
            val a = a0 + 30 * 60_000L
            if (now in a0..b + 3_600_000) {
                val bw = minOf(w * 0.7f, 520 * d); val bx = cx - bw / 2; y += unit * 0.09f
                val r = RectF(bx, y, bx + bw, y + 3 * d)
                c.drawRoundRect(r, 3 * d, 3 * d, barBg)
                barFg.color = (mood.accent and 0x00FFFFFF) or (0xB3 shl 24)
                val f = ((now - a).toFloat() / (b - a).coerceAtLeast(1)).coerceIn(0f, 1f)
                c.drawRoundRect(RectF(bx, y, bx + bw * f, y + 3 * d), 3 * d, 3 * d, barFg)
            }
        }
        // a dim concierge in the corner
        if (sprite.ready) {
            val dt = SystemClock.uptimeMillis() - last; last = SystemClock.uptimeMillis()
            sprite.place(w - sprite.w - 28 * d + sx, h - sprite.h - 36 * d + sy, Rect(0, 0, width, height))
            sprite.step(dt.coerceAtMost(200), Rect(0, 0, width, height))
            val save = c.saveLayerAlpha(0f, 0f, w, h, 120)
            sprite.draw(c, withBubble = false)
            c.restoreToCount(save)
        }
        postInvalidateDelayed(if (st.target > 0 && st.target - now < 600_000) 1000L else 5000L)
    }

    private fun fit(t: String, p: Paint, max: Float): String {
        if (p.measureText(t) <= max) return t
        var e = t; while (e.length > 2 && p.measureText("$e…") > max) e = e.dropLast(1); return "$e…"
    }
}

/** Screen saver (Android "スクリーンセーバー"): shows the desk view while charging or docked. */
class AodDream : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true; isScreenBright = false
        setContentView(AodView(this))
    }
}

/** The same view opened from the app; works over the lock screen. Tap anywhere to close. */
class AodActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.12f }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val v = AodView(this); v.setOnClickListener { finish() }
        setContentView(v)
    }
}
