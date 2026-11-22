package bd.desertfox.lightchat

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toolbar

class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var chatList: ListView
    private lateinit var emptyState: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var modelBtn: TextView
    private lateinit var scrim: View
    private lateinit var sidebarPanel: LinearLayout
    private lateinit var chatsList: ListView
    // v0.5.1: nullable — the chatList scroll listener is registered before
    // this view is created in buildUi(), so the listener must never assume
    // it is initialised (UninitializedPropertyAccessException on launch).
    private var scrollDownBtn: TextView? = null

    private var conv: Conversation? = null
    private var chatAdapter: ChatAdapter? = null
    private var flag: StopFlag? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastPaint = 0L
    private var pendingPaint = false

    fun isStreaming(): Boolean = flag != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.5.1: crash handler FIRST, before anything that can throw —
        // in v0.5.0 it was only installed at the end of Store.load(), so a
        // crash during load left no crash.log and no trace at all.
        TraceLog.init(this)
        TraceLog.installCrashHandler()
        TraceLog.log("app", "start v" + BuildInfo.VERSION_NAME)
        try {
            TraceLog.log("app", "Store.load start")
            Store.load(this)
            TraceLog.log("app", "Store.load done providers=" + Store.providers.size +
                " chats=" + Store.chats.size)
        } catch (e: Exception) {
            // A half-loaded Store is better than no app: continue with
            // whatever was loaded; the trace says what failed.
            TraceLog.log("app", "Store.load FAILED: " + e.javaClass.simpleName +
                ": " + (e.message ?: ""))
        }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        Store.log("app start v" + ver)
        try {
            TraceLog.log("app", "buildUi start")
            buildUi()
            TraceLog.log("app", "buildUi done")
        } catch (e: Exception) {
            TraceLog.log("app", "buildUi FAILED: " + e.javaClass.simpleName +
                ": " + (e.message ?: ""))
            throw e
        }
        try {
            conv = Store.chats.firstOrNull()
            renderChat()
            refreshSidebar()
            TraceLog.log("app", "first render done chats=" + Store.chats.size)
        } catch (e: Exception) {
            TraceLog.log("app", "first render FAILED: " + e.javaClass.simpleName +
                ": " + (e.message ?: ""))
            throw e
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        setContentView(root)

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setBackgroundColor(Ui.BG)
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top bar
        val bar = Toolbar(this)
        bar.setBackgroundColor(Ui.SURFACE)
        bar.title = ""
        val barLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56))
        content.addView(bar, barLp)

        val burger = Ui.tv(this, "☰", 22f, Ui.TEXT)
        burger.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0)
        burger.setOnClickListener { openSidebar() }
        bar.addView(burger, Toolbar.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.CENTER_VERTICAL))

        modelBtn = Ui.tv(this, "", 15f, Ui.TEXT)
        modelBtn.gravity = Gravity.CENTER
        modelBtn.maxLines = 1
        modelBtn.ellipsize = TextUtils.TruncateAt.MIDDLE
        modelBtn.setOnClickListener { openModelPicker() }
        bar.addView(modelBtn, Toolbar.LayoutParams(
            Ui.dp(this, 210), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // Chat list
        chatList = ListView(this)
        chatList.divider = null
        chatList.dividerHeight = 0
        chatList.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6))
        chatList.clipToPadding = false
        chatList.setBackgroundColor(Ui.BG)
        chatList.visibility = View.GONE
        // P1-2: track whether the user scrolled away from the bottom, so the
        // floating ↓ button can appear instead of a forced scroll.
        chatList.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, s: Int) {}
            override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {
                // v0.5.1: scrollDownBtn is created later in buildUi() — the
                // listener must tolerate it not existing yet.
                val btn = scrollDownBtn ?: return
                if (!isStreaming()) { btn.visibility = View.GONE; return }
                val atBottom = first + visible >= total
                btn.visibility = if (atBottom) View.GONE else View.VISIBLE
            }
        })
        val listLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        content.addView(chatList, listLp)

        // Empty state
        emptyState = LinearLayout(this)
        emptyState.orientation = LinearLayout.VERTICAL
        emptyState.gravity = Gravity.CENTER
        emptyState.setBackgroundColor(Ui.BG)
        emptyState.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0)
        val greet = Ui.tv(this, "What can I help with?", 20f, Ui.TEXT)
        greet.typeface = android.graphics.Typeface.DEFAULT_BOLD
        emptyState.addView(greet)
        val chipLabels = arrayOf("Write a story", "Explain code", "Make a plan", "Summarise this")
        var row: LinearLayout? = null
        for (i in chipLabels.indices) {
            if (i % 2 == 0) {
                row = LinearLayout(this)
                row.gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = Ui.dp(this, 12)
                emptyState.addView(row, lp)
            }
            val chip = Button(this)
            chip.text = chipLabels[i]
            chip.textSize = 13f
            chip.setTextColor(Ui.TEXT)
            chip.background = Ui.round(Ui.SURFACE, 20f)
            chip.isAllCaps = false
            chip.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8))
            chip.setOnClickListener {
                // v0.6.0: a chip fills the input — it does not send.
                input.setText(chipLabels[i])
                input.setSelection(input.text.length)
                input.requestFocus()
            }
            val clp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clp.marginStart = Ui.dp(this, 6)
            clp.marginEnd = Ui.dp(this, 6)
            row!!.addView(chip, clp)
        }
        content.addView(emptyState, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Input bar
        val bar2 = LinearLayout(this)
        bar2.orientation = LinearLayout.HORIZONTAL
        bar2.gravity = Gravity.BOTTOM
        bar2.setBackgroundColor(Ui.BG)
        bar2.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 8))

        chipsScroll = android.widget.HorizontalScrollView(this)
        chipsScroll.isHorizontalScrollBarEnabled = false
        chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        chipsScroll.addView(chipsRow)
        chipsScroll.visibility = View.GONE
        content.addView(chipsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(bar2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        input = EditText(this)
        input.hint = "Message LightChat…"
        input.setHintTextColor(Ui.DIM)
        input.setTextColor(Ui.TEXT)
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        input.background = Ui.round(Ui.INPUT_BG, 22f)
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12))
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.maxLines = 5
        // v0.6.0: Enter sends (soft keyboard shows a Send key; hardware
        // Enter sends too, Shift+Enter still makes a newline).
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                onSend(); true
            } else false
        }
        input.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN && !event.isShiftPressed) {
                onSend(); true
            } else false
        }
        input.gravity = Gravity.CENTER_VERTICAL
        val plus = TextView(this)
        plus.text = "＋"
        plus.textSize = 20f
        plus.setTextColor(Ui.TEXT)
        plus.gravity = Gravity.CENTER
        plus.background = Ui.round(Ui.INPUT_BG, 22f)
        plus.setOnClickListener {
            val items = arrayOf("Photo from gallery", "Take photo", "Attach file")
            android.app.AlertDialog.Builder(this).setTitle("Add attachment")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> {
                            val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
                            i.type = "image/*"
                            i.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                            i.addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            startActivityForResult(
                                android.content.Intent.createChooser(i, "Images"), REQ_IMAGES)
                        }
                        1 -> {
                            val pair = Attachments.newCameraFile(this)
                            camFile = pair.second
                            camId = pair.first
                            val uri = android.net.Uri.parse(
                                "content://bd.desertfox.lightchat.files/attachments/" + camFile!!.name)
                            val i = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                            i.clipData = android.content.ClipData.newRawUri("photo", uri)
                            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try { startActivityForResult(i, REQ_CAMERA) }
                            catch (e: Exception) { Ui.toast(this, "No camera app found") }
                        }
                        2 -> {
                            val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                            i.addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            i.type = "*/*"
                            startActivityForResult(i, REQ_FILE)
                        }
                    }
                }.setNegativeButton("Cancel", null).show()
        }
        val plp = LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 48))
        plp.marginEnd = Ui.dp(this, 6)
        bar2.addView(plus, plp)
        bar2.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        sendBtn = TextView(this)
        sendBtn.text = "➤"
        sendBtn.textSize = 18f
        // v0.6.0: white send button (ChatGPT-like) with a dark arrow.
        sendBtn.setTextColor(0xFF212121.toInt())
        sendBtn.gravity = Gravity.CENTER
        sendBtn.background = Ui.round(0xFFFFFFFF.toInt(), 26f)
        sendBtn.setOnClickListener { if (isStreaming()) stopStream() else onSend() }
        val slp = LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48))
        slp.marginStart = Ui.dp(this, 8)
        bar2.addView(sendBtn, slp)

        // Floating scroll-to-bottom button (P1-2): shown only when the user
        // has scrolled up while a reply is streaming.
        val sdb = TextView(this)
        sdb.text = "↓"
        sdb.textSize = 18f
        sdb.setTextColor(Ui.TEXT)
        sdb.gravity = Gravity.CENTER
        sdb.background = Ui.round(Ui.SURFACE, 20f)
        sdb.visibility = View.GONE
        sdb.setOnClickListener { scrollToBottom() }
        scrollDownBtn = sdb
        val sdbLp = FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40))
        sdbLp.gravity = Gravity.BOTTOM or Gravity.END
        sdbLp.marginEnd = Ui.dp(this, 16)
        sdbLp.bottomMargin = Ui.dp(this, 96)
        root.addView(sdb, sdbLp)

        // Sidebar
        scrim = View(this)
        scrim.setBackgroundColor(0xB3000000.toInt())
        scrim.visibility = View.GONE
        scrim.setOnClickListener { closeSidebar() }
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val panelWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        sidebarPanel = LinearLayout(this)
        sidebarPanel.orientation = LinearLayout.VERTICAL
        sidebarPanel.setBackgroundColor(Ui.SIDEBAR)
        root.addView(sidebarPanel, FrameLayout.LayoutParams(
            panelWidth, ViewGroup.LayoutParams.MATCH_PARENT))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 8))
        header.addView(Ui.tv(this, "LightChat", 18f, Ui.TEXT))
        sidebarPanel.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val newChat = Button(this)
        newChat.text = "＋ New chat"
        newChat.textSize = 14f
        newChat.setTextColor(Ui.TEXT)
        newChat.background = Ui.round(Ui.SURFACE, 10f)
        newChat.isAllCaps = false
        newChat.setOnClickListener { newChat() }
        val nlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.setMargins(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8))
        sidebarPanel.addView(newChat, nlp)

        chatsList = ListView(this)
        chatsList.divider = null
        chatsList.adapter = ChatsAdapter(this)
        sidebarPanel.addView(chatsList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val provBtn = sideButton("Providers")
        provBtn.setOnClickListener {
            closeSidebar()
            startActivity(android.content.Intent(this, ProvidersActivity::class.java))
        }
        sidebarPanel.addView(provBtn, bottomButtonLp())
        val setBtn = sideButton("Settings")
        setBtn.setOnClickListener {
            closeSidebar()
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        sidebarPanel.addView(setBtn, bottomButtonLp())
        val logBtn = sideButton("Logs")
        logBtn.setOnClickListener {
            closeSidebar()
            startActivity(android.content.Intent(this, LogsActivity::class.java))
        }
        sidebarPanel.addView(logBtn, bottomButtonLp())

        sidebarPanel.translationX = -panelWidth.toFloat()
    }

    private fun sideButton(label: String): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 15f
        b.setTextColor(Ui.TEXT)
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.background = null
        b.isAllCaps = false
        b.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12))
        return b
    }

    private fun bottomButtonLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, 0)
        return lp
    }

    // ---- chat rendering ----

    fun renderChat() {
        val c = conv
        if (c == null || c.messages.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            chatList.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            chatList.visibility = View.VISIBLE
            chatAdapter = ChatAdapter(this, c)
            chatList.adapter = chatAdapter
        }
        updateToolbar()
    }

    val pendingAtts = ArrayList<Att>()
    private var camFile: java.io.File? = null
    private var camId: String? = null
    private val REQ_IMAGES = 101
    private val REQ_CAMERA = 102
    private val REQ_FILE = 103
    private lateinit var chipsRow: LinearLayout
    private lateinit var chipsScroll: android.widget.HorizontalScrollView

    private fun updateToolbar() {
        val c = conv
        val p = if (c != null) Store.effectiveProvider(c) else Store.defaultProvider()
        val model = if (c != null && c.modelId.isNotBlank()) c.modelId
        else if (Store.settings.defaultModel.isNotBlank()) Store.settings.defaultModel else ""
        modelBtn.text = (p?.name ?: "No provider") + (if (model.isNotBlank()) " / " + model else "")
    }

    fun openConversation(c: Conversation) {
        if (isStreaming()) { Ui.toast(this, "Stop the current reply first"); return }
        pendingAtts.clear()
        refreshChips()
        conv = c
        closeSidebar()
        renderChat()
        chatList.post { chatList.setSelection((chatAdapter?.count ?: 1) - 1) }
        hideKeyboard()
    }

    fun newChat() {
        if (isStreaming()) { Ui.toast(this, "Stop the current reply first"); return }
        pendingAtts.clear()
        refreshChips()
        conv = null
        closeSidebar()
        renderChat()
        hideKeyboard()
    }

    fun chatMenu(c: Conversation) {
        val items = arrayOf("Rename", "Delete")
        android.app.AlertDialog.Builder(this)
            .setTitle(c.title.ifBlank { "(untitled)" })
            .setItems(items) { _, which ->
            if (which == 0) {
                Dialogs.input(this, "Rename chat", c.title) { t ->
                    if (t.isNotEmpty()) { c.title = t; Store.save(); refreshSidebar() }
                }
            } else {
                Dialogs.confirm(this, "Delete chat", "Delete \"" + c.title.ifBlank { "(untitled)" } + "\"?", "Delete") {
                    for (mm in c.messages) Attachments.deleteAtts(mm.atts)
                    Store.chats.remove(c)
                    if (conv === c) conv = null
                    Store.save()
                    renderChat()
                    refreshSidebar()
                }
            }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun refreshSidebar() {
        (chatsList.adapter as? ChatsAdapter)?.notifyDataSetChanged()
    }

    // ---- sidebar ----

    private fun openSidebar() {
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        sidebarPanel.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeSidebar() {
        scrim.animate().alpha(0f).setDuration(180).withEndAction { scrim.visibility = View.GONE }.start()
        sidebarPanel.animate().translationX(-sidebarPanel.width.toFloat()).setDuration(200).start()
    }

    override fun onBackPressed() {
        if (scrim.visibility == View.VISIBLE) closeSidebar() else super.onBackPressed()
    }

    private fun hideKeyboard() {
        try {
            val im = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            im.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (e: Exception) {}
    }

    // ---- attachments ----

    private fun urisFrom(data: android.content.Intent?): List<android.net.Uri> {
        val out = ArrayList<android.net.Uri>()
        val clip = data?.clipData
        if (clip != null) for (i in 0 until clip.itemCount) out.add(clip.getItemAt(i).uri)
        else data?.data?.let { out.add(it) }
        return out
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CAMERA) { camFile?.delete(); camFile = null }
            return
        }
        when (requestCode) {
            REQ_IMAGES -> importMany(urisFrom(data))
            REQ_FILE -> importMany(urisFrom(data))
            REQ_CAMERA -> {
                val f = camFile
                val id = camId
                camFile = null
                camId = null
                if (f == null) return
                if (!f.exists() || f.length() <= 0L) {
                    val bmp = data?.extras?.get("data") as? android.graphics.Bitmap
                    if (bmp != null) {
                        java.io.FileOutputStream(f).use {
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
                        }
                    }
                }
                if (f.exists() && f.length() > 0L) {
                    Thread {
                        val a = Attachments.registerCamera(this, id ?: "", f)
                        runOnUiThread { pendingAtts.add(a); refreshChips() }
                    }.start()
                }
            }
        }
    }

    private fun importMany(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        Thread {
            val got = ArrayList<Att>()
            for (u in uris) Attachments.importUri(this, u)?.let { got.add(it) }
            runOnUiThread {
                Store.log("attach imported: " + got.size + " file(s)")
                pendingAtts.addAll(got)
                refreshChips()
            }
        }.start()
    }

    private fun refreshChips() {
        chipsRow.removeAllViews()
        if (pendingAtts.isEmpty()) { chipsScroll.visibility = View.GONE; return }
        chipsScroll.visibility = View.VISIBLE
        for (a in pendingAtts) chipsRow.addView(chipView(a))
        chipsScroll.post { chipsScroll.fullScroll(android.view.View.FOCUS_RIGHT) }
    }

    private fun chipView(a: Att): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Ui.round(Ui.SURFACE, 12f)
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4))
        if (a.kind == "image") {
            val iv = android.widget.ImageView(this)
            iv.setImageBitmap(Attachments.thumb(a.path, 72))
            iv.setOnClickListener { viewImageDialog(a) }
            row.addView(iv, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)))
        } else {
            row.addView(Ui.tv(this, "\uD83D\uDCC4", 15f, Ui.TEXT))
        }
        val name = Ui.tv(this, a.name, 12f, Ui.TEXT)
        name.maxWidth = Ui.dp(this, 110)
        name.setSingleLine(true)
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        val nlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.marginStart = Ui.dp(this, 5)
        row.addView(name, nlp)
        val x = Ui.tv(this, "✕", 14f, Ui.DIM)
        x.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 2), 0)
        x.setOnClickListener { pendingAtts.remove(a); Attachments.deleteAtt(a); refreshChips() }
        row.addView(x)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = Ui.dp(this, 6)
        return row
    }

    fun viewImageDialog(a: Att) {
        val f = java.io.File(Attachments.dir(this), a.path)
        if (!f.exists()) { Ui.toast(this, "File missing"); return }
        viewFileImageDialog(a.name, f.absolutePath)
    }

    private fun decodeSample(path: String, maxPx: Int): android.graphics.Bitmap? {
        val opts = android.graphics.BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        android.graphics.BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0) return null
        var smp = 1
        while (Math.max(opts.outWidth, opts.outHeight) / (smp * 2) >= maxPx) smp *= 2
        opts.inSampleSize = smp
        opts.inJustDecodeBounds = false
        return android.graphics.BitmapFactory.decodeFile(path, opts)
    }

    fun viewFileImageDialog(name: String, absPath: String) {
        // v0.6.0: decode off the UI thread (was a blocking decode in the
        // dialog call). The dialog opens once the bitmap is ready.
        Thread {
            val bmp = decodeSample(absPath, 1600)
            runOnUiThread {
                if (bmp == null) { Ui.toast(this, "Could not decode image"); return@runOnUiThread }
                val iv = android.widget.ImageView(this)
                iv.setImageBitmap(bmp)
                iv.adjustViewBounds = true
                val mime = Attachments.mimeFromName(name).ifEmpty { "image/png" }
                val b = android.app.AlertDialog.Builder(this)
                b.setTitle(name).setView(iv)
                b.setPositiveButton("Close", null)
                b.setNeutralButton("Share") { _, _ -> shareFile(name, mime, absPath) }
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    b.setNegativeButton("Save") { _, _ -> saveImageFileToGallery(name, absPath) }
                }
                b.show()
            }
        }.start()
    }

    fun viewBitmapDialog(name: String, bmp: android.graphics.Bitmap) {
        try {
            val f = java.io.File(OutputKit.cacheDir(this), "share_" + System.currentTimeMillis() + ".png")
            java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, it) }
            viewFileImageDialog(name, f.absolutePath)
        } catch (e: Exception) { Ui.toast(this, "Could not show image") }
    }

    /** Full-size view of an image the model produced. */
    fun viewOutputImage(alt: String, url: String) {
        val nm = alt.ifBlank { "image" }
        if (url.startsWith("data:")) {
            // P0-6: decode off the UI thread (was a blocking full-res decode).
            OutputKit.loadDataUrl(this, url, 1600) { b ->
                if (b == null) Ui.toast(this, "Could not decode image")
                else viewBitmapDialog(nm, b)
            }
            return
        }
        val cached = OutputKit.cacheFile(this, url)
        if (cached.exists() && cached.length() > 0) {
            viewFileImageDialog(nm, cached.absolutePath)
            return
        }
        Ui.toast(this, "Loading image…")
        OutputKit.download(this, url) { f ->
            if (f == null) Ui.toast(this, "Image load failed")
            else viewFileImageDialog(nm, f.absolutePath)
        }
    }

    private fun saveImageFileToGallery(name: String, absPath: String) {
        if (android.os.Build.VERSION.SDK_INT < 29) { Ui.toast(this, "Needs Android 10+"); return }
        try {
            val values = android.content.ContentValues()
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,
                Attachments.mimeFromName(name).ifEmpty { "image/png" })
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LightChat")
            val u = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (u == null) { Ui.toast(this, "Save failed"); return }
            contentResolver.openOutputStream(u)?.use { os ->
                java.io.FileInputStream(absPath).use { it.copyTo(os) }
            }
            Ui.toast(this, "Saved to Pictures/LightChat")
        } catch (e: Exception) { Ui.toast(this, "Save failed: " + e.message) }
    }

    private fun saveImageToGallery(a: Att) {
        saveImageFileToGallery(a.name, java.io.File(Attachments.dir(this), a.path).absolutePath)
    }

    fun downloadAndOpen(url: String) {
        Ui.toast(this, "Downloading…")
        OutputKit.download(this, url) { f ->
            if (f == null) { Ui.toast(this, "Download failed (see Logs)"); return@download }
            val rel = f.absolutePath.removePrefix(filesDir.absolutePath + "/")
            val uri = android.net.Uri.parse("content://bd.desertfox.lightchat.files/" + rel)
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            i.setDataAndType(uri, Attachments.mimeFromName(f.name).ifEmpty { "application/octet-stream" })
            i.clipData = android.content.ClipData.newRawUri(f.name, uri)
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { startActivity(i) }
            catch (e: Exception) { Ui.toast(this, "Saved " + f.name + " — no app to open it") }
        }
    }

    fun saveCodeAsFile(code: String, lang: String) {
        // v0.6.0: the extension comes from the model's lang string — sanitise
        // it to [a-z0-9] (max 6) so "../" can never escape the downloads dir.
        val ext = when (lang.trim().lowercase()) {
            "kotlin", "kt" -> "kt"; "java" -> "java"; "python", "py" -> "py"
            "javascript", "js" -> "js"; "typescript", "ts" -> "ts"; "json" -> "json"
            "html" -> "html"; "css" -> "css"; "xml" -> "xml"
            "bash", "sh", "shell" -> "sh"; "c" -> "c"; "cpp", "c++" -> "cpp"
            "sql" -> "sql"; "markdown", "md" -> "md"; "yaml", "yml" -> "yml"
            "" -> "txt"
            else -> lang.trim().lowercase()
                .filter { it in 'a'..'z' || it in '0'..'9' }.take(6).ifEmpty { "txt" }
        }
        try {
            val f = java.io.File(OutputKit.downloadsDir(this),
                System.currentTimeMillis().toString() + "_snippet." + ext)
            f.writeText(code)
            Ui.toast(this, "Saved " + f.name)
            Store.log("code saved: " + f.name + " " + Attachments.sizeLabel(f.length()))
        } catch (e: Exception) { Ui.toast(this, "Save failed: " + e.message) }
    }

    fun regenerateFrom(m: Msg) {
        val c = conv ?: return
        if (isStreaming()) { Ui.toast(this, "Stop the current reply first"); return }
        val idx = c.messages.indexOf(m)
        if (idx >= 0) {
            while (c.messages.size > idx) c.messages.removeAt(idx)
            Store.touch(c); Store.save(); renderChat(); startStream()
        }
    }

    fun editFrom(m: Msg) {
        val c = conv ?: return
        if (isStreaming()) { Ui.toast(this, "Stop the current reply first"); return }
        val idx = c.messages.indexOf(m)
        if (idx >= 0) {
            val keep = ArrayList(m.atts)
            while (c.messages.size > idx) c.messages.removeAt(idx)
            input.setText(m.content)
            pendingAtts.clear()
            pendingAtts.addAll(keep)
            refreshChips()
            Store.touch(c); Store.save(); renderChat()
            input.requestFocus()
        }
    }

    fun messageMenu(m: Msg) {
        val c = conv ?: return
        val items = ArrayList<String>()
        items.add("Copy")
        items.add("Share")
        if (m.role == "assistant") items.add("Regenerate")
        if (m.role == "user") items.add("Edit")
        items.add("Delete")
        android.app.AlertDialog.Builder(this)
            .setTitle(if (m.role == "user") "Your message" else "Reply")
            .setItems(items.toTypedArray()) { _, w ->
                when (items[w]) {
                    "Copy" -> Ui.copy(this, m.content)
                    "Share" -> {
                        val i = android.content.Intent(android.content.Intent.ACTION_SEND)
                        i.type = "text/plain"
                        i.putExtra(android.content.Intent.EXTRA_TEXT, m.content)
                        try { startActivity(android.content.Intent.createChooser(i, "Share")) }
                        catch (e: Exception) {}
                    }
                    "Regenerate" -> regenerateFrom(m)
                    "Edit" -> editFrom(m)
                    "Delete" -> {
                        if (isStreaming()) { Ui.toast(this, "Stop the current reply first"); return@setItems }
                        Attachments.deleteAtts(m.atts)
                        c.messages.remove(m)
                        Store.touch(c); Store.save(); renderChat()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }



    private fun shareAtt(a: Att) {
        shareFile(a.name, a.mime, java.io.File(Attachments.dir(this), a.path).absolutePath)
    }

    private fun shareFile(name: String, mime: String, absPath: String) {
        val rel = absPath.removePrefix(filesDir.absolutePath + "/")
        val uri = android.net.Uri.parse("content://bd.desertfox.lightchat.files/" + rel)
        val i = android.content.Intent(android.content.Intent.ACTION_SEND)
        i.type = mime
        i.putExtra(android.content.Intent.EXTRA_STREAM, uri)
        i.clipData = android.content.ClipData.newRawUri(name, uri)
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(android.content.Intent.createChooser(i, "Share " + name)) }
        catch (e: Exception) { Ui.toast(this, "No app to share with") }
    }

    // ---- sending / streaming ----

    private fun onSend() {
        val t = input.text.toString().trim()
        if ((t.isEmpty() && pendingAtts.isEmpty()) || isStreaming()) return
        input.setText("")
        hideKeyboard()
        if (conv == null) conv = Store.newConversation()
        val c = conv!!
        if (c.title.isBlank()) {
            c.title = t.take(40).ifBlank { pendingAtts.firstOrNull()?.name?.take(40) ?: "Chat" }
            refreshSidebar()
        }
        val m = Msg("user", t, System.currentTimeMillis())
        if (pendingAtts.isNotEmpty()) {
            m.atts.addAll(pendingAtts)
            pendingAtts.clear()
            refreshChips()
            Store.log("send with " + m.atts.size + " attachment(s)")
        }
        c.messages.add(m)
        Store.touch(c)
        renderChat()
        startStream()
    }

    private fun startStream() {
        val c = conv ?: return
        val p = Store.effectiveProvider(c)
        val model = if (c.modelId.isNotBlank()) c.modelId else Store.settings.defaultModel
        if (p == null) { failStream("Add a provider first (Providers in the sidebar)"); return }
        if (model.isBlank()) { failStream("Select a model first (tap the name at the top)"); return }
        if (p.models.isNotEmpty() && p.models.none { it == model }) {
            // model not in the fetched list — still allow, providers may accept any id
        }
        val placeholder = Msg("assistant", "", System.currentTimeMillis())
        c.messages.add(placeholder)
        Store.touch(c)
        renderChat()
        scrollToBottom()
        setStreamingUI(true)
        val f = StopFlag()
        flag = f
        val history = c.messages.filter { it !== placeholder && it.role == "user" || it !== placeholder && it.role == "assistant" }
        Thread {
            // P0-1: the delta piece used to be ignored (onDelta = { scheduleUi() }),
            // so the placeholder stayed blank until the whole reply arrived.
            // Append each piece on the UI thread, then repaint only the last row.
            ChatClient.streamChat(p, model, Store.settings.systemPrompt, history, f,
                onDelta = { piece ->
                    uiHandler.post {
                        placeholder.content += piece
                        scheduleUi()
                    }
                },
                onDone = { full, tokens -> runOnUiThread { doneStream(full, tokens, placeholder) } },
                onError = { msg, partial -> runOnUiThread { failStream(msg, partial) } })
        }.start()
    }

    private fun setStreamingUI(streaming: Boolean) {
        sendBtn.text = if (streaming) "■" else "➤"
        if (streaming) {
            sendBtn.background = Ui.round(Ui.ERROR, 26f)
            sendBtn.setTextColor(android.graphics.Color.WHITE)
        } else {
            sendBtn.background = Ui.round(0xFFFFFFFF.toInt(), 26f)
            sendBtn.setTextColor(0xFF212121.toInt())
        }
        chatAdapter?.notifyDataSetChanged()
    }

    private fun doneStream(full: String, tokens: Int, placeholder: Msg) {
        val c = conv ?: return
        flag = null
        setStreamingUI(false)
        Store.log("chat ok — " + (if (tokens > 0) tokens.toString() + " tokens" else "no token count"))
        if (full.isBlank() && tokens < 0) {
            c.messages.remove(placeholder)
        } else {
            placeholder.content = full
            if (tokens > 0) placeholder.tokens = tokens
        }
        Store.touch(c)
        Store.save()
        chatAdapter?.notifyDataSetChanged()
        scrollToBottom()
    }

    private fun failStream(msg: String, partial: String = "") {
        Store.log("chat error — " + msg)
        val c = conv ?: return
        flag = null
        setStreamingUI(false)
        // P1-5: keep any partial reply text instead of discarding it. The
        // placeholder holds the streamed text already (P0-1 appends deltas);
        // if the error carried partial text, use that. Only a blank
        // placeholder is dropped, and the error row goes below the partial.
        val last = c.messages.lastOrNull()
        if (last != null && last.role == "assistant") {
            if (partial.isNotBlank()) last.content = partial
            if (last.content.isBlank()) c.messages.removeAt(c.messages.size - 1)
        }
        c.messages.add(Msg("error", msg, System.currentTimeMillis()))
        Store.touch(c)
        Store.save()
        chatAdapter?.notifyDataSetChanged()
        scrollToBottom()
    }

    fun stopStream() {
        flag?.stop()
    }

    fun retryLast() {
        if (isStreaming()) return
        val c = conv ?: return
        while (c.messages.isNotEmpty() && c.messages.last().role == "error") {
            c.messages.removeAt(c.messages.size - 1)
        }
        if (c.messages.isNotEmpty() && c.messages.last().role == "assistant" &&
            c.messages.last().content.isBlank()) c.messages.removeAt(c.messages.size - 1)
        if (c.messages.none { it.role == "user" }) { renderChat(); return }
        Store.touch(c)
        renderChat()
        startStream()
    }

    private fun scrollToBottom() {
        scrollDownBtn?.visibility = View.GONE
        chatList.post { chatList.setSelection((chatAdapter?.count ?: 1) - 1) }
    }

    private fun scheduleUi() {
        uiHandler.post {
            val now = System.currentTimeMillis()
            if (now - lastPaint >= 150) {
                lastPaint = now
                paintStream()
            } else if (!pendingPaint) {
                pendingPaint = true
                uiHandler.postDelayed({
                    pendingPaint = false
                    lastPaint = System.currentTimeMillis()
                    paintStream()
                }, 150)
            }
        }
    }

    // P0-1 / P1-2: repaint only the last (streaming) row in place instead of
    // notifyDataSetChanged() on every tick, and auto-scroll only if the list
    // was already at the bottom — otherwise show the floating ↓ button.
    private fun paintStream() {
        val adapter = chatAdapter ?: return
        val lastPos = adapter.count - 1
        if (lastPos < 0) return
        val first = chatList.firstVisiblePosition
        val last = chatList.lastVisiblePosition
        val atBottom = last >= lastPos
        if (lastPos in first..last) {
            val row = chatList.getChildAt(lastPos - first)
            adapter.getView(lastPos, row, chatList)
        }
        if (isStreaming()) {
            if (atBottom) scrollToBottom()
            else scrollDownBtn?.visibility = View.VISIBLE
        } else {
            scrollDownBtn?.visibility = View.GONE
        }
    }

    // ---- model picker ----

    private fun openModelPicker() {
        // v0.6.0: switching model mid-stream is blocked, like opening
        // another chat mid-stream.
        if (isStreaming()) { Ui.toast(this, "Stop the current reply first"); return }
        val c = conv
        val current = if (c != null && c.modelId.isNotBlank()) c.modelId
        else Store.settings.defaultModel
        Dialogs.modelPicker(this, current) { p, model -> applyModel(p, model) }
    }

    private fun applyModel(p: Provider, model: String) {
        // v0.6.0: remember the last-used model — the picker shows it first.
        Store.settings.lastUsedModel = model
        val c = conv
        if (c == null) {
            Store.settings.defaultProviderId = p.id
            Store.settings.defaultModel = model
            Store.save()
            updateToolbar()
            return
        }
        val changed = c.providerId != p.id || c.modelId != model
        c.providerId = p.id
        c.modelId = model
        if (c.messages.isNotEmpty() && changed) {
            c.messages.add(Msg("note", "Switched to " + p.name + " / " + model, System.currentTimeMillis()))
        }
        if (c.messages.isEmpty()) {
            Store.settings.defaultProviderId = p.id
            Store.settings.defaultModel = model
        }
        Store.touch(c)
        Store.save()
        chatAdapter?.notifyDataSetChanged()
        updateToolbar()
    }

    override fun onResume() {
        super.onResume()
        TraceLog.log("app", "onResume")
        refreshSidebar()
        chatAdapter?.notifyDataSetChanged()
        updateToolbar()
    }

    override fun onPause() {
        super.onPause()
        Store.saveNow()
    }
}
