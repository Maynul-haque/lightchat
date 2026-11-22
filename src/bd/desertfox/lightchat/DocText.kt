package bd.desertfox.lightchat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Lightweight text extraction for PDF (text PDFs only, no OCR) and DOCX.
 * No library: PDF streams are inflated with java.util.zip.Inflater, DOCX is
 * unzipped with java.util.zip.ZipInputStream. Returns null when nothing
 * readable is found — the caller then says so instead of sending junk.
 *
 * Known limits: encrypted PDFs, PDFs with exotic stream filters (e.g.
 * ASCII85), and PDFs with custom font encodings (text may come out garbled)
 * are not handled. Compressed object streams (/ObjStm) are attempted: their
 * literal/hex strings are scanned, which recovers text in some Word/Chrome
 * PDFs, but content stored only as indirect objects in an object stream may
 * still be missed — the caller then says so honestly.
 */
object DocText {
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    private const val MAX_INFLATED_BYTES = 8 * 1024 * 1024

    /** Extract text from a PDF or DOCX by file name extension. Null = failure. */
    fun extract(name: String, bytes: ByteArray, cap: Int = Attachments.TEXT_CAP): String? {
        if (bytes.size > MAX_FILE_BYTES) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        val txt = when (ext) {
            "pdf" -> extractPdf(bytes, cap)
            "docx" -> extractDocx(bytes)
            else -> null
        } ?: return null
        val t = txt.trim()
        if (t.isEmpty()) return null
        return if (t.length > cap) t.substring(0, cap) else t
    }

    // ---- PDF ----

    private fun extractPdf(bytes: ByteArray, cap: Int = Attachments.TEXT_CAP): String? {
        // Encrypted PDFs cannot be read without the password.
        if (containsBytes(bytes, "/Encrypt".toByteArray(Charsets.US_ASCII))) return null
        val streamKw = "stream".toByteArray(Charsets.US_ASCII)
        val endKw = "endstream".toByteArray(Charsets.US_ASCII)
        val out = StringBuilder()
        var pos = 0
        while (true) {
            // P1-9: apply the cap during extraction, not after building the
            // full text of a 30 MB file.
            if (out.length >= cap) break
            var sIdx = indexOf(bytes, streamKw, pos)
            if (sIdx < 0) break
            // Skip the "stream" inside "endstream".
            if (sIdx >= 3 && bytes[sIdx - 3] == 'e'.code.toByte() &&
                bytes[sIdx - 2] == 'n'.code.toByte() && bytes[sIdx - 1] == 'd'.code.toByte()) {
                pos = sIdx + streamKw.size
                continue
            }
            var dataStart = sIdx + streamKw.size
            // "stream" must be followed by CRLF, LF or CR.
            if (dataStart + 1 < bytes.size && bytes[dataStart] == '\r'.code.toByte() &&
                bytes[dataStart + 1] == '\n'.code.toByte()) dataStart += 2
            else if (dataStart < bytes.size && (bytes[dataStart] == '\n'.code.toByte() ||
                    bytes[dataStart] == '\r'.code.toByte())) dataStart += 1
            val eIdx = indexOf(bytes, endKw, dataStart)
            if (eIdx < 0) break
            val raw = bytes.copyOfRange(dataStart, eIdx)
            val dictStart = maxOf(0, sIdx - 1200)
            val dict = String(bytes, dictStart, sIdx - dictStart, Charsets.ISO_8859_1)
            val isObjStm = dict.contains("/ObjStm")
            val isImage = dict.contains("/Subtype /Image") || dict.contains("/DCTDecode") ||
                dict.contains("/JPXDecode") || dict.contains("/JBIG2Decode")
            if (isObjStm) {
                // P1-9: compressed object stream — inflate it and scan the
                // literal/hex strings inside. This recovers text in some
                // Word/Chrome PDFs whose objects live in an object stream.
                if (dict.contains("/FlateDecode")) {
                    val content = inflate(raw)
                    if (content != null) {
                        val t = stringsFromBytes(content, cap - out.length)
                        if (t.isNotBlank()) {
                            out.append(t)
                            if (!t.endsWith("\n")) out.append('\n')
                        }
                    }
                }
            } else if (!isImage) {
                val content: ByteArray? = if (dict.contains("/FlateDecode")) {
                    inflate(raw)
                } else if (String(raw, Charsets.ISO_8859_1).contains("BT")) {
                    raw // uncompressed content stream
                } else null
                if (content != null) {
                    val t = textFromContent(content, cap - out.length)
                    if (t.isNotBlank()) {
                        out.append(t)
                        if (!t.endsWith("\n")) out.append('\n')
                    }
                }
            }
            pos = eIdx + endKw.size
        }
        val res = out.toString().trim()
        return if (res.isEmpty()) null else res
    }

