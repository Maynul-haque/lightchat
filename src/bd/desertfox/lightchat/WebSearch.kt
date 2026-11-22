package bd.desertfox.lightchat

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** A single web-search hit: title, snippet and source URL. */
class WebResult(val title: String, val snippet: String, val url: String)

/**
 * Batch C — manual web search (DuckDuckGo, free, no API key, no new
 * dependency). Runs on the caller's worker thread; never on the UI thread.
 *
 * DuckDuckGo's lightweight HTML endpoints are scraped with plain string /
 * regex extraction — no HTML parser library. Both known markups are handled:
 * html.duckduckgo.com/html/ (`result__a` / `result__snippet`) and
 * lite.duckduckgo.com/lite/ (`result-link` / `result-snippet`).
 *
 * Honest limitation: DuckDuckGo serves a bot-challenge ("anomaly") page to
 * some datacenter IPs, so from some networks the search returns nothing.
 * That is never fatal — the caller keeps the chat going without results
 * and says so (see MainActivity.startStream). On a normal phone connection
 * the endpoints return real results.
 */
object WebSearch {
    private const val MAX_RESULTS = 5
    private const val MAX_CHARS = 4000
    private const val TIMEOUT_MS = 10000L

    private val A_TAG = Pattern.compile(
        "<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL)
    private val A_TAG_LITE = Pattern.compile(
        "<a[^>]+class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL)
    private val SNIP = Pattern.compile(
        "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL)
    private val SNIP_LITE = Pattern.compile(
        "<td[^>]+class=\"result-snippet\"[^>]*>(.*?)</td>", Pattern.DOTALL)
    private val TAG = Pattern.compile("<[^>]+>")
    private val WS = Pattern.compile("\\s+")

    /** Search DuckDuckGo for [query]. Returns up to 5 results, or an empty
     *  list on any failure (no network, bot page, empty page, timeout). */
    fun search(query: String): List<WebResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        TraceLog.log("search", "start query=" + q.take(80))
        val encoded = try { URLEncoder.encode(q, "UTF-8") } catch (e: Exception) { return emptyList() }
        val endpoints = arrayOf(
            "https://html.duckduckgo.com/html/?q=" + encoded,
            "https://lite.duckduckgo.com/lite/?q=" + encoded
        )
        for (url in endpoints) {
            try {
                val html = fetch(url) ?: continue
                val results = parse(html)
                if (results.isNotEmpty()) {
                    TraceLog.log("search", "done results=" + results.size + " via " + url.substring(8, 28))
                    return results
                }
                TraceLog.log("search", "no results from " + url.substring(8, 28))
            } catch (e: Exception) {
                TraceLog.log("search", "fail " + url.substring(8, 28) + ": " + e.javaClass.simpleName)
            }
        }
        TraceLog.log("search", "fail — no usable results")
        return emptyList()
    }

    private fun fetch(url: String): String? {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "LightChat/" + BuildInfo.VERSION_NAME)
            .header("Accept", "text/html")
            .get()
            .build()
        val call = ChatClient.sharedClient().newCall(req)
        call.timeout().timeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val r = call.execute()
        r.use { resp ->
            if (resp.code != 200) {
                TraceLog.log("search", "HTTP " + resp.code + " from " + url.substring(8, 28))
                return null
            }
            val bytes = resp.body?.bytes() ?: return null
            if (bytes.size > 512 * 1024) return null
            return String(bytes, Charsets.UTF_8)
        }
    }

    /** Extract results from DuckDuckGo HTML (either endpoint's markup). */
    fun parse(html: String): List<WebResult> {
        val out = ArrayList<WebResult>()
        var total = 0
        val links = ArrayList<Pair<String, String>>()
        var m = A_TAG.matcher(html)
        while (m.find()) links.add(Pair(cleanUrl(m.group(1)), cleanText(m.group(2))))
        if (links.isEmpty()) {
            m = A_TAG_LITE.matcher(html)
            while (m.find()) links.add(Pair(cleanUrl(m.group(1)), cleanText(m.group(2))))
        }
        val snippets = ArrayList<String>()
        var s = SNIP.matcher(html)
        while (s.find()) snippets.add(cleanText(s.group(1)))
        if (snippets.isEmpty()) {
            s = SNIP_LITE.matcher(html)
            while (s.find()) snippets.add(cleanText(s.group(1)))
        }
        for (i in links.indices) {
            if (out.size >= MAX_RESULTS) break
            val title = links[i].second
            val url = links[i].first
            if (title.isEmpty() || url.isEmpty()) continue
            var snippet = if (i < snippets.size) snippets[i] else ""
            if (snippet.length > 400) snippet = snippet.substring(0, 400)
            val len = title.length + snippet.length + url.length + 8
            if (total + len > MAX_CHARS) break
            total += len
            out.add(WebResult(title, snippet, url))
        }
        return out
    }

    /** DuckDuckGo wraps result links as //duckduckgo.com/l/?uddg=<urlencoded>.
     *  Unwrap to the real URL so sources are readable and tappable. */
    private fun cleanUrl(raw: String): String {
        var u = raw.trim()
        if (u.startsWith("//")) u = "https:" + u
        val marker = "uddg="
        val idx = u.indexOf(marker)
        if (u.contains("duckduckgo.com/l/") && idx >= 0) {
            val enc = u.substring(idx + marker.length)
            val end = enc.indexOf('&').let { if (it < 0) enc.length else it }
            return try {
                java.net.URLDecoder.decode(enc.substring(0, end), "UTF-8")
            } catch (e: Exception) { u }
        }
        return u
    }

    private fun cleanText(raw: String): String {
        var t = TAG.matcher(raw).replaceAll("")
        t = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        t = WS.matcher(t.trim()).replaceAll(" ")
        return t
    }

    /** The context block appended to the system prompt for a search message,
     *  so the model summarises the answer from the results and cites them. */
    fun contextBlock(query: String, results: List<WebResult>): String {
        val sb = StringBuilder()
        sb.append("\n\n[Web search results for \"").append(query.take(120)).append("\" — ")
        sb.append("answer using these results and cite the source URLs. ")
        sb.append("If they do not cover the question, say so plainly.]\n")
        for ((i, r) in results.withIndex()) {
            sb.append(i + 1).append(". ").append(r.title).append("\n")
            if (r.snippet.isNotEmpty()) sb.append("   ").append(r.snippet).append("\n")
            sb.append("   Source: ").append(r.url).append("\n")
        }
        return sb.toString()
    }
}
