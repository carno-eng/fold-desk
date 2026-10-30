package io.github.carnoeng.folddesk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.io.File

/** Live wallpaper: the concierge walks around the home screen. Runs only while the home screen is visible. */
class ConciergeWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = WEngine()

    inner class WEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private val sprite = Sprite(this@ConciergeWallpaper, 96f)
        private var visible = false
        private var last = 0L
        private var width = 0; private var height = 0
        private var bg: Bitmap? = null; private var bgFile = 0L
        private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var lastReminderCheck = 0L
        private val d = resources.displayMetrics.density
        private var downAt = 0L

        private val frame = object : Runnable {
            override fun run() {
                if (!visible) return
                val now = SystemClock.uptimeMillis()
                val dt = (now - last).coerceIn(0, 80); last = now
                if (!sprite.ready) sprite.load()
                sprite.step(dt, bounds())
                checkReminders()
                draw()
                main.postDelayed(this, 40)
            }
        }

        override fun onCreate(holder: SurfaceHolder) { super.onCreate(holder); setTouchEventsEnabled(true); sprite.load() }
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h); width = w; height = h; bg = null
            if (sprite.x == 0f && sprite.y == 0f) sprite.place(w * .6f, h * .62f, bounds())
            sprite.place(sprite.x, sprite.y, bounds()); draw()
        }
        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            main.removeCallbacks(frame)
            if (v) { last = SystemClock.uptimeMillis(); lastReminderCheck = System.currentTimeMillis() - 17_000; main.post(frame) }
        }
        override fun onDestroy() { visible = false; main.removeCallbacks(frame); super.onDestroy() }

        override fun onTouchEvent(e: MotionEvent) {
            if (e.action == MotionEvent.ACTION_DOWN) downAt = SystemClock.uptimeMillis()
            if (e.action == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - downAt < 300 && sprite.hit(e.x, e.y)) {
                if (sprite.talking) sprite.hush() else sprite.say(Talk.lines(this@ConciergeWallpaper), 8000, "cheeks")
            }
        }

        private fun bounds() = Rect((8 * d).toInt(), (90 * d).toInt(), width - (8 * d).toInt(), height - (130 * d).toInt())

        private fun checkReminders() {
            val now = System.currentTimeMillis()
            if (now - lastReminderCheck < 20_000) return
            lastReminderCheck = now
            if (sprite.talking) return
            val r = Talk.dueReminder(this@ConciergeWallpaper, now)
            if (r != null) { sprite.say(r, 9000, "wave"); return }
            Talk.proactive(this@ConciergeWallpaper)?.let { sprite.say(it, 9000, Talk.pose) }
        }

        private fun background(c: Canvas) {
            val f = File(filesDir, "wall_bg.img")
            if (f.exists() && f.lastModified() != bgFile) { bgFile = f.lastModified(); bg = null }
            if (bg == null && f.exists()) {
                bg = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
            }
            val b = bg
            if (b != null) {
                val s = maxOf(width / b.width.toFloat(), height / b.height.toFloat())
                val bw = b.width * s; val bh = b.height * s
                c.drawBitmap(b, null, RectF((width - bw) / 2, (height - bh) / 2, (width + bw) / 2, (height + bh) / 2), bgPaint)
            } else {
                val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                bgPaint.shader = LinearGradient(0f, 0f, width * .3f, height.toFloat(),
                    if (dark) intArrayOf(0xFF0B1A3A.toInt(), 0xFF050814.toInt(), 0xFF000000.toInt()) else intArrayOf(0xFFDCE8FF.toInt(), 0xFFF2F5FF.toInt(), 0xFFFFF4EE.toInt()),
                    null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
                bgPaint.shader = null
            }
        }

        private fun draw() {
            val h = surfaceHolder
            val c = try { h.lockCanvas() } catch (_: Exception) { null } ?: return
            try { background(c); sprite.draw(c) } finally { try { h.unlockCanvasAndPost(c) } catch (_: Exception) { } }
        }
    }
}