    /** Scan raw bytes for (...) literal and <...> hex strings and decode them.
     *  Used for object-stream contents, which have no BT...ET blocks. */
    private fun stringsFromBytes(data: ByteArray, cap: Int): String {
        val s = String(data, Charsets.ISO_8859_1)
        val out = StringBuilder()
        var i = 0
        while (i < s.length && out.length < cap) {
            val c = s[i]
            if (c == '(') {
                val (str, next) = readLiteral(s, i)
                if (str != null && str.isNotBlank()) {
                    // Skip strings that are clearly not text (binary junk).
                    val printable = str.count { it.code in 32..126 || it.code >= 160 || it == '\n' || it == '\t' }
                    if (printable * 4 >= str.length * 3) out.append(str).append(' ')
                    i = next
                } else i++
                continue
            }
            if (c == '<' && i + 1 < s.length && s[i + 1] != '<') {
                val (str, next) = readHex(s, i)
                if (str != null && str.isNotBlank()) {
                    val printable = str.count { it.code in 32..126 || it.code >= 160 || it == '\n' || it == '\t' }
                    if (printable * 4 >= str.length * 3) out.append(str).append(' ')
                    i = next
                } else i++
                continue
            }
            i++
        }
        return out.toString()
    }

    /** Inflate a FlateDecode stream (zlib first, raw deflate as fallback). */
    private fun inflate(raw: ByteArray): ByteArray? {
        return inflateWith(raw, false) ?: inflateWith(raw, true)
    }

