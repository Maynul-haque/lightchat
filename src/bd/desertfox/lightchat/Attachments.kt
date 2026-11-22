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
    /** P0-5: refuse imports over 50 MB instead of reading them into RAM. */
    const val MAX_IMPORT_BYTES = 50L * 1024 * 1024

    // P1-3: cache extracted text and base64 per Att.id so a PDF/image is
    // not re-read, re-extracted or re-encoded on every send.
    private val textCache = HashMap<String, String?>()
    private val b64Cache = HashMap<String, String>()

    private fun readCapped(ins: java.io.InputStream, cap: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

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
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
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
            // P0-5: refuse oversized files before reading them into RAM.
            if (size > MAX_IMPORT_BYTES) {
                Store.log("attach too large: " + name + " " + sizeLabel(size) + " (limit 50 MB)")
                return null
            }
            val bytes = cr.openInputStream(uri)?.use { readCapped(it, MAX_IMPORT_BYTES) } ?: return null
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
            // PDF/DOCX carry extractable text (see DocText); other non-text
            // files stay "binary" and are never sent as raw bytes.
            val ext = name.substringAfterLast('.', "").lowercase()
            val kind = when {
                isTextLike(mime, name) -> "text"
                ext == "pdf" -> "pdf"
                ext == "docx" -> "docx"
                else -> "binary"
            }
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

    /** Append a PDF/DOCX attachment's extracted text, or a failure note. */
    private fun appendExtracted(sb: StringBuilder, a: Att) {
        val txt = extractCached(a)
        if (sb.isNotEmpty()) sb.append("\n\n")
        if (txt.isNullOrBlank()) {
            sb.append("[Attached file: ").append(a.name).append(", ")
                .append(sizeLabel(a.size)).append(" — text could not be extracted]")
            Store.log("attach extract failed: " + a.name)
        } else {
            sb.append("[Attached file: ").append(a.name).append("]\n").append(txt)
            if (txt.length >= TEXT_CAP)
                sb.append("\n[... file truncated at ").append(TEXT_CAP / 1000).append(" KB]")
        }
    }

    /** Extracted text for a PDF/DOCX attachment, cached per Att.id (P1-3). */
    private fun extractCached(a: Att): String? {
        if (textCache.containsKey(a.id)) return textCache[a.id]
        val f = File(homeDir(), a.path)
        val bytes = try {
            if (f.exists()) f.readBytes() else null
        } catch (e: Exception) {
            null
        }
        val txt = if (bytes != null) DocText.extract(a.name, bytes) else null
        textCache[a.id] = txt
        return txt
    }

    /** Base64 of an image attachment file, cached per Att.id (P1-3). */
    private fun b64Cached(a: Att): String? {
        b64Cache[a.id]?.let { return it }
        return try {
            val f = File(homeDir(), a.path)
            val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            b64Cache[a.id] = b64
            b64
        } catch (e: Exception) {
            Store.log("attach read failed: " + a.name)
            null
        }
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
                // Attachments saved by v0.3.3 as "binary" may still be a PDF/DOCX:
                // try extraction for those, otherwise say the content was not sent.
                val ext = a.name.substringAfterLast('.', "").lowercase()
                if (ext == "pdf" || ext == "docx") appendExtracted(sb, a)
                else {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append("[Attached file: ").append(a.name).append(", ")
                        .append(sizeLabel(a.size)).append(" — binary content not sent]")
                }
            }
            "pdf", "docx" -> appendExtracted(sb, a)
        }
        val arr = JSONArray()
        arr.put(JSONObject().put("type", "text").put("text", sb.toString()))
        for (a in m.atts) if (a.kind == "image") {
            val b64 = b64Cached(a) ?: continue
            arr.put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:" + a.mime + ";base64," + b64)))
        }
        return arr
    }

    // P1-1: thumbnail cache — thumbs were decoded from disk on the UI thread
    // on every repaint.
    private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(16)

    /** Small bitmap for bubble/chip thumbnails; null when the file is missing. */
    fun thumb(path: String, px: Int): android.graphics.Bitmap? {
        val key = path + "@" + px
        try { thumbCache.get(key)?.let { return it } } catch (e: Exception) {}
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
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
            if (bmp != null) try { thumbCache.put(key, bmp) } catch (e: Exception) {}
            bmp
        } catch (e: Exception) { null }
    }

    fun deleteAtt(a: Att) {
        textCache.remove(a.id)
        b64Cache.remove(a.id)
        try { File(homeDir(), a.path).delete() } catch (e: Exception) {}
    }

    fun deleteAtts(list: List<Att>) {
        for (a in list) deleteAtt(a)
    }
}
