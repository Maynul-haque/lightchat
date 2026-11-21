package bd.desertfox.lightchat

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A provider-side failure with an HTTP status. */
class HttpErr(val status: Int, msg: String) : Exception(msg)

class TestResult(val ok: Boolean, val detail: String)

/** Set from the UI thread, read by the worker thread. Cancels the live call. */
class StopFlag {
    val stop = AtomicBoolean(false)
    @Volatile var call: Call? = null
    fun stop() {
        stop.set(true)
        try { call?.cancel() } catch (e: Exception) {}
    }
}

object ChatClient {
    private const val MAX_BODY = 8 * 1024 * 1024
    private const val MAX_MODELS_BODY = 1024 * 1024
    private const val CONNECT_MS = 15000L
    private const val FIRST_CHUNK_MS = 30000L
    private const val READ_MS = 60000L
    /** Honest UA: a fake Chrome UA on top of OkHttp's TLS stack has the wrong
     *  fingerprint for Chrome, so Cloudflare-fronted gateways flag it as a bot
     *  (HTTP 403 "Just a moment..."). RikkaHub uses an honest app UA and its
     *  /models fetch works on the same gateways. */
    private const val USER_AGENT = "LightChat/0.4.0"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val baseHttp = OkHttpClient.Builder()
        .connectTimeout(CONNECT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private fun http(readMs: Long): OkHttpClient =
        if (readMs == READ_MS) baseHttp
        else baseHttp.newBuilder().readTimeout(readMs, TimeUnit.MILLISECONDS).build()

    private fun baseUrl(p: Provider): String {
        var b = p.baseUrl.trim()
        while (b.endsWith("/")) b = b.substring(0, b.length - 1)
        return b
    }

    private fun key(p: Provider): String = if (p.apiKey.isBlank()) "x" else p.apiKey

    private fun readBounded(ins: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) throw RuntimeException("response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun bodyText(r: Response, cap: Int): String =
        r.body?.byteStream()?.use { String(readBounded(it, cap), Charsets.UTF_8) } ?: ""

    private fun providerMsg(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val j = JSONObject(body)
            val e = j.optJSONObject("error")
            if (e != null) {
                val m = e.optString("message")
                if (m.isNotEmpty()) m else e.optString("code", body.take(200))
            } else body.take(200)
        } catch (e: Exception) { body.take(200) }
    }

    /** True when a body is a Cloudflare bot-challenge page, not a provider error. */
    private fun isCloudflareChallenge(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("just a moment") || b.contains("cf-chl") || b.contains("cloudflare")
    }

    private fun req(p: Provider, path: String, accept: String, bytes: ByteArray?): Request {
        val b = Request.Builder()
            .url(baseUrl(p) + path)
            .header("Authorization", "Bearer " + key(p))
            .header("Accept", accept)
            .header("User-Agent", USER_AGENT)
        // Per-provider custom headers (e.g. HTTP-Referer / X-Title for OpenRouter).
        // They must never override Authorization or the LightChat User-Agent.
        // Only header names are logged, never values (they may hold secrets).
        val names = ArrayList<String>()
        for ((name, value) in p.customHeaders) {
            val n = name.trim()
            if (n.isEmpty()) continue
            val lower = n.lowercase()
            if (lower == "authorization" || lower == "user-agent") continue
            if (value.contains('\n') || value.contains('\r')) continue
            try {
                b.header(n, value)
                names.add(n)
            } catch (e: Exception) {
                // Invalid header name/value — skip it, never fail the request.
            }
        }
        if (names.isNotEmpty()) Store.log("custom headers (" + p.name + "): " + names.joinToString(", "))
        if (bytes != null) b.post(bytes.toRequestBody(JSON_TYPE)) else b.get()
        return b.build()
    }

    private fun historyJson(system: String, history: List<Msg>): JSONArray {
        val arr = JSONArray()
        if (system.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        for (m in history) if (m.role == "user" || m.role == "assistant") {
            if (m.role == "user" && m.atts.isNotEmpty()) {
                arr.put(JSONObject().put("role", m.role).put("content", Attachments.buildContent(m)))
            } else if (m.content.isNotBlank()) {
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            }
        }
        return arr
    }

    private fun netMsg(e: Exception, sawFirstChunk: Boolean, firstSec: Int = 30): String = when {
        e is SocketTimeoutException && !sawFirstChunk ->
            "No response in " + firstSec + " s — the model may be hanging, try another one"
        e.message != null && e.message!!.isNotEmpty() -> e.message!!
        else -> "Invalid response from provider"
    }

    /**
     * Blocking streaming chat. Call from a worker thread. One non-streaming
     * retry when the streaming request itself errors. A 200 whose payload
     * carries an error object is a failure, never a reply.
     */
    fun streamChat(p: Provider, model: String, system: String, history: List<Msg>,
                   flag: StopFlag, onDelta: (String) -> Unit,
                   onDone: (String, Int) -> Unit, onError: (String) -> Unit) {
        var sawFirstChunk = false
        var resp: Response? = null
        var firstMs = FIRST_CHUNK_MS
        try {
            val body = JSONObject()
                .put("model", model)
                .put("messages", historyJson(system, history))
                .put("stream", true)
                .put("temperature", 0.7)
            // Providers often take longer to start replying when a file must be
            // processed first; give those requests a wider first-chunk window.
            val lastUser = history.lastOrNull { it.role == "user" }
            firstMs = if (lastUser != null && lastUser.atts.isNotEmpty()) 90000L else FIRST_CHUNK_MS
            val call = http(firstMs).newCall(
                req(p, "/chat/completions", "text/event-stream",
                    body.toString().toByteArray(Charsets.UTF_8)))
            flag.call = call
            val r = call.execute()
            resp = r
            val code = r.code
            if (code in 200..299) {
                val sb = StringBuilder()
                var tokens = -1
                var failed = false
                val source = r.body!!.source()
                loop@ while (true) {
                    if (flag.stop.get()) break
                    val line = try { source.readUtf8Line() } catch (e: Exception) {
                        if (flag.stop.get()) break@loop else throw e
                    }
                    if (line == null) break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    if (!sawFirstChunk) {
                        sawFirstChunk = true
                        try { source.timeout().timeout(READ_MS, TimeUnit.MILLISECONDS) } catch (e: Exception) {}
                    }
                    val j = try { JSONObject(payload) } catch (e: Exception) { continue }
                    val err = j.optJSONObject("error")
                    if (err != null) {
                        failed = true
                        val m = err.optString("message").ifEmpty { err.optString("code", "error") }
                        Store.log("chat 200-with-error: " + m)
                        onError(m)
                        return
                    }
                    val choices = j.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val delta = choices.optJSONObject(0)?.optJSONObject("delta")
                        val piece = delta?.optString("content") ?: ""
                        if (piece.isNotEmpty()) { sb.append(piece); onDelta(piece) }
                    }
                    val usage = j.optJSONObject("usage")
                    if (usage != null) {
                        val t = usage.optInt("total_tokens", -1)
                        if (t > 0) tokens = t
                    }
                }
                if (failed) return
                if (flag.stop.get()) { onDone(sb.toString(), tokens); return }
                if (sb.isBlank()) { onError("Invalid response from provider"); return }
                onDone(sb.toString(), tokens)
            } else {
                Store.log("chat HTTP " + code + " — trying non-streaming fallback")
                try {
                    val r2 = nonStreaming(p, model, system, history)
                    onDone(r2.first, r2.second)
                } catch (he: HttpErr) {
                    onError(he.status.toString() + " — " + (he.message ?: "error"))
                } catch (e: Exception) {
                    onError(netMsg(e, true))
                }
            }
        } catch (e: Exception) {
            if (flag.stop.get()) onDone("", -1)
            else onError(netMsg(e, sawFirstChunk, (firstMs / 1000).toInt()))
        } finally {
            try { resp?.close() } catch (e: Exception) {}
        }
    }

    /** Non-streaming chat. Throws HttpErr on any failure (200-with-error included). */
    private fun nonStreaming(p: Provider, model: String, system: String, history: List<Msg>): Pair<String, Int> {
        val body = JSONObject()
            .put("model", model)
            .put("messages", historyJson(system, history))
            .put("stream", false)
            .put("temperature", 0.7)
        val r = http(READ_MS).newCall(
            req(p, "/chat/completions", "application/json",
                body.toString().toByteArray(Charsets.UTF_8))).execute()
        r.use { resp ->
            val code = resp.code
            if (code in 200..299) {
                val j = JSONObject(bodyText(resp, MAX_BODY))
                val err = j.optJSONObject("error")
                if (err != null) throw HttpErr(200, err.optString("message").ifEmpty { "provider error" })
                val choices = j.optJSONArray("choices")
                if (choices == null || choices.length() == 0) throw HttpErr(200, "empty reply")
                val content = choices.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
                if (content.isEmpty()) throw HttpErr(200, "empty reply")
                var tokens = -1
                val usage = j.optJSONObject("usage")
                if (usage != null) {
                    val t = usage.optInt("total_tokens", -1)
                    if (t > 0) tokens = t
                }
                return Pair(content, tokens)
            }
            throw HttpErr(code, providerMsg(bodyText(resp, MAX_BODY)).ifEmpty { "HTTP " + code })
        }
    }

    /** Strict probe: a real non-empty reply passes; 429 passes (proves entitlement). */
    fun testProvider(p: Provider, model: String): TestResult {
        try {
            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                .put("max_tokens", 1)
                .put("stream", false)
            val r = http(READ_MS).newCall(
                req(p, "/chat/completions", "application/json",
                    body.toString().toByteArray(Charsets.UTF_8))).execute()
            r.use { resp ->
                val code = resp.code
                if (code == 429) return TestResult(true, "rate limited — the key works")
                if (code in 200..299) {
                    val j = JSONObject(bodyText(resp, MAX_BODY))
                    val err = j.optJSONObject("error")
                    if (err != null) return TestResult(false, err.optString("message").ifEmpty { "provider error" })
                    val content = j.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content") ?: ""
                    return if (content.isNotEmpty()) TestResult(true, "ok")
                    else TestResult(false, "empty reply")
                }
                return TestResult(false, "HTTP " + code + " — " + providerMsg(bodyText(resp, MAX_BODY)))
            }
        } catch (e: Exception) {
            return TestResult(false, netMsg(e, false))
        }
    }

    /** GET /models. An empty list never wipes the stored one (throws instead). */
    fun fetchModels(p: Provider): List<String> {
        val r = http(30000).newCall(req(p, "/models", "application/json", null)).execute()
        r.use { resp ->
            val code = resp.code
            if (code != 200) {
                val body = bodyText(resp, MAX_MODELS_BODY)
                if (code == 403 && isCloudflareChallenge(body)) {
                    throw RuntimeException("Cloudflare blocked (HTTP 403) — add the model id manually")
                }
                throw RuntimeException("HTTP " + code + " — " + providerMsg(body))
            }
            val j = JSONObject(bodyText(resp, MAX_MODELS_BODY))
            val data = j.optJSONArray("data") ?: throw RuntimeException("no data[] in response")
            val out = ArrayList<String>()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id") ?: continue
                if (id.isNotEmpty()) out.add(id)
            }
            if (out.isEmpty()) throw RuntimeException("no models in response — the saved list was kept")
            return out
        }
    }
}
