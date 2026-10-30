package io.github.carnoeng.folddesk

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }
    private val pickBg = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.use { inp -> java.io.File(filesDir, "wall_bg.img").outputStream().use { inp.copyTo(it) } }
            web.evaluateJavascript("window.toast && 0", null)
        } catch (_: Exception) { }
    }
    fun pickWallpaperImage() { pickBg.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    fun openWallpaperPicker() {
        val i = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, android.content.ComponentName(this, ConciergeWallpaper::class.java))
        try { startActivity(i) } catch (_: Exception) { try { startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } catch (_: Exception) { } }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        web.evaluateJavascript("window.__fdNotif && window.__fdNotif(${if (ok) "true" else "false"})", null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        web = WebView(this)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        web.setBackgroundColor(if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt())
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsetsCompat.CONSUMED
        }
        Web.configure(web, this, Bridge(applicationContext, this))
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { pickFile.launch(p!!.createIntent()); true } catch (_: Exception) { fileCb = null; false }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__fdBack && window.__fdBack()) ? 1 : 0") { r ->
                    if (r != "1") { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(urlFor(intent))
        Scheduler.ensureWorker(applicationContext)
        Notifier.ensureChannel(applicationContext)
        if (OverlayService.enabled(this) && android.provider.Settings.canDrawOverlays(this) && !OverlayService.running)
            androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        web.loadUrl(urlFor(intent))
    }

    private fun urlFor(i: Intent?): String {
        val d = i?.data
        if (d != null && d.scheme == "folddesk" && d.host == "pair") {
            val payload = d.getQueryParameter("d") ?: ""
            if (Regex("^[A-Za-z0-9_-]{20,2000}$").matches(payload)) return Web.APP_URL + "#pair=" + payload
            val school = d.getQueryParameter("s") ?: ""
            if (Regex("^[A-Za-z0-9_-]{20,2000}$").matches(school)) return Web.APP_URL + "#school=" + school
        }
        val route = i?.getStringExtra("route") ?: "today"
        return Web.APP_URL + "#" + (if (route in ROUTES) route else "today")
    }

    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        else web.evaluateJavascript("window.__fdNotif && window.__fdNotif(true)", null)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onResume() {
        super.onResume(); foreground = true; web.onResume()
        // widgets may have changed data through the invisible copy of the app
        web.evaluateJavascript("window.__fdResume && window.__fdResume()", null)
    }
    override fun onPause() { foreground = false; web.onPause(); super.onPause() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }

    companion object {
        @Volatile var foreground = false
        val ROUTES = setOf("today", "timetable", "tasks", "events", "people", "memo", "ask", "prep", "settings", "follow")
    }
}
