package io.github.carnoeng.folddesk

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject

/** Reads the text on a business card photo on the phone (no internet, nothing uploaded). */
object CardScan {
    fun read(c: Context, uri: Uri, done: (String) -> Unit) {
        val img = try { InputImage.fromFilePath(c, uri) } catch (e: Exception) { done(JSONObject().put("ok", false).toString()); return }
        val rec = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        rec.process(img)
            .addOnSuccessListener { r ->
                val lines = JSONArray()
                r.textBlocks.forEach { b -> b.lines.forEach { l ->
                    val box = l.boundingBox
                    lines.put(JSONObject().put("t", l.text).put("h", box?.height() ?: 0).put("y", box?.top ?: 0).put("x", box?.left ?: 0))
                } }
                done(JSONObject().put("ok", true).put("text", r.text.take(4000)).put("lines", lines).put("w", img.width).put("hgt", img.height).toString())
                rec.close()
            }
            .addOnFailureListener { done(JSONObject().put("ok", false).toString()); rec.close() }
    }
}
