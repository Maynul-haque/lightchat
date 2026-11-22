package bd.desertfox.lightchat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

object Ui {
    val BG = 0xFF14151A.toInt()
    val SURFACE = 0xFF1D2027.toInt()
    val SIDEBAR = 0xFF191C22.toInt()
    val ACCENT = 0xFF7C6CF7.toInt()
    val TEXT = 0xFFECEDF1.toInt()
    val DIM = 0xFF9AA0AC.toInt()
    val BUBBLE = 0xFF3A3560.toInt()
    val ERROR = 0xFFFF6B6B.toInt()
    val CODE_BG = 0xFF101218.toInt()
    val INLINE_CODE_BG = 0xFF262A33.toInt()
    val INPUT_BG = 0xFF262A33.toInt()

    fun dp(ctx: Context, n: Int): Int = Math.round(n * ctx.resources.displayMetrics.density)

    fun round(color: Int, radiusDp: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radiusDp
        return g
    }

    fun toast(ctx: Context, s: String) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    }

    fun copy(ctx: Context, s: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", s))
        toast(ctx, "Copied")
    }

    fun tv(ctx: Context, text: String, sizeSp: Float, color: Int): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        return t
    }
}

/** Chat message list. View types: 0 user bubble, 1 assistant, 2 note / error. */
class ChatAdapter(private val act: MainActivity, private val conv: Conversation) : BaseAdapter() {

    // P1-1: cache the parsed markdown blocks per message, invalidated when
    // the content changes — a streaming reply was re-parsed every 150 ms.
    private val blockCache = WeakHashMap<Msg, Pair<String, List<Markdown.Block>>>()
    override fun getCount(): Int = conv.messages.size
    override fun getItem(position: Int): Msg = conv.messages[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 3
    override fun getItemViewType(position: Int): Int = when (conv.messages[position].role) {
        "user" -> 0
        "assistant" -> 1
        else -> 2
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val m = conv.messages[position]
        return when (m.role) {
            "user" -> userView(m, convertView)
            "assistant" -> assistantView(m, position, convertView)
            "note" -> noteView(m, convertView)
            else -> errorView(m, convertView)
        }
    }

    private fun userView(m: Msg, convertView: View?): View {
        val root = convertView as? LinearLayout ?: LinearLayout(act).also {
            it.orientation = LinearLayout.VERTICAL
            it.setPadding(Ui.dp(act, 10), Ui.dp(act, 3), Ui.dp(act, 10), Ui.dp(act, 3))
        }
        root.orientation = LinearLayout.VERTICAL
        root.removeAllViews()
        val bubble = LinearLayout(act)
        bubble.orientation = LinearLayout.VERTICAL
        bubble.background = Ui.round(Ui.BUBBLE, 16f)
        if (m.atts.isEmpty()) bubble.setPadding(Ui.dp(act, 14), Ui.dp(act, 9), Ui.dp(act, 14), Ui.dp(act, 9))
        else bubble.setPadding(Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 6))
        for ((idx, a) in m.atts.withIndex()) {
            val v = attView(a)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (idx > 0) lp.setMargins(0, Ui.dp(act, 6), 0, 0)
            bubble.addView(v, lp)
        }
        if (m.content.isNotBlank()) {
            val tv = TextView(act)
            tv.text = m.content
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            tv.setTextColor(Color.WHITE)
            tv.setTextIsSelectable(true)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (m.atts.isNotEmpty()) lp.setMargins(0, Ui.dp(act, 8), 0, Ui.dp(act, 2))
            bubble.addView(tv, lp)
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.END
        lp.marginStart = Ui.dp(act, 56)
        root.addView(bubble, lp)
        // No action row under user messages: the copy/edit buttons used to
        // overlay the bubble (FrameLayout root). Copy/Edit stay in the
        // long-press menu (MainActivity.messageMenu).
        bubble.setOnLongClickListener { act.messageMenu(m); true }
        bubble.isLongClickable = true
        return root
    }

    private fun attView(a: Att): View {
        if (a.kind == "image") {
            val iv = android.widget.ImageView(act)
            iv.setImageBitmap(Attachments.thumb(a.path, 480))
            iv.adjustViewBounds = true
            iv.maxWidth = Ui.dp(act, 240)
            iv.contentDescription = a.name
            iv.setOnClickListener { act.viewImageDialog(a) }
            return iv
        }
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Ui.round(0x33000000.toInt(), 8f)
        row.setPadding(Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 6))
        row.addView(Ui.tv(act, "\uD83D\uDCC4", 13f, Color.WHITE))
        row.addView(Ui.tv(act, a.name + " (" + Attachments.sizeLabel(a.size) + ")", 13f, Color.WHITE))
        return row
    }

