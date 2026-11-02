package io.github.carnoeng.folddesk

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Receives "共有" from other apps (text, links, PDFs, images), keeps a private copy on this phone,
 * and hands it to the app, which asks what to do with it.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { receive(intent) } catch (_: Exception) { }
        startActivity(Intent(this, MainActivity::class.java).putExtra("route", "share")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @Suppress("DEPRECATION")
    private fun receive(i: Intent) {
        val uris = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let { uris.addAll(it) }
        }
        val dir = File(cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val files = JSONArray()
        uris.take(10).forEachIndexed { n, u ->
            var name = "file$n"; var size = 0L
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) } }
            if (size > 60_000_000) return@forEachIndexed
            val mime = contentResolver.getType(u) ?: i.type ?: ""
            val f = File(dir, "s$n")
            contentResolver.openInputStream(u)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } ?: return@forEachIndexed
            files.put(JSONObject().put("id", "s$n").put("name", name.take(120)).put("mime", mime).put("size", f.length()))
        }
        val o = JSONObject()
            .put("text", (i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: "").take(20_000))
            .put("subject", (i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "").take(300))
            .put("files", files).put("at", System.currentTimeMillis())
        getSharedPreferences("fd", MODE_PRIVATE).edit().putString("share", o.toString()).commit()
    }

    companion object {
        fun take(c: android.content.Context): String {
            val p = c.getSharedPreferences("fd", MODE_PRIVATE); val s = p.getString("share", "") ?: ""
            p.edit().remove("share").apply(); return s
        }
        fun file(c: android.content.Context, id: String): File? =
            if (Regex("^s\\d{1,2}$").matches(id)) File(File(c.cacheDir, "share"), id).takeIf { it.exists() } else null
        fun clear(c: android.content.Context) { File(c.cacheDir, "share").listFiles()?.forEach { it.delete() } }
    }
}
