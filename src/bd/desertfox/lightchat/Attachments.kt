package bd.desertfox.lightchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

object Attachments {
    const val MAX_DIM = 1568
    const val JPEG_Q = 85
    const val TEXT_CAP = 100_000

    private var appCtx: Context? = null

    fun init(c: Context) {
        appCtx = c.applicationContext
        dir(appCtx!!)
    }

    fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "attachments")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun homeDir(): File = dir(appCtx ?: throw IllegalStateException("no ctx"))

    fun extOf(name: String): String {
        val e = name.substringAfterLast('.', "")
        return if (e.isEmpty()) "" else "." + e
    }

    fun stripExt(name: String): String {
        val e = name.substringAfterLast('.', "")
        return if (e.isEmpty()) name else name.substring(0, name.length - e.length - 1)
    }

    fun mimeFromName(name: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        return when (e) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "txt", "log" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "csv", "tsv" -> "text/csv"
            "html", "htm" -> "text/html"
            "xml" -> "text/xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> ""
        }
    }

    fun isTextLike(mime: String, name: String): Boolean {
        if (mime.startsWith("text/") || mime == "application/json") return true
        val e = name.substringAfterLast('.', "").lowercase()
        return e in setOf("txt", "md", "log", "json", "csv", "tsv", "xml", "html", "htm",
            "kt", "java", "py", "js", "ts", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb",
            "php", "sh", "bat", "ps1", "yml", "yaml", "toml", "ini", "conf", "properties",
            "gradle", "sql", "r", "swift", "dart", "lua", "srt", "vtt")
    }

    fun sizeLabel(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " B"
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0)
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Import a picked content Uri into the attachments folder. */
    fun importUri(ctx: Context, uri: Uri): Att? {
        try {
            val cr = ctx.contentResolver
            var name = "file"
            var size = -1L
            var mime = cr.getType(uri) ?: ""
            try {
                cr.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) c.getString(ni)?.let { if (it.isNotBlank()) name = it }
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (e: Exception) {}
            if (mime.isBlank()) mime = mimeFromName(name)
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (size < 0) size = bytes.size.toLong()
            val id = UUID.randomUUID().toString()
            if (mime.startsWith("image/")) {
                val isJpeg = mime == "image/jpeg" || mime == "image/jpg"
                if (isJpeg || bytes.size > 3_000_000) {
                    val f = File(dir(ctx), id + ".jpg")
                    if (!downscaleBytesToJpeg(bytes, f)) {
                        Store.log("attach decode failed: " + name)
                        return null
                    }
                    return Att(id, stripExt(name) + ".jpg", "image/jpeg", f.name, f.length(), "image")
                }
                val f = File(dir(ctx), id + extOf(name))
                f.writeBytes(bytes)
                val m = if (mime.isNotBlank()) mime else "image/*"
                return Att(id, name, m, f.name, f.length(), "image")
            }
            val f = File(dir(ctx), id + extOf(name))
            f.writeBytes(bytes)
            val kind = if (isTextLike(mime, name)) "text" else "binary"
            val m = if (mime.isNotBlank()) mime else "application/octet-stream"
            Store.log("attach ok: " + name + " " + sizeLabel(f.length()) + " [" + kind + "]")
            return Att(id, name, m, f.name, f.length(), kind)
        } catch (e: Exception) {
            Store.log("attach import failed: " + e.message)
            return null
        }
    }

    fun newCameraFile(ctx: Context): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        return Pair(id, File(dir(ctx), id + ".jpg"))
    }

    fun registerCamera(ctx: Context, id: String, f: File): Att {
        try {
            val bytes = f.readBytes()
            downscaleBytesToJpeg(bytes, f)
        } catch (e: Exception) {
            Store.log("camera normalize failed: " + e.message)
        }
        return Att(id, "photo_" + System.currentTimeMillis() + ".jpg", "image/jpeg",
            f.name, f.length(), "image")
    }

    private fun sampleFor(w: Int, h: Int, maxDim: Int): Int {
        var s = 1
        val m = Math.max(w, h)
        while (m / (s * 2) >= maxDim) s *= 2
        return s
    }

    private fun fit(bmp: Bitmap, maxDim: Int): Bitmap {
        val m = Math.max(bmp.width, bmp.height)
        if (m <= maxDim) return bmp
        val scale = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun exifRotate(bytes: ByteArray, bmp: Bitmap): Bitmap {
        return try {
            val ex = ExifInterface(ByteArrayInputStream(bytes))
            val o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val deg = when (o) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) bmp
            else {
                val m = Matrix()
                m.postRotate(deg)
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
        } catch (e: Exception) { bmp }
    }

    fun downscaleBytesToJpeg(bytes: ByteArray, out: File, maxDim: Int = MAX_DIM,
                             q: Int = JPEG_Q): Boolean {
        return try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return false
            opts.inSampleSize = sampleFor(opts.outWidth, opts.outHeight, maxDim)
            opts.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
            val rotated = exifRotate(bytes, bmp)
            val target = fit(rotated, maxDim)
            FileOutputStream(out).use { target.compress(Bitmap.CompressFormat.JPEG, q, it) }
            if (target != bmp) bmp.recycle()
            true
        } catch (e: Exception) {
            Store.log("jpeg encode failed: " + e.message)
            false
        }
    }

    fun readTextCap(f: File, cap: Int = TEXT_CAP): String {
        return try {
            if (!f.exists()) return ""
            val n = minOf(f.length(), cap.toLong()).toInt()
            val bytes = ByteArray(n)
            FileInputStream(f).use { val rd = it.read(bytes, 0, n); if (rd < n) return String(bytes, 0, Math.max(rd, 0), Charsets.UTF_8) }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    /** OpenAI-compatible content for a user message that carries attachments. */
    fun buildContent(m: Msg): JSONArray {
        val sb = StringBuilder()
        if (m.content.isNotBlank()) sb.append(m.content)
        for (a in m.atts) when (a.kind) {
            "text" -> {
                val f = File(homeDir(), a.path)
                val txt = readTextCap(f)
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append("[Attached file: ").append(a.name).append("]\n").append(txt)
                if (f.length() > TEXT_CAP)
                    sb.append("\n[... file truncated at ").append(TEXT_CAP / 1000).append(" KB]")
            }
            "binary" -> {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append("[Attached file: ").append(a.name).append(", ")
                    .append(sizeLabel(a.size)).append(" — binary content not sent]")
            }
        }
        val arr = JSONArray()
        arr.put(JSONObject().put("type", "text").put("text", sb.toString()))
        for (a in m.atts) if (a.kind == "image") {
            try {
                val f = File(homeDir(), a.path)
                val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                arr.put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:" + a.mime + ";base64," + b64)))
            } catch (e: Exception) {
                Store.log("attach read failed: " + a.name)
            }
        }
        return arr
    }

    /** Small bitmap for bubble/chip thumbnails; null when the file is missing. */
    fun thumb(path: String, px: Int): android.graphics.Bitmap? {
        return try {
            val f = File(homeDir(), path)
            if (!f.exists()) return null
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, opts)
            if (opts.outWidth <= 0) return null
            var s = 1
            while (Math.max(opts.outWidth, opts.outHeight) / (s * 2) >= px) s *= 2
            opts.inSampleSize = s
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (e: Exception) { null }
    }

    fun deleteAtt(a: Att) {
        try { File(homeDir(), a.path).delete() } catch (e: Exception) {}
    }

    fun deleteAtts(list: List<Att>) {
        for (a in list) deleteAtt(a)
    }
}