    private fun noteView(m: Msg, convertView: View?): View {
        val root = convertView as? FrameLayout ?: FrameLayout(act).also {
            it.setPadding(Ui.dp(act, 10), Ui.dp(act, 2), Ui.dp(act, 10), Ui.dp(act, 2))
        }
        root.removeAllViews()
        val tv = Ui.tv(act, m.content, 12f, Ui.DIM)
        tv.gravity = Gravity.CENTER
        root.addView(tv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun errorView(m: Msg, convertView: View?): View {
        val root = convertView as? LinearLayout ?: LinearLayout(act).also {
            it.orientation = LinearLayout.VERTICAL
            it.setPadding(Ui.dp(act, 14), Ui.dp(act, 3), Ui.dp(act, 14), Ui.dp(act, 3))
        }
        root.removeAllViews()
        root.addView(Ui.tv(act, m.content, 14f, Ui.ERROR))
        val retry = Button(act)
        retry.text = "Retry"
        retry.textSize = 13f
        retry.setTextColor(Ui.TEXT)
        retry.background = Ui.round(Ui.SURFACE, 10f)
        retry.isAllCaps = false
        retry.setOnClickListener { act.retryLast() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(act, 4)
        root.addView(retry, lp)
        return root
    }

    private fun assistantView(m: Msg, position: Int, convertView: View?): View {
        val root = convertView as? LinearLayout ?: LinearLayout(act).also {
            it.orientation = LinearLayout.VERTICAL
            it.setPadding(Ui.dp(act, 14), Ui.dp(act, 3), Ui.dp(act, 14), Ui.dp(act, 3))
        }
        root.removeAllViews()

        val blocks = blockCache[m]?.takeIf { it.first == m.content }?.second
            ?: Markdown.split(m.content).also {
                if (blockCache.size > 200) blockCache.clear()
                blockCache[m] = m.content to it
            }
        for (block in blocks) {
            if (block.code != null) {
                root.addView(codeView(block.code))
            } else if (block.table != null) {
                root.addView(tableView(block.table))
            } else if (block.img != null) {
                root.addView(imgView(block.img))
            } else if (block.fileUrl != null) {
                root.addView(fileChipView(block.fileUrl.first, block.fileUrl.second))
            } else if (!block.text.isNullOrBlank()) {
                val tv = TextView(act)
                tv.text = Markdown.render(block.text, act, Ui.ACCENT, Ui.INLINE_CODE_BG)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                tv.setTextColor(Ui.TEXT)
                tv.setTextIsSelectable(true)
                tv.movementMethod = LinkMovementMethod.getInstance()
                tv.setLineSpacing(0f, 1.15f)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = Ui.dp(act, 2)
                root.addView(tv, lp)
            }
        }

        if (m.tokens > 0) {
            val tk = Ui.tv(act, "tokens: " + m.tokens, 11f, Ui.DIM)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = Ui.dp(act, 2)
            root.addView(tk, lp)
        }

        // P0-1: thinking indicator while the placeholder is still blank, and
        // a streaming cursor while text is arriving (last message only).
        if (act.isStreaming() && position == getCount() - 1) {
            if (m.content.isBlank()) {
                root.addView(Ui.tv(act, "Thinking…", 14f, Ui.DIM))
            } else {
                root.addView(Ui.tv(act, "▍", 15f, Ui.ACCENT))
            }
        }

        if (!act.isStreaming() && m.content.isNotBlank()) {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val copy = actionButton("\u2B09")
            copy.setOnClickListener { Ui.copy(act, m.content) }
            row.addView(copy)
            val regen = actionButton("\u21BB")
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = Ui.dp(act, 8)
            lp.topMargin = Ui.dp(act, 6)
            regen.setOnClickListener { act.regenerateFrom(m) }
            row.addView(regen, lp)
            root.addView(row)
        }

        root.setOnLongClickListener { act.messageMenu(m); true }
        root.isLongClickable = true
        return root
    }

    private fun tableView(rows: List<List<String>>): View {
        val scroll = android.widget.HorizontalScrollView(act)
        scroll.isHorizontalScrollBarEnabled = false
        val tbl = android.widget.TableLayout(act)
        for ((ri, r) in rows.withIndex()) {
            val tr = android.widget.TableRow(act)
            for (cell in r) {
                val tv = TextView(act)
                tv.text = cell
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                tv.setTextColor(Ui.TEXT)
                tv.setTypeface(null, if (ri == 0) Typeface.BOLD else Typeface.NORMAL)
                tv.setPadding(Ui.dp(act, 10), Ui.dp(act, 5), Ui.dp(act, 10), Ui.dp(act, 5))
                if (ri == 0) tv.setBackgroundColor(Ui.CODE_BG)
                tr.addView(tv)
            }
            tbl.addView(tr)
        }
        scroll.addView(tbl)
        val wrap = LinearLayout(act)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4))
        wrap.addView(scroll)
        return wrap
    }

    private fun imgView(im: Markdown.Img): View {
        val iv = android.widget.ImageView(act)
        iv.adjustViewBounds = true
        iv.maxWidth = Ui.dp(act, 260)
        iv.tag = im.url
        iv.minimumHeight = Ui.dp(act, 60)
        iv.setOnClickListener { act.viewOutputImage(im.alt, im.url) }
        if (im.url.startsWith("data:")) {
            // P0-6: decode off the UI thread, sampled, cached per URL — the
            // old code decoded full-res on every repaint.
            OutputKit.loadDataUrl(act, im.url, 1200) { bmp ->
                if (iv.tag == im.url && bmp != null) {
                    iv.setImageBitmap(bmp)
                    iv.minimumHeight = 0
                }
            }
        } else {
            OutputKit.load(act, im.url, 1200) { bmp ->
                if (iv.tag == im.url) {
                    if (bmp != null) {
                        iv.setImageBitmap(bmp)
                        iv.minimumHeight = 0
                    } else {
                        iv.setImageDrawable(null)
                    }
                }
            }
        }
        val wrap = LinearLayout(act)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4))
        wrap.addView(iv)
        return wrap
    }

    private fun fileChipView(name: String, url: String): View {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Ui.round(Ui.SURFACE, 10f)
        row.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8))
        row.addView(Ui.tv(act, "\u2B07", 13f, Ui.TEXT))
        row.addView(Ui.tv(act, " " + name, 13f, Ui.TEXT))
        row.setOnClickListener { act.downloadAndOpen(url) }
        val wrap = LinearLayout(act)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4))
        wrap.addView(row)
        return wrap
    }

    private fun actionButton(label: String): Button {
        val b = Button(act)
        b.text = label
        b.textSize = 15f
        b.setTextColor(Ui.DIM)
        b.background = Ui.round(Ui.SURFACE, 12f)
        b.isAllCaps = false
        b.setPadding(Ui.dp(act, 10), Ui.dp(act, 2), Ui.dp(act, 10), Ui.dp(act, 2))
        b.minWidth = 0
        b.minimumWidth = 0
        return b
    }

    private fun codeView(code: Markdown.Code): View {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.background = Ui.round(Ui.CODE_BG, 10f)
        box.setPadding(Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 8))

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.tv(act, if (code.lang.isBlank()) "code" else code.lang, 11f, Ui.DIM))
        head.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        val cp = Button(act)
        cp.text = "Copy"
        cp.textSize = 11f
        cp.setTextColor(Ui.TEXT)
        cp.background = Ui.round(Ui.SURFACE, 8f)
        cp.isAllCaps = false
        cp.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), 0)
        cp.setOnClickListener { Ui.copy(act, code.code) }
        head.addView(cp)
        val sv = Button(act)
        sv.text = "Save"
        sv.textSize = 11f
        sv.setTextColor(Ui.TEXT)
        sv.background = Ui.round(Ui.SURFACE, 8f)
        sv.isAllCaps = false
        sv.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), 0)
        val svlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        svlp.marginStart = Ui.dp(act, 6)
        sv.setOnClickListener { act.saveCodeAsFile(code.code, code.lang) }
        head.addView(sv, svlp)
        box.addView(head)

        val body = TextView(act)
        body.text = code.code
        body.typeface = Typeface.MONOSPACE
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        body.setTextColor(Ui.TEXT)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(act, 4)
        box.addView(body, lp)

        val wrap = LinearLayout(act)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4))
        wrap.addView(box)
        return wrap
    }
}

