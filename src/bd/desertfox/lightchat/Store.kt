package bd.desertfox.lightchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Store {
    val providers = ArrayList<Provider>()
    val chats = ArrayList<Conversation>() // newest first
    var settings = Settings("You are a helpful assistant.", "", "")
    val logLines = ArrayList<String>()

    private var appCtx: Context? = null
    private val saveExecutor = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private var saveScheduled = false
    private var lastSaveMs = 0L
    private const val SAVE_DEBOUNCE_MS = 2000L
    private const val LOG_CAP = 200
    private var seeded = false
    private var loaded = false
    // P0-2: set when a file failed to parse; saveNow() must never overwrite it.
    private var configLoadFailed = false
    private var chatsLoadFailed = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        appCtx = ctx.applicationContext
        Attachments.init(ctx)
        val cf = File(ctx.filesDir, "config.json")
        if (cf.exists()) {
            try {
                val j = JSONObject(cf.readText())
                try { settings = Settings.from(j.optJSONObject("settings") ?: JSONObject()) } catch (e: Exception) {}
                seeded = j.optBoolean("seeded", false)
                val pa = j.optJSONArray("providers")
                // P0-2: one bad provider entry must not kill all providers.
                if (pa != null) for (i in 0 until pa.length()) {
                    try { providers.add(Provider.from(pa.getJSONObject(i))) }
                    catch (e: Exception) { Store.log("skip corrupt provider entry " + i) }
                }
                backupFile(cf)
            } catch (e: Exception) {
                // Whole file unreadable: keep providers empty in memory, but
                // never let a later save overwrite the (possibly recoverable) file.
                configLoadFailed = true
                Store.log("config.json corrupt — kept as-is, not overwriting")
            }
        }
        val hf = File(ctx.filesDir, "chats.json")
        if (hf.exists()) {
            try {
                val a = JSONArray(hf.readText())
                // P0-2: one bad chat entry must not kill all chats.
                for (i in 0 until a.length()) {
                    try { chats.add(Conversation.from(a.getJSONObject(i))) }
                    catch (e: Exception) { Store.log("skip corrupt chat entry " + i) }
                }
                backupFile(hf)
            } catch (e: Exception) {
                chatsLoadFailed = true
                Store.log("chats.json corrupt — kept as-is, not overwriting")
            }
        }
        // Cleanup for the pre-0.1.3 reload bug: load() had no one-time guard, so
        // every screen open re-read the files and appended every provider/chat again.
        run {
            val seenIds = HashSet<String>()
            val seenKeys = HashSet<String>()
            val it = providers.iterator()
            while (it.hasNext()) {
                val pr = it.next()
                val key = pr.name + "|" + pr.baseUrl + "|" + pr.apiKey + "|" + pr.models.joinToString(",")
                if (!seenIds.add(pr.id) || !seenKeys.add(key)) it.remove()
            }
            val seenChats = HashSet<String>()
            val itc = chats.iterator()
            while (itc.hasNext()) if (!seenChats.add(itc.next().id)) itc.remove()
        }
        val la = cfgLogArray()
        synchronized(logLines) {
            logLines.clear()
            for (i in 0 until la.length()) logLines.add(la.optString(i))
        }
        installCrashHandler()
        if (!seeded) {
            seeded = true
            if (providers.isEmpty()) {
                val p = Provider(UUID.randomUUID().toString(), "LLM Router", "http://127.0.0.1:8790/v1", "x")
                providers.add(p)
            }
        }
        save()
    }

    fun save() {
        synchronized(lock) {
            if (saveScheduled) return
            val delay = (SAVE_DEBOUNCE_MS - (System.currentTimeMillis() - lastSaveMs)).coerceAtLeast(0L)
            saveScheduled = true
            saveExecutor.schedule({ saveNow() }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** Keep a copy of the last good file, so a corrupt file can be recovered. */
    private fun backupFile(f: File) {
        try {
            val bak = File(f.parentFile, f.name + ".bak")
            f.copyTo(bak, overwrite = true)
        } catch (e: Exception) {}
    }

    /** Atomic tmp-file + rename write of both files. Call from onPause. */
    fun saveNow() {
        val ctx: Context
        val cfgText: String?
        val chatsText: String?
        // P0-3: snapshot everything under the lock first. UI-thread mutations
        // never take this lock, so serialising the live lists could throw
        // ConcurrentModificationException on the save thread. The snapshot
        // (lists + messages + attachments) is fully detached from live data.
        synchronized(lock) {
            saveScheduled = false
            lastSaveMs = System.currentTimeMillis()
            ctx = appCtx ?: return
            val provSnap = ArrayList(providers)
            val chatSnap = chats.map { c ->
                Conversation(c.id, c.title, c.providerId, c.modelId, c.createdAt, c.updatedAt,
                    c.messages.map { m -> Msg(m.role, m.content, m.ts, m.tokens, ArrayList(m.atts)) }.toMutableList())
            }
            cfgText = if (configLoadFailed) null else {
                val cj = JSONObject()
                cj.put("providers", JSONArray(provSnap.map { it.toJson() }))
                cj.put("settings", settings.toJson())
                cj.put("seeded", seeded)
                synchronized(logLines) { cj.put("log", JSONArray(logLines)) }
                cj.toString()
            }
            chatsText = if (chatsLoadFailed) null else
                JSONArray(chatSnap.map { it.toJson() }).toString()
        }
        try {
            // P0-2: never overwrite a file whose load failed.
            if (cfgText != null) atomicWrite(File(ctx.filesDir, "config.json"), cfgText)
            if (chatsText != null) atomicWrite(File(ctx.filesDir, "chats.json"), chatsText)
        } catch (e: Exception) { }
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun cfgLogArray(): JSONArray {
        return try {
            val f = File(appCtx!!.filesDir, "config.json")
            if (f.exists()) JSONObject(f.readText()).optJSONArray("log") ?: JSONArray() else JSONArray()
        } catch (e: Exception) { JSONArray() }
    }

    private fun ts(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Append one diagnostic line. Persisted (debounced) with the config. */
    fun log(msg: String) {
        val line = ts() + " " + msg
        synchronized(logLines) {
            logLines.add(line)
            while (logLines.size > LOG_CAP) logLines.removeAt(0)
        }
        save()
    }

    /** Immediate file write for the crash handler (the process is dying). */
    fun logNow(msg: String) {
        val ctx = appCtx ?: return
        try {
            File(ctx.filesDir, "crash.log").appendText("\n==== " + ts() + " ====\n" + msg)
        } catch (e: Exception) {}
        synchronized(logLines) {
            logLines.add(ts() + " CRASH — full trace in crash.log")
            while (logLines.size > LOG_CAP) logLines.removeAt(0)
        }
    }

    fun crashLog(): String {
        val ctx = appCtx ?: return ""
        return try {
            val f = File(ctx.filesDir, "crash.log")
            if (f.exists()) {
                val t = f.readText()
                if (t.length > 8000) t.substring(t.length - 8000) else t
            } else ""
        } catch (e: Exception) { "" }
    }

    fun clearCrashLog() {
        val ctx = appCtx ?: return
        try { File(ctx.filesDir, "crash.log").delete() } catch (e: Exception) {}
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            logNow(android.util.Log.getStackTraceString(e))
            prev?.uncaughtException(t, e)
        }
    }

    fun provider(id: String): Provider? {
        for (p in providers) if (p.id == id) return p
        return null
    }

    fun defaultProvider(): Provider? {
        settings.defaultProviderId.let { if (it.isNotEmpty()) provider(it)?.let { p -> return p } }
        return providers.firstOrNull { it.enabled }
    }

    /** Resolved provider for a conversation: its own, else the app default. */
    fun effectiveProvider(c: Conversation): Provider? = provider(c.providerId) ?: defaultProvider()

    fun newConversation(): Conversation {
        val dp = defaultProvider()
        val c = Conversation(UUID.randomUUID().toString(), "", dp?.id ?: "", settings.defaultModel,
            System.currentTimeMillis(), System.currentTimeMillis())
        chats.add(0, c)
        save()
        return c
    }

    fun touch(c: Conversation) {
        c.updatedAt = System.currentTimeMillis()
        // keep newest first
        chats.remove(c)
        chats.add(0, c)
    }
}
