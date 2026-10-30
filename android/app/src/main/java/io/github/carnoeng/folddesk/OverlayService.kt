package io.github.carnoeng.folddesk

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs

/** Full-screen mode: the concierge walks over every app in a small touchable window. */
class OverlayService : Service() {
    companion object {
        const val ACTION_SAY = "io.github.carnoeng.folddesk.SAY"
        const val ACTION_STOP = "io.github.carnoeng.folddesk.STOP_OVERLAY"
        @Volatile var running = false
        fun enabled(c: Context) = c.getSharedPreferences("fd", Context.MODE_PRIVATE).getBoolean("overlay", false)
        fun setEnabled(c: Context, on: Boolean): Boolean {
            if (on && !Settings.canDrawOverlays(c)) {
                c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return false
            }
            if (on && !Sprite.hasArt(c)) return false
            c.getSharedPreferences("fd", Context.MODE_PRIVATE).edit().putBoolean("overlay", on).apply()
            val i = Intent(c, OverlayService::class.java)
            if (on) androidx.core.content.ContextCompat.startForegroundService(c, i) else c.stopService(i)
            return true
        }
        fun say(c: Context, title: String, body: String) {
            if (!running) return
            c.startService(Intent(c, OverlayService::class.java).setAction(ACTION_SAY).putExtra("t", title).putExtra("b", body))
        }
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private lateinit var sprite: Sprite
    private lateinit var charView: CharView
    private lateinit var bubbleView: BubbleView
    private lateinit var cp: WindowManager.LayoutParams
    private lateinit var bp: WindowManager.LayoutParams
    private var bubbleShown = false
    private var screenOn = true
    private var last = 0L
    private var lastCheck = 0L
    private val d by lazy { resources.displayMetrics.density }

    inner class CharView(c: Context) : View(c) {
        override fun onDraw(cv: Canvas) { sprite.draw(cv, 0f, 0f, withBubble = false) }
        private var dx = 0f; private var dy = 0f; private var downAt = 0L; private var moved = false
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - sprite.x; dy = e.rawY - sprite.y; downAt = SystemClock.uptimeMillis(); moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.rawX - dx - sprite.x) > 12 * d || abs(e.rawY - dy - sprite.y) > 12 * d)) { moved = true; sprite.play("joy") }
                    if (moved) { sprite.place(e.rawX - dx, e.rawY - dy, bounds()); move() }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && SystemClock.uptimeMillis() - downAt < 400) {
                        if (sprite.talking) { sprite.hush(); hideBubble(); openApp() }
                        else sayNow(Talk.lines(this@OverlayService), "cheeks")
                    }
                }
            }
            return true
        }
    }
    inner class BubbleView(c: Context) : View(c) {
        override fun onDraw(cv: Canvas) { sprite.drawBubble(cv, sprite.x, sprite.y, width, height) }
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - last).coerceIn(0, 80); last = now
            if (!sprite.ready) sprite.load()
            sprite.step(dt, bounds())
            move(); charView.invalidate()
            if (sprite.talking) { if (!bubbleShown) showBubble() else bubbleView.invalidate() } else if (bubbleShown) hideBubble()
            val wall = System.currentTimeMillis()
            if (wall - lastCheck > 20_000) { lastCheck = wall; checkTalk(wall) }
            if (screenOn) main.postDelayed(this, 40)
        }
    }

    private fun checkTalk(wall: Long) {
        if (sprite.talking) return
        val r = Talk.dueReminder(this, wall)
        if (r != null) { sayNow(r, "wave"); return }
        Talk.proactive(this)?.let { sayNow(it, Talk.pose, 9000) }
    }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT) { main.postDelayed({ if (running && screenOn) checkTalk(System.currentTimeMillis()) }, 2500); return }
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            main.removeCallbacks(frame)
            if (screenOn) { last = SystemClock.uptimeMillis(); main.post(frame) }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        startAsForeground()
        sprite = Sprite(this, 84f).also { it.load() }
        charView = CharView(this); bubbleView = BubbleView(this)
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        cp = WindowManager.LayoutParams(sprite.w, sprite.h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        bp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        val b = bounds()
        sprite.place(b.right - sprite.w - 20 * d, b.bottom - sprite.h - 40 * d, b)
        cp.x = sprite.x.toInt(); cp.y = sprite.y.toInt()
        try { wm.addView(charView, cp) } catch (_: Exception) { stopSelf(); return }
        running = true
        androidx.core.content.ContextCompat.registerReceiver(this, screenRx, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        last = SystemClock.uptimeMillis(); main.post(frame)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACTION_STOP -> { setEnabled(this, false); stopSelf() }
            ACTION_SAY -> sayNow(listOf(i.getStringExtra("t") ?: "", i.getStringExtra("b") ?: ""), "wave")
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); sprite.place(sprite.x, sprite.y, bounds()); move() }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(frame)
        try { unregisterReceiver(screenRx) } catch (_: Exception) { }
        try { wm.removeView(charView) } catch (_: Exception) { }
        hideBubble()
        super.onDestroy()
    }

    private fun sayNow(lines: List<String>, pose: String, ms: Long = 8000) { sprite.say(lines, ms, pose); showBubble() }
    private fun showBubble() { if (bubbleShown) return; try { wm.addView(bubbleView, bp); bubbleShown = true } catch (_: Exception) { } }
    private fun hideBubble() { if (!bubbleShown) return; try { wm.removeView(bubbleView) } catch (_: Exception) { }; bubbleShown = false }

    private fun move() {
        val nx = sprite.x.toInt(); val ny = sprite.y.toInt()
        if (cp.x != nx || cp.y != ny) { cp.x = nx; cp.y = ny; try { wm.updateViewLayout(charView, cp) } catch (_: Exception) { } }
    }

    private fun bounds(): Rect {
        val w: Int; val h: Int
        if (Build.VERSION.SDK_INT >= 30) { val m = wm.currentWindowMetrics.bounds; w = m.width(); h = m.height() }
        else { val dm = resources.displayMetrics; w = dm.widthPixels; h = dm.heightPixels }
        return Rect((4 * d).toInt(), (48 * d).toInt(), w - (4 * d).toInt(), h - (56 * d).toInt())
    }

    private fun openApp() {
        try { startActivity(Intent(this, MainActivity::class.java).putExtra("route", "today").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("overlay") == null)
            nm.createNotificationChannel(NotificationChannel("overlay", "コンシェルジュ（全画面）", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val stop = PendingIntent.getService(this, 3, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, "overlay")
            .setSmallIcon(R.drawable.ic_notify).setContentTitle("コンシェルジュが画面にいます").setContentText("タップで今日の案内。止めるときは「しまう」")
            .setOngoing(true).setContentIntent(open).addAction(0, "しまう", stop).setPriority(NotificationCompat.PRIORITY_MIN).build()
        ServiceCompat.startForeground(this, 77, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }
}