/** Sidebar conversation list rows. */
class ChatsAdapter(private val act: MainActivity) : BaseAdapter() {
    private val fmt = SimpleDateFormat("MMM d", Locale.US)

    override fun getCount(): Int = Store.chats.size
    override fun getItem(position: Int): Conversation = Store.chats[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 1
    override fun getItemViewType(position: Int): Int = 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val c = Store.chats[position]
        val root = convertView as? LinearLayout ?: LinearLayout(act).also {
            it.orientation = LinearLayout.VERTICAL
            it.setPadding(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10))
        }
        root.removeAllViews()
        val title = Ui.tv(act, c.title.ifBlank { "(untitled)" }, 15f, Ui.TEXT)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        root.addView(title)
        val date = Ui.tv(act, fmt.format(Date(c.updatedAt)), 12f, Ui.DIM)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(act, 1)
        root.addView(date, lp)
        root.setOnClickListener { act.openConversation(c) }
        root.setOnLongClickListener { act.chatMenu(c); true }
        return root
    }
}

/** Provider list rows for the Providers screen. */
class ProvidersAdapter(private val act: ProvidersActivity) : BaseAdapter() {
    override fun getCount(): Int = Store.providers.size
    override fun getItem(position: Int): Provider = Store.providers[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 1
    override fun getItemViewType(position: Int): Int = 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val p = Store.providers[position]
        val root = convertView as? LinearLayout ?: LinearLayout(act).also {
            it.orientation = LinearLayout.VERTICAL
            it.setPadding(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 16), Ui.dp(act, 10))
        }
        root.removeAllViews()
        val name = Ui.tv(act, p.name.ifBlank { "(unnamed)" }, 15f, if (p.enabled) Ui.TEXT else Ui.DIM)
        name.typeface = Typeface.DEFAULT_BOLD
        root.addView(name)
        val url = Ui.tv(act, p.baseUrl, 12f, Ui.DIM)
        url.maxLines = 1
        url.ellipsize = TextUtils.TruncateAt.MIDDLE
        root.addView(url)
        val cnt = Ui.tv(act, p.models.size.toString() + " models", 12f, Ui.DIM)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(act, 1)
        root.addView(cnt, lp)
        root.setOnClickListener { act.editProvider(p) }
        root.setOnLongClickListener { act.deleteProvider(p); true }
        return root
    }
}
