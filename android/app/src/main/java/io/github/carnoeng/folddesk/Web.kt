package io.github.carnoeng.folddesk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** Shared WebView setup: only the app's own pages load inside; everything else opens outside. */
object Web {
    val APP_URL: String = BuildConfig.APP_URL
    private val APP_ORIGIN: String = Uri.parse(APP_URL).let { "${it.scheme}://${it.host}" }
    private val APP_PATH: String = Uri.parse(APP_URL).path ?: "/"

    fun isAppUrl(u: Uri?): Boolean =
        u != null && "${u.scheme}://${u.host}" == APP_ORIGIN && (u.path ?: "/").startsWith(APP_PATH.trimEnd('/'))

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView, ctx: Context, bridge: Bridge) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "$userAgentString FoldDeskApp/${BuildConfig.VERSION_CODE}"
            // follow the phone's font size, within a range the layout is checked for
            textZoom = (ctx.resources.configuration.fontScale * 100).toInt().coerceIn(85, 120)
        }
        web.addJavascriptInterface(bridge, "FoldDeskNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (isAppUrl(u)) return false
                if (u.scheme == "https" || u.scheme == "mailto" || u.scheme == "tel") {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
                return true
            }
        }
    }
}
