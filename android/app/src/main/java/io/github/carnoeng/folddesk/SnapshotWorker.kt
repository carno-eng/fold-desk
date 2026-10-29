package io.github.carnoeng.folddesk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Every ~2 hours: load the app invisibly so it syncs and recomputes the widget snapshot. */
class SnapshotWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (MainActivity.foreground) return Result.success()
        val main = Handler(Looper.getMainLooper())
        withTimeoutOrNull(75_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                var web: WebView? = null
                main.post {
                    val w = WebView(applicationContext)
                    web = w
                    val bridge = Bridge(applicationContext, null) {
                        if (cont.isActive) cont.resumeWith(kotlin.Result.success(true))
                        main.post { w.destroy() }
                    }
                    Web.configure(w, applicationContext, bridge)
                    w.loadUrl(Web.APP_URL + "#snapshot")
                }
                cont.invokeOnCancellation { main.post { web?.destroy() } }
            }
        }
        main.post { Widgets.updateAll(applicationContext); Scheduler.schedule(applicationContext) }
        return Result.success()
    }
}
