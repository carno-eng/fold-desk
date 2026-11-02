package io.github.carnoeng.folddesk

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Methods the web app can call as window.FoldDeskNative.* */
class Bridge(
    private val ctx: Context,
    private val activity: MainActivity?,
    private val onDone: (() -> Unit)? = null
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun isApp(): Boolean = true

    @JavascriptInterface fun version(): String =
        JSONObject().put("code", BuildConfig.VERSION_CODE).put("name", BuildConfig.VERSION_NAME).toString()

    @JavascriptInterface fun setSnapshot(json: String) {
        Store.saveSnapshot(ctx, json)
        main.post {
            Widgets.updateAll(ctx)
            Scheduler.schedule(ctx)
            Quiet.apply(ctx)
            NowNotif.update(ctx)
        }
    }

    @JavascriptInterface fun takeActions(): String = Store.takeActions(ctx)

    /* calendars on this phone */
    @JavascriptInterface fun calState(): String = if (DeviceCal.granted(ctx)) "granted" else "none"
    @JavascriptInterface fun requestCalendar() { main.post { activity?.askCalendar() } }
    @JavascriptInterface fun calList(): String = DeviceCal.calendars(ctx)
    @JavascriptInterface fun calEvents(from: String, days: Int, ids: String): String = DeviceCal.events(ctx, from, days, ids)
    @JavascriptInterface fun calAdd(calId: String, json: String): Boolean = calId.toLongOrNull()?.let { DeviceCal.add(ctx, it, json) } ?: false
    @JavascriptInterface fun openCalEvent(eventId: String) {
        val id = eventId.toLongOrNull() ?: return
        main.post { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }
    }

    /* business cards: take or pick a photo, read it on the phone */
    @JavascriptInterface fun scanCard(source: String) { main.post { activity?.scanCard(source == "camera") } }

    @JavascriptInterface fun done() { main.post { onDone?.invoke() } }

    /** Concierge art (kept only on this device). Returns its size so the web app knows whether to resend. */
    @JavascriptInterface fun artVersion(): Int = Sprite.artFile(ctx).let { if (it.exists()) it.length().toInt() else -1 }
    @JavascriptInterface fun setConciergeArt(b64: String) {
        try { val bytes = Base64.decode(b64, Base64.DEFAULT); if (bytes.size in 1..6_000_000) Sprite.artFile(ctx).writeBytes(bytes) } catch (_: Exception) { }
    }
    @JavascriptInterface fun openWallpaper() { main.post { activity?.openWallpaperPicker() } }
    @JavascriptInterface fun pickWallpaperImage() { main.post { activity?.pickWallpaperImage() } }
    @JavascriptInterface fun overlayOn(): Boolean = OverlayService.enabled(ctx) && android.provider.Settings.canDrawOverlays(ctx)
    @JavascriptInterface fun setOverlay(on: Boolean): Boolean {
        if (on && !Sprite.hasArt(ctx)) return false
        var ok = false
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { ok = OverlayService.setEnabled(ctx, on); latch.countDown() }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }
    @JavascriptInterface fun openAod() { main.post { try { ctx.startActivity(Intent(ctx, AodActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } } }
    @JavascriptInterface fun openDreamSettings() { main.post { try { ctx.startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { try { ctx.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } } } }
    @JavascriptInterface fun nowOn(): Boolean = NowNotif.enabled(ctx)
    @JavascriptInterface fun setNow(on: Boolean) { main.post { NowNotif.setEnabled(ctx, on) } }
    @JavascriptInterface fun quietOn(): Boolean = Quiet.enabled(ctx)
    @JavascriptInterface fun setQuiet(on: Boolean): Boolean {
        var ok = false
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { ok = Quiet.setEnabled(ctx, on); latch.countDown() }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    @JavascriptInterface fun notifState(): String {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            return if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) "default" else "denied"
        }
        return "granted"
    }

    @JavascriptInterface fun requestNotifications() { main.post { activity?.askNotifications() } }

    @JavascriptInterface fun openExternal(url: String) {
        val u = Uri.parse(url)
        if (u.scheme != "https") return
        main.post {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    /** Saves a generated file (e.g. CSV export) into Downloads. */
    @JavascriptInterface fun saveFile(name: String, mime: String, b64: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, if (mime.startsWith("text/")) mime else "application/octet-stream")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            true
        } catch (_: Exception) { false }
    }
}
