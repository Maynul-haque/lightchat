package bd.desertfox.lightchat

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny crash-proof trace log (v0.5.1 hotfix).
 *
 *  Appends short timestamped lines to debug.log in the app's files dir and
 *  flushes on every write (appendText opens, writes and closes), so the log
 *  survives a crash and the next launch can show what the app was doing.
 *
 *  Privacy: only event names, counts, durations and error messages are
 *  logged — never API keys, request bodies, message content or header values.
 *
 *  The file is capped at ~200 KB (oldest half is dropped when it grows past
 *  the cap) so it never grows without bound. */
object TraceLog {
    private const val FILE = "debug.log"
    private const val CRASH_FILE = "crash.log"
    private const val MAX_BYTES = 200L * 1024
    private const val READ_CAP = 12000
    private var appCtx: Context? = null
    private val lock = Any()
    private var handlerInstalled = false

    fun init(c: Context) {
        if (appCtx == null) appCtx = c.applicationContext
    }

    private fun ts(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    /** One timestamped line. Never throws. */
    fun log(tag: String, msg: String) {
        val line = ts() + " " + tag + " " + msg
        synchronized(lock) {
            try {
                val ctx = appCtx ?: return
                val f = File(ctx.filesDir, FILE)
                if (f.exists() && f.length() > MAX_BYTES) {
                    val t = f.readText()
                    f.writeText(t.substring((t.length / 2).coerceAtLeast(0)))
                }
                f.appendText(line + "\n")
            } catch (e: Exception) {
            }
        }
    }

    /** Last ~12 KB of the trace, for the Logs screen. Never throws. */
    fun readTail(): String {
        return try {
            val ctx = appCtx ?: return ""
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return ""
            val t = f.readText()
            if (t.length > READ_CAP) t.substring(t.length - READ_CAP) else t
        } catch (e: Exception) {
            ""
        }
    }

    fun clear() {
        try {
            val ctx = appCtx ?: return
            File(ctx.filesDir, FILE).delete()
        } catch (e: Exception) {
        }
    }

    /** Install the crash handler. Idempotent: safe to call from every
     *  Activity. MUST be called first in MainActivity.onCreate, before
     *  Store.load, so a crash during load is recorded — in v0.5.0 the
     *  handler was only installed at the end of Store.load(), so a crash
     *  during load left no crash.log at all.
     *
     *  The stacktrace is written to debug.log (and crash.log, as before),
     *  then the previous handler runs so the system crash dialog still shows. */
    fun installCrashHandler() {
        synchronized(lock) {
            if (handlerInstalled) return
            handlerInstalled = true
        }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val trace = Log.getStackTraceString(e)
                log("CRASH", "thread=" + t.name + " " +
                    e.javaClass.simpleName + ": " + (e.message ?: ""))
                // Full stacktrace, on its own lines, flushed immediately.
                synchronized(lock) {
                    try {
                        val ctx = appCtx
                        if (ctx != null) {
                            File(ctx.filesDir, FILE).appendText(trace + "\n")
                            File(ctx.filesDir, CRASH_FILE)
                                .appendText("\n==== " + ts() + " ====\n" + trace)
                        }
                    } catch (ignored: Exception) {
                    }
                }
            } catch (ignored: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
