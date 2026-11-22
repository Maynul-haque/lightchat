package bd.desertfox.lightchat

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Minimal androidx-free file provider over the app's attachments folder. */
class FilesProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        val root = context!!.filesDir
        val rel = uri.path?.trimStart('/') ?: ""
        if (rel.isEmpty()) return File(root, "nonexistent")
        val f = File(root, rel)
        return try {
            if (!f.canonicalPath.startsWith(root.canonicalPath)) File(root, "nonexistent") else f
        } catch (e: Exception) { File(root, "nonexistent") }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri)
        if (mode.contains('w')) {
            // v0.6.1: the camera app opens the pending photo for WRITE, and
            // the file does not exist yet — it is only a File object until
            // the camera writes it. Create it and open read/write; before
            // this, openFile returned null for a missing file and always
            // opened read-only, so the capture could never be saved.
            // fileFor() already confines paths under filesDir, so the
            // write mode is safe.
            return try {
                f.parentFile?.mkdirs()
                if (!f.exists()) f.createNewFile()
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE)
            } catch (e: Exception) { null }
        }
        if (!f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        Attachments.mimeFromName(fileFor(uri).name).ifEmpty { "application/octet-stream" }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                       args: Array<String>?, order: String?): Cursor? {
        val f = fileFor(uri)
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf(f.name, f.length()))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        args: Array<String>?): Int = 0
}