    private fun inflateWith(raw: ByteArray, nowrap: Boolean): ByteArray? {
        return try {
            val inf = Inflater(nowrap)
            inf.setInput(raw)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED_BYTES) break
            }
            inf.end()
            val res = out.toByteArray()
            if (res.isEmpty()) null else res
        } catch (e: Exception) {
            null
        }
    }

    /** Pull the text strings out of BT...ET blocks of a content stream. */
    private fun textFromContent(data: ByteArray, cap: Int = Int.MAX_VALUE): String {
        // Latin-1 keeps byte offsets 1:1 while scanning.
        val s = String(data, Charsets.ISO_8859_1)
        val out = StringBuilder()
        var i = 0
        var inText = false
        while (i < s.length && out.length < cap) {
            val c = s[i]
            if (!inText) {
                if (c == 'B' && i + 1 < s.length && s[i + 1] == 'T' &&
                    opBoundary(s, i - 1) && opBoundary(s, i + 2)) {
                    inText = true
                    i += 2
                } else i++
                continue
            }
            if (c == 'E' && i + 1 < s.length && s[i + 1] == 'T' &&
                opBoundary(s, i - 1) && opBoundary(s, i + 2)) {
                inText = false
                out.append('\n')
                i += 2
                continue
            }
            if (c == '(') {
                val (str, next) = readLiteral(s, i)
                if (str != null) {
                    out.append(str).append(' ')
                    i = next
                } else i++
                continue
            }
            if (c == '<' && i + 1 < s.length && s[i + 1] != '<') {
                val (str, next) = readHex(s, i)
                if (str != null) {
                    out.append(str).append(' ')
                    i = next
                } else i++
                continue
            }
            i++
        }
        return out.toString()
    }

    /** True when the char at idx is a delimiter (or outside the string). */
    private fun opBoundary(s: String, idx: Int): Boolean {
        if (idx < 0 || idx >= s.length) return true
        val c = s[idx]
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' ||
            c == '\u0000' || c == '[' || c == ']' || c == '(' || c == ')' ||
            c == '<' || c == '>' || c == '/' || c == '%'
    }

    /** Read a (...) literal string starting at s[start]. Returns text + next index. */
    private fun readLiteral(s: String, start: Int): Pair<String?, Int> {
        val bytes = ArrayList<Int>()
        var i = start + 1
        var depth = 1
        while (i < s.length && depth > 0) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    val n = s[i + 1]
                    when (n) {
                        'n' -> { bytes.add('\n'.code); i += 2 }
                        'r' -> { bytes.add('\r'.code); i += 2 }
                        't' -> { bytes.add('\t'.code); i += 2 }
                        'b' -> { bytes.add(8); i += 2 }
                        'f' -> { bytes.add(12); i += 2 }
                        '(' -> { bytes.add('('.code); i += 2 }
                        ')' -> { bytes.add(')'.code); i += 2 }
                        '\\' -> { bytes.add('\\'.code); i += 2 }
                        '\n' -> { i += 2 } // line continuation
                        '\r' -> { i += if (i + 2 < s.length && s[i + 2] == '\n') 3 else 2 }
                        in '0'..'7' -> {
                            var v = 0
                            var j = i + 1
                            var count = 0
                            while (j < s.length && count < 3 && s[j] in '0'..'7') {
                                v = v * 8 + (s[j] - '0')
                                j++
                                count++
                            }
                            bytes.add(v and 0xFF)
                            i = j
                        }
                        else -> { bytes.add(n.code); i += 2 }
                    }
                }
                c == '(' -> { depth++; bytes.add(c.code); i++ }
                c == ')' -> { depth--; if (depth > 0) bytes.add(c.code); i++ }
                else -> { bytes.add(c.code and 0xFF); i++ }
            }
        }
        if (depth != 0) return Pair(null, start + 1)
        return Pair(decodePdfBytes(bytes.toIntArray()), i)
    }

    /** Read a <...> hex string starting at s[start]. Returns text + next index. */
    private fun readHex(s: String, start: Int): Pair<String?, Int> {
        var i = start + 1
        val digits = StringBuilder()
        while (i < s.length && s[i] != '>') {
            val c = s[i]
            if (!c.isWhitespace()) digits.append(c)
            i++
        }
        if (i >= s.length) return Pair(null, start + 1)
        var hex = digits.toString()
        if (hex.length % 2 == 1) hex += "0"
        val bytes = IntArray(hex.length / 2)
        for (k in bytes.indices) {
            val v = hex.substring(k * 2, k * 2 + 2).toIntOrNull(16)
                ?: return Pair(null, i + 1)
            bytes[k] = v
        }
        return Pair(decodePdfBytes(bytes), i + 1)
    }

    /** Decode PDF string bytes: UTF-16BE/LE with BOM, else Latin-1 (WinAnsi approx). */
    private fun decodePdfBytes(b: IntArray): String {
        if (b.size >= 2 && b[0] == 0xFE && b[1] == 0xFF) {
            val sb = StringBuilder()
            var i = 2
            while (i + 1 < b.size) {
                sb.append(((b[i] shl 8) or b[i + 1]).toChar())
                i += 2
            }
            return sb.toString()
        }
        if (b.size >= 2 && b[0] == 0xFF && b[1] == 0xFE) {
            val sb = StringBuilder()
            var i = 2
            while (i + 1 < b.size) {
                sb.append((b[i] or (b[i + 1] shl 8)).toChar())
                i += 2
            }
            return sb.toString()
        }
        val sb = StringBuilder()
        for (v in b) sb.append(v.toChar())
        return sb.toString()
    }

    // ---- DOCX ----

    private fun extractDocx(bytes: ByteArray): String? {
        return try {
            var xml: String? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        xml = String(zin.readBytes(), Charsets.UTF_8)
                        break
                    }
                    entry = zin.nextEntry
                }
            }
            val doc = xml ?: return null
            val out = StringBuilder()
            // Split into paragraphs so line breaks survive.
            val paras = doc.split("</w:p>")
            val textRe = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
            for (para in paras) {
                val line = StringBuilder()
                for (hit in textRe.findAll(para)) line.append(unescapeXml(hit.groupValues[1]))
                val t = line.toString().trim()
                if (t.isNotEmpty()) out.append(t).append('\n')
            }
            val res = out.toString().trim()
            if (res.isEmpty()) null else res
        } catch (e: Exception) {
            null
        }
    }

    private fun unescapeXml(s: String): String {
        var t = s
        t = Regex("&#(\\d+);").replace(t) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m ->
            val code = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }

    // ---- byte helpers ----

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun containsBytes(hay: ByteArray, needle: ByteArray): Boolean =
        indexOf(hay, needle, 0) >= 0
}
