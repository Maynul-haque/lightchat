package bd.desertfox.lightchat

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

class ProvidersActivity : Activity() {

    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        setContentView(root)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Ui.SURFACE)
        val back = Ui.tv(this, "←", 20f, Ui.TEXT)
        back.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0)
        back.setOnClickListener { finish() }
        bar.addView(back)
        bar.addView(Ui.tv(this, "Providers", 18f, Ui.TEXT),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val add = Button(this)
        add.text = "＋ Add"
        add.textSize = 13f
        add.setTextColor(Ui.TEXT)
        add.background = Ui.round(0xFF2A2E38.toInt(), 10f)
        add.isAllCaps = false
        add.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4))
        add.setOnClickListener { editProvider(null) }
        bar.addView(add, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))

        list = ListView(this)
        list.divider = null
        list.adapter = ProvidersAdapter(this)
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (Store.providers.isEmpty()) {
            val hint = Ui.tv(this, "No providers yet. Tap ＋ Add.\n\nAn OpenAI-compatible base URL works, e.g. https://api.openai.com/v1", 13f, Ui.DIM)
            hint.gravity = Gravity.CENTER
            hint.setPadding(Ui.dp(this, 24), Ui.dp(this, 40), Ui.dp(this, 24), 0)
            root.addView(hint, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    fun refresh() {
        (list.adapter as? ProvidersAdapter)?.notifyDataSetChanged()
    }

    fun deleteProvider(p: Provider) {
        Dialogs.confirm(this, "Delete provider",
            "Delete \"" + p.name + "\"? Chats using it fall back to the default provider.", "Delete") {
            Store.providers.remove(p)
            Store.save()
            refresh()
        }
    }

    fun editProvider(existing: Provider?) {
        val localModels = ArrayList<String>()
        if (existing != null) localModels.addAll(existing.models)
        val allowedSel = HashSet<String>()
        if (existing != null) allowedSel.addAll(existing.allowed)
        var fetchedOrTested = existing != null && existing.models.isNotEmpty()
        var lastTestFailed = false

        val scroll = ScrollView(this)
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 4))
        scroll.addView(form)

        fun field(hint: String, prefill: String, password: Boolean, into: LinearLayout = form): EditText {
            val et = EditText(this)
            et.hint = hint
            et.setHintTextColor(Ui.DIM)
            et.setText(prefill)
            et.setTextColor(Ui.TEXT)
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            et.background = Ui.round(Ui.INPUT_BG, 10f)
            et.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11))
            if (password) et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (password) et.transformationMethod = PasswordTransformationMethod.getInstance()
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = Ui.dp(this, 8)
            into.addView(et, lp)
            return et
        }

        val nameEt = field("Name", existing?.name ?: "", false)
        val urlEt = field("Base URL (…/v1)", existing?.baseUrl ?: "", false)
        val keyRow = LinearLayout(this)
        keyRow.orientation = LinearLayout.HORIZONTAL
        keyRow.gravity = Gravity.CENTER_VERTICAL
        val keyEt = field("API key (optional)", existing?.apiKey ?: "", true, keyRow)
        val show = Button(this)
        show.text = "Show"
        show.textSize = 12f
        show.setTextColor(Ui.TEXT)
        show.background = Ui.round(Ui.SURFACE, 8f)
        show.isAllCaps = false
        show.setOnClickListener {
            if (keyEt.transformationMethod == null) {
                keyEt.transformationMethod = PasswordTransformationMethod.getInstance()
                show.text = "Show"
            } else {
                keyEt.transformationMethod = null
                show.text = "Hide"
            }
        }
        keyRow.addView(show, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(keyRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Per-provider custom headers, one "Name: value" per line (blank = none).
        // Used for gateways that need extra headers, e.g. OpenRouter's
        // HTTP-Referer / X-Title. Authorization / User-Agent can never be set here.
        val headersEt = EditText(this)
        headersEt.hint = "Custom headers (optional)\nHTTP-Referer: https://example.com\nX-Title: LightChat"
        headersEt.setHintTextColor(Ui.DIM)
        headersEt.setText(existing?.customHeaders?.entries?.joinToString("\n") { it.key + ": " + it.value } ?: "")
        headersEt.setTextColor(Ui.TEXT)
        headersEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        headersEt.background = Ui.round(Ui.INPUT_BG, 10f)
        headersEt.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11))
        headersEt.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        headersEt.minLines = 3
        headersEt.gravity = Gravity.TOP
        val hlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = Ui.dp(this, 8)
        form.addView(headersEt, hlp)

        /** Parse "Name: value" lines; blank lines and lines without a colon are ignored. */
        fun parseHeaders(text: String): LinkedHashMap<String, String> {
            val out = LinkedHashMap<String, String>()
            for (line in text.split("\n")) {
                val t = line.trim()
                if (t.isEmpty()) continue
                val i = t.indexOf(':')
                if (i <= 0) continue
                val n = t.substring(0, i).trim()
                val v = t.substring(i + 1).trim()
                if (n.isEmpty() || v.isEmpty()) continue
                out[n] = v
            }
            return out
        }

        val status = Ui.tv(this, "", 13f, Ui.DIM)
        status.setPadding(0, Ui.dp(this, 10), 0, 0)

        val modelsTv = Ui.tv(this, "", 12f, Ui.DIM)
        fun refreshModelsTv() {
            modelsTv.text = if (localModels.isEmpty()) "No models yet"
            else localModels.size.toString() + " models: " +
                localModels.take(8).joinToString(", ") + (if (localModels.size > 8) " …" else "")
        }
        refreshModelsTv()

        val manualRow = LinearLayout(this)
        manualRow.orientation = LinearLayout.HORIZONTAL
        manualRow.gravity = Gravity.CENTER_VERTICAL
        val manualEt = EditText(this)
        manualEt.hint = "Add model id manually"
        manualEt.setHintTextColor(Ui.DIM)
        manualEt.setTextColor(Ui.TEXT)
        manualEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        manualEt.background = Ui.round(Ui.INPUT_BG, 10f)
        manualEt.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11))
        manualEt.inputType = InputType.TYPE_CLASS_TEXT
        manualRow.addView(manualEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val addModel = Button(this)
        addModel.text = "Add"
        addModel.textSize = 12f
        addModel.setTextColor(Ui.TEXT)
        addModel.background = Ui.round(Ui.SURFACE, 8f)
        addModel.isAllCaps = false
        addModel.setOnClickListener {
            val id = manualEt.text.toString().trim()
            if (id.isNotEmpty() && localModels.none { it == id }) {
                localModels.add(id)
                manualEt.setText("")
                refreshModelsTv()
            }
        }
        manualRow.addView(addModel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(manualRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = Gravity.CENTER_VERTICAL

        fun pill(label: String): Button {
            val b = Button(this)
            b.text = label
            b.textSize = 12f
            b.setTextColor(Ui.TEXT)
            b.background = Ui.round(Ui.SURFACE, 10f)
            b.isAllCaps = false
            b.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = Ui.dp(this, 8)
            btnRow.addView(b, lp)
            return b
        }

        val fetchBtn = pill("Fetch models")
        val testBtn = pill("Test")
        val allowBtn = pill("Allowed")
        btnRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        form.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(modelsTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun tempProvider(): Provider = Provider(
            existing?.id ?: "tmp", nameEt.text.toString().trim(),
            urlEt.text.toString().trim(), keyEt.text.toString(), ArrayList(localModels),
            customHeaders = parseHeaders(headersEt.text.toString()))

        fetchBtn.setOnClickListener {
            val url = urlEt.text.toString().trim()
            if (url.isEmpty()) { status.text = "Enter a base URL first"; status.setTextColor(Ui.ERROR); return@setOnClickListener }
            status.text = "Fetching…"; status.setTextColor(Ui.DIM)
            val probe = tempProvider()
            Thread {
                try {
                    val models = ChatClient.fetchModels(probe)
                    runOnUiThread {
                        localModels.clear()
                        localModels.addAll(models)
                        refreshModelsTv()
                        fetchedOrTested = true
                        Store.log("fetch models ok: " + models.size + " (" + probe.name + ")")
                        status.text = models.size.toString() + " models found"
                        status.setTextColor(0xFF7ED4A6.toInt())
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Store.log("fetch models failed (" + probe.name + "): " + (e.message ?: "error"))
                        status.text = "Fetch failed: " + (e.message ?: "error")
                        status.setTextColor(Ui.ERROR)
                        Ui.toast(this, "Fetch failed")
                    }
                }
            }.start()
        }

        testBtn.setOnClickListener {
            val model = localModels.firstOrNull() ?: existing?.models?.firstOrNull()
            if (model == null) {
                status.text = "Fetch models first — Test needs a model"
                status.setTextColor(Ui.ERROR)
                return@setOnClickListener
            }
            status.text = "Testing…"; status.setTextColor(Ui.DIM)
            val probe = tempProvider()
            Thread {
                val r = ChatClient.testProvider(probe, model)
                runOnUiThread {
                    fetchedOrTested = true
                    lastTestFailed = !r.ok
                    Store.log("test " + (if (r.ok) "pass" else "fail") + " (" + probe.name + " / " + model + "): " + r.detail)
                    status.text = (if (r.ok) "Pass — " else "Fail — ") + r.detail
                    status.setTextColor(if (r.ok) 0xFF7ED4A6.toInt() else Ui.ERROR)
                }
            }.start()
        }

        allowBtn.setOnClickListener {
            if (localModels.isEmpty()) {
                status.text = "Fetch models first — the list is built from them"
                status.setTextColor(Ui.ERROR)
                return@setOnClickListener
            }
            val sel = HashSet(allowedSel)
            val shown = ArrayList<String>()
            val lv = ListView(this)
            lv.divider = null
            lv.adapter = object : android.widget.BaseAdapter() {
                override fun getCount(): Int = shown.size
                override fun getItem(pos: Int): String = shown[pos]
                override fun getItemId(pos: Int): Long = pos.toLong()
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                    val cb = cv as? android.widget.CheckBox ?: android.widget.CheckBox(this@ProvidersActivity)
                    cb.setOnCheckedChangeListener(null)
                    cb.text = shown[pos]
                    cb.textSize = 14f
                    cb.setTextColor(Ui.TEXT)
                    cb.isChecked = sel.contains(shown[pos])
                    cb.setOnCheckedChangeListener { _, c ->
                        if (c) sel.add(shown[pos]) else sel.remove(shown[pos])
                    }
                    cb.setPadding(Ui.dp(this@ProvidersActivity, 6), Ui.dp(this@ProvidersActivity, 10),
                        Ui.dp(this@ProvidersActivity, 6), Ui.dp(this@ProvidersActivity, 10))
                    return cb
                }
            }
            val search = EditText(this)
            search.hint = "Search models…"
            search.setHintTextColor(Ui.DIM)
            search.setTextColor(Ui.TEXT)
            search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            search.background = Ui.round(Ui.INPUT_BG, 10f)
            search.isSingleLine = true
            search.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10))
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    shown.clear()
                    val t = (s?.toString() ?: "").trim().lowercase()
                    for (m in localModels) if (t.isEmpty() || m.lowercase().contains(t)) shown.add(m)
                    (lv.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                    if (shown.isNotEmpty()) lv.setSelection(0)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            for (m in localModels) shown.add(m)
            val wrap = LinearLayout(this)
            wrap.orientation = LinearLayout.VERTICAL
            val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            slp.setMargins(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8))
            wrap.addView(search, slp)
            wrap.addView(lv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val ad = AlertDialog.Builder(this)
                .setTitle("Allowed models (" + localModels.size + ")")
                .setView(wrap)
                .setPositiveButton("Save") { _, _ ->
                    allowedSel.clear(); allowedSel.addAll(sel)
                    status.text = if (allowedSel.isEmpty()) "All models allowed"
                    else allowedSel.size.toString() + " models allowed"
                    status.setTextColor(Ui.DIM)
                }
                .setNeutralButton("Allow all") { _, _ ->
                    allowedSel.clear()
                    status.text = "All models allowed"
                    status.setTextColor(Ui.DIM)
                }
                .setNegativeButton("Cancel", null)
                .create()
            ad.show()
            ad.window?.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt(),
                (resources.displayMetrics.heightPixels * 0.72f).toInt())
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add provider" else "Edit provider")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameEt.text.toString().trim()
            val url = urlEt.text.toString().trim()
            if (name.isEmpty() || url.isEmpty()) {
                status.text = "Name and base URL are required"
                status.setTextColor(Ui.ERROR)
                return@setOnClickListener
            }
            fun doSave() {
                val headers = parseHeaders(headersEt.text.toString())
                if (existing == null) {
                    Store.providers.add(Provider(UUID.randomUUID().toString(), name, url,
                        keyEt.text.toString(), ArrayList(localModels), true, HashSet(allowedSel),
                        LinkedHashMap(headers)))
                } else {
                    existing.name = name
                    existing.baseUrl = url
                    existing.apiKey = keyEt.text.toString()
                    existing.models.clear()
                    existing.models.addAll(localModels)
                    existing.allowed.clear()
                    existing.allowed.addAll(allowedSel)
                    existing.customHeaders.clear()
                    existing.customHeaders.putAll(headers)
                }
                Store.save()
                refresh()
                if (headers.isNotEmpty()) {
                    status.text = headers.size.toString() + " custom header(s) saved"
                    status.setTextColor(Ui.DIM)
                }
                dlg.dismiss()
                Ui.toast(this, if (headers.isNotEmpty()) "Saved (" + headers.size + " custom header(s))" else "Saved")
            }
            if (lastTestFailed) {
                Dialogs.confirm(this, "Test failed",
                    "The last test failed. Save the provider anyway?", "Save anyway") { doSave() }
            } else if (!fetchedOrTested) {
                Dialogs.confirm(this, "Not tested",
                    "No Fetch models or Test was run for this provider. Save it anyway?", "Save anyway") { doSave() }
            } else {
                doSave()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
