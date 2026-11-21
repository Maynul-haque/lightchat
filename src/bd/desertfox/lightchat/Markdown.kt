package bd.desertfox.lightchat

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/** Hand-written markdown -> blocks + Spannable. No library, no WebView. */
object Markdown {

    class Code(val lang: String, val code: String)
    class Img(val alt: String, val url: String)

    class Block(val text: String?, val code: Code?,
                val table: List<List<String>>? = null,
                val img: Img? = null,
                val fileUrl: Pair<String, String>? = null)

    private val IMG_LINE = Regex("^!\\[([^\\]]*)\\]\\(([^)]+)\\)\\s*$")
    private val FILE_LINE = Regex("^\\[([^\\]]+)\\]\\((https?://[^)]+)\\)\\s*$")
    private val NUM_LIST = Regex("^(\\d+)\\.\\s+(.*)")

    private fun isTableSep(l: String): Boolean {
        val t = l.trim()
        return t.startsWith("|") && t.contains("-") && Regex("^[|:\\- ]+$").matches(t)
    }

    private fun parseTable(lines: List<String>): List<List<String>>? {
        val sepIdx = lines.indexOfFirst { isTableSep(it) }
        if (sepIdx != 0 && sepIdx != 1) return null
        val rows = ArrayList<List<String>>()
        for (l in lines) {
            if (isTableSep(l)) continue
            val cells = l.trim().trimEnd('|').removePrefix("|").split("|").map { it.trim() }
            if (cells.isNotEmpty()) rows.add(cells)
        }
        return if (rows.size >= 2) rows else null
    }

    /** Split a message into text chunks, code fences, tables, images and file links. */
    fun split(src: String): List<Block> {
        val out = ArrayList<Block>()
        val lines = src.split("\n")
        var i = 0
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                var t = buf.toString()
                buf.setLength(0)
                while (t.endsWith("\n")) t = t.substring(0, t.length - 1)
                out.add(Block(t, null))
            }
        }
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("```")) {
                flush()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++ // skip closing fence (or run off the end)
                out.add(Block(null, Code(lang, code.toString().trimEnd('\n'))))
                continue
            }
            if (t.startsWith("|")) {
                var j = i
                while (j < lines.size && lines[j].trim().startsWith("|")) j++
                val tbl = parseTable(lines.subList(i, j))
                if (tbl != null) {
                    flush()
                    out.add(Block(null, null, table = tbl))
                    i = j
                    continue
                }
            }
            val img = IMG_LINE.find(t)
            if (img != null) {
                flush()
                out.add(Block(null, null, img = Img(img.groupValues[1], img.groupValues[2])))
                i++
                continue
            }
            val fl = FILE_LINE.find(t)
            if (fl != null && OutputKit.isFileUrl(fl.groupValues[2])) {
                flush()
                out.add(Block(null, null, fileUrl = Pair(fl.groupValues[1], fl.groupValues[2])))
                i++
                continue
            }
            buf.append(lines[i]).append('\n')
            i++
        }
        flush()
        return out
    }

    private val INLINE = Regex("(\\*\\*(.+?)\\*\\*)|(\\*([^*]+)\\*)|(`([^`]+)`)|(https?://[^\\s)\\]>]+)")

    fun render(src: String, ctx: Context, accent: Int, codeBg: Int): CharSequence {
        val b = SpannableStringBuilder()
        for (raw in src.split("\n")) {
            var line = raw
            val t = line.trimStart()
            val indent = raw.takeWhile { it == ' ' }.length
            val pre = if (indent > 0) "    ".repeat(Math.min(indent / 2, 4)) else ""
            var heading = 0
            if (t.startsWith("### ")) { heading = 3; line = t.substring(4) }
            else if (t.startsWith("## ")) { heading = 2; line = t.substring(3) }
            else if (t.startsWith("# ")) { heading = 1; line = t.substring(2) }
            var bold = heading > 0
            if (heading == 0) {
                if (t.startsWith("- ") || t.startsWith("* ")) {
                    line = pre + "• " + t.substring(2)
                    bold = indent > 0
                } else {
                    val nm = NUM_LIST.find(t)
                    if (nm != null) {
                        line = pre + nm.groupValues[1] + ". " + nm.groupValues[2]
                        bold = indent > 0
                    } else if (t.startsWith(">")) {
                        line = "▎" + t.removePrefix(">").trim()
                    }
                }
            }
            val start = b.length
            var idx = 0
            for (m in INLINE.findAll(line)) {
                val r = m.range
                if (r.first > idx) b.append(line.substring(idx, r.first))
                val s = b.length
                val inner = m.groups[2]?.value
                val ital = m.groups[4]?.value
                val code = m.groups[6]?.value
                val url = m.groups[7]?.value
                when {
                    inner != null -> { b.append(inner); b.setSpan(StyleSpan(Typeface.BOLD), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    ital != null -> { b.append(ital); b.setSpan(StyleSpan(Typeface.ITALIC), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    code != null -> {
                        b.append(code)
                        b.setSpan(TypefaceSpan("monospace"), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        b.setSpan(BackgroundColorSpan(codeBg), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    url != null -> {
                        b.append(url)
                        b.setSpan(URLSpan(url), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        b.setSpan(StyleSpan(Typeface.BOLD), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                idx = r.last + 1
            }
            if (idx < line.length) b.append(line.substring(idx))
            if (bold) {
                b.setSpan(StyleSpan(Typeface.BOLD), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (heading > 0) {
                b.setSpan(RelativeSizeSpan(if (heading == 1) 1.3f else if (heading == 2) 1.18f else 1.08f),
                    start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            b.append("\n")
        }
        while (b.isNotEmpty() && b[b.length - 1] == '\n') b.delete(b.length - 1, b.length)
        return b
    }

    /** Open a tapped bare URL (URLSpan handles it, this is only a helper for tests). */
    fun open(ctx: Context, url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
    }
}
