package bd.desertfox.lightchat

import org.json.JSONArray
import org.json.JSONObject

/** Single source of truth for the app version.
 *  build.sh must be kept in sync (aapt2 link --version-code/--version-name);
 *  ChatClient.USER_AGENT and the Settings About line read this constant. */
object BuildInfo {
    const val VERSION_NAME = "0.5.0"
    const val VERSION_CODE = 12
}

class Provider(
    val id: String,
    var name: String,
    var baseUrl: String,
    var apiKey: String,
    val models: MutableList<String> = ArrayList(),
    var enabled: Boolean = true,
    /** ids the user whitelisted; empty set = every model allowed */
    val allowed: MutableSet<String> = HashSet(),
    /** extra headers sent on every request for this provider, e.g. HTTP-Referer / X-Title */
    val customHeaders: MutableMap<String, String> = LinkedHashMap()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("baseUrl", baseUrl)
        o.put("apiKey", apiKey)
        o.put("enabled", enabled)
        o.put("models", JSONArray(models))
        o.put("allowed", JSONArray(allowed.toList()))
        o.put("customHeaders", JSONObject(customHeaders))
        return o
    }

    companion object {
        fun from(j: JSONObject): Provider {
            val l = ArrayList<String>()
            val a = j.optJSONArray("models")
            if (a != null) for (i in 0 until a.length()) {
                val s = a.optString(i)
                if (s.isNotEmpty()) l.add(s)
            }
            val allowed = HashSet<String>()
            val al = j.optJSONArray("allowed")
            if (al != null) for (i in 0 until al.length()) {
                val s = al.optString(i)
                if (s.isNotEmpty()) allowed.add(s)
            }
            // Old config.json files have no "customHeaders" — they load as empty.
            val headers = LinkedHashMap<String, String>()
            val ch = j.optJSONObject("customHeaders")
            if (ch != null) {
                val keys = ch.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headers[k] = ch.optString(k)
                }
            }
            return Provider(j.optString("id"), j.optString("name"), j.optString("baseUrl"),
                j.optString("apiKey"), l, j.optBoolean("enabled", true), allowed, headers)
        }
    }
}

/** kind: image | text | binary | pdf | docx; path is a file name inside the attachments folder. */
class Att(val id: String, val name: String, val mime: String, val path: String,
          val size: Long, val kind: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("mime", mime)
        .put("path", path).put("size", size).put("kind", kind)

    companion object {
        fun from(j: JSONObject) = Att(j.optString("id"), j.optString("name"),
            j.optString("mime"), j.optString("path"), j.optLong("size"),
            j.optString("kind", "binary"))
    }
}

/** role: user / assistant in history; note / error are UI-only and never persisted or sent. */
class Msg(val role: String, var content: String, val ts: Long, var tokens: Int = 0,
          val atts: MutableList<Att> = ArrayList()) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("role", role)
        o.put("content", content)
        o.put("ts", ts)
        if (tokens > 0) o.put("tokens", tokens)
        if (atts.isNotEmpty()) {
            val a = JSONArray()
            for (at in atts) a.put(at.toJson())
            o.put("atts", a)
        }
        return o
    }

    companion object {
        fun from(j: JSONObject): Msg {
            val m = Msg(j.optString("role"), j.optString("content"),
                j.optLong("ts"), j.optInt("tokens", 0))
            val aa = j.optJSONArray("atts")
            // P0-2: one bad attachment entry must not kill the whole message.
            if (aa != null) for (i in 0 until aa.length()) {
                try { m.atts.add(Att.from(aa.getJSONObject(i))) } catch (e: Exception) {}
            }
            return m
        }
    }
}

class Conversation(
    val id: String,
    var title: String,
    var providerId: String,
    var modelId: String,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<Msg> = ArrayList()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("providerId", providerId)
        o.put("modelId", modelId)
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        val a = JSONArray()
        // P1-7: blank assistant placeholders (streaming that never produced
        // text, or a stream interrupted before any text) are never persisted.
        for (m in messages) if (m.role == "user" || m.role == "assistant") {
            if (m.role == "assistant" && m.content.isBlank()) continue
            a.put(m.toJson())
        }
        o.put("messages", a)
        return o
    }

    companion object {
        fun from(j: JSONObject): Conversation {
            val l = ArrayList<Msg>()
            val a = j.optJSONArray("messages")
            // P0-2: one corrupt message must not kill the whole conversation.
            if (a != null) for (i in 0 until a.length()) {
                try { l.add(Msg.from(a.getJSONObject(i))) } catch (e: Exception) {}
            }
            return Conversation(j.optString("id"), j.optString("title"), j.optString("providerId"),
                j.optString("modelId"), j.optLong("createdAt"), j.optLong("updatedAt"), l)
        }
    }
}

class Settings(var systemPrompt: String, var defaultProviderId: String, var defaultModel: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("systemPrompt", systemPrompt)
        .put("defaultProviderId", defaultProviderId)
        .put("defaultModel", defaultModel)

    companion object {
        fun from(j: JSONObject) = Settings(j.optString("systemPrompt", "You are a helpful assistant."),
            j.optString("defaultProviderId"), j.optString("defaultModel"))
    }
}
