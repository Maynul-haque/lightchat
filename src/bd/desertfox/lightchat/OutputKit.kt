package bd.desertfox.lightchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Model-output helpers: remote image loading with a disk cache, and file downloads. */
object OutputKit {

    // ---- images ----

    fun cacheDir(ctx: Context): File {
        val d = File(ctx.filesDir, "imgcache")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun downloadsDir(ctx: Context): File {
        val d = File(ctx.filesDir, "downloads")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    fun extOf(url: String): String {
        val clean = url.substringBefore('?').substringAfterLast('/')
        val e = clean.substringAfterLast('.', "")
        return if (e.length <= 5 && e.isNotEmpty() && e.matches(Regex("[a-zA-Z0-9]+"))) "." + e.lowercase() else ".png"
    }

    /** Decode a data: URL to a bitmap, or null. */
    fun fromDataUrl(u: String): Bitmap? {
        return try {
            if (!u.startsWith("data:")) return null
            val comma = u.indexOf(',')
            if (comma < 0) return null
            val meta = u.substring(5, comma)
            val data = u.substring(comma + 1)
            val bytes = if (meta.contains("base64")) Base64.decode(data, Base64.DEFAULT)
            else data.toByteArray(Charsets.UTF_8)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }

    fun cacheFile(ctx: Context, url: String): File = File(cacheDir(ctx), md5(url) + extOf(url))

    /** Load a remote image through the disk cache, decoded to about maxPx. */
    fun load(ctx: Context, url: String, maxPx: Int, cb: (Bitmap?) -> Unit) {
        val f = cacheFile(ctx, url)
        fun deliver(b: Bitmap?) { cb(b) }
        if (f.exists() && f.length() > 0) {
            val b = decodeFile(f, maxPx)
            if (b != null) { deliver(b); return }
            f.delete()
        }
        Thread {
            var ok = false
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.instanceFollowRedirects = true
                if (c.responseCode in 200..299) {
                    val bytes = c.inputStream.use { readCap(it, 10_000_000) }
                    if (bytes != null) {
                        val opts = BitmapFactory.Options()
                        opts.inJustDecodeBounds = true
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (opts.outWidth > 0) {
                            var s = 1
                            while (Math.max(opts.outWidth, opts.outHeight) / (s * 2) >= maxPx) s *= 2
                            opts.inSampleSize = s
                            opts.inJustDecodeBounds = false
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                            if (bmp != null) {
                                try { FileOutputStream(f).use { bmp.compress(jpegFmt(f), 90, it) } } catch (e: Exception) {}
                                ok = true
                                android.os.Handler(ctx.mainLooper).post { deliver(bmp) }
                                return@Thread
                            }
                        }
                    }
                }
                try { c.disconnect() } catch (e: Exception) {}
            } catch (e: Exception) {}
            if (!ok) android.os.Handler(ctx.mainLooper).post { deliver(null) }
        }.start()
    }

    private fun jpegFmt(f: File) = Bitmap.CompressFormat.PNG

    private fun decodeFile(f: File, maxPx: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, opts)
            if (opts.outWidth <= 0) return null
            var s = 1
            while (Math.max(opts.outWidth, opts.outHeight) / (s * 2) >= maxPx) s *= 2
            opts.inSampleSize = s
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (e: Exception) { null }
    }

    private fun readCap(ins: InputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ---- files ----

    fun fileExt(url: String): String? {
        val clean = url.substringBefore('?').substringAfterLast('/')
        val e = clean.substringAfterLast('.', "")
        if (e.isEmpty() || e.length > 5) return null
        if (!e.matches(Regex("[a-zA-Z0-9]+"))) return null
        return e.lowercase()
    }

    fun isFileUrl(url: String): Boolean {
        val e = fileExt(url) ?: return false
        return e in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "rar",
            "7z", "gz", "tar", "csv", "tsv", "txt", "md", "json", "xml", "apk", "mp3", "mp4",
            "wav", "ogg", "srt", "vtt", "kt", "java", "py", "js", "ts", "html", "epub", "ics")
    }

    fun nameFromUrl(url: String): String {
        var n = url.substringBefore('?').substringAfterLast('/')
        if (n.isBlank()) n = "file"
        return n
    }

    /** Download to the app's downloads folder; cb gets the file or null. */
    fun download(ctx: Context, url: String, cb: (File?) -> Unit) {
        Thread {
            var done: File? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.instanceFollowRedirects = true
                if (c.responseCode in 200..299) {
                    val cd = c.getHeaderField("Content-Disposition")
                    var name = nameFromUrl(url)
                    if (cd != null) {
                        val m = Regex("filename\\*?=\"?([^\";]+)").find(cd)
                        if (m != null) name = m.groupValues[1].trim()
                    }
                    if (!name.contains('.')) name += "." + (fileExt(url) ?: "bin")
                    val f = File(downloadsDir(ctx), System.currentTimeMillis().toString() + "_" + name)
                    c.inputStream.use { ins -> FileOutputStream(f).use { ins.copyTo(it) } }
                    done = f
                    Store.log("download ok: " + name + " " + Attachments.sizeLabel(f.length()))
                } else {
                    Store.log("download HTTP " + c.responseCode)
                }
                try { c.disconnect() } catch (e: Exception) {}
            } catch (e: Exception) {
                Store.log("download failed: " + e.message)
            }
            val final = done
            android.os.Handler(ctx.mainLooper).post { cb(final) }
        }.start()
    }
}
