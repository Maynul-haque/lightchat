package bd.desertfox.lightchat

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var sysEt: EditText
    private lateinit var defaultBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        setContentView(scroll)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 24))
        scroll.addView(root, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.tv(this, "←", 20f, Ui.TEXT)
        back.setPadding(0, 0, Ui.dp(this, 14), 0)
        back.setOnClickListener { finish() }
        bar.addView(back)
        bar.addView(Ui.tv(this, "Settings", 18f, Ui.TEXT))
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun label(text: String): TextView {
            val t = Ui.tv(this, text, 13f, Ui.DIM)
            t.typeface = Typeface.DEFAULT_BOLD
            t.isAllCaps = true
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = Ui.dp(this, 22)
            root.addView(t, lp)
            return t
        }

        label("System prompt")
        sysEt = EditText(this)
        sysEt.setText(Store.settings.systemPrompt)
        sysEt.setTextColor(Ui.TEXT)
        sysEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        sysEt.background = Ui.round(Ui.INPUT_BG, 10f)
        sysEt.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11))
        sysEt.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        sysEt.minLines = 3
        sysEt.gravity = Gravity.TOP
        root.addView(sysEt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val save = Button(this)
        save.text = "Save system prompt"
        save.textSize = 13f
        save.setTextColor(Ui.TEXT)
        save.background = Ui.round(Ui.ACCENT, 10f)
        save.isAllCaps = false
        save.setOnClickListener {
            Store.settings.systemPrompt = sysEt.text.toString()
            Store.save()
            Ui.toast(this, "Saved")
        }
        val slp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = Ui.dp(this, 8)
        root.addView(save, slp)

        label("Default provider / model")
        defaultBtn = Button(this)
        defaultBtn.textSize = 14f
        defaultBtn.setTextColor(Ui.TEXT)
        defaultBtn.background = Ui.round(Ui.SURFACE, 10f)
        defaultBtn.isAllCaps = false
        defaultBtn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        defaultBtn.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12))
        defaultBtn.setOnClickListener {
            Dialogs.modelPicker(this) { p, m ->
                Store.settings.defaultProviderId = p.id
                Store.settings.defaultModel = m
                Store.save()
                refreshDefault()
            }
        }
        root.addView(defaultBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        label("About")
        root.addView(Ui.tv(this, "LightChat 0.4.0", 14f, Ui.TEXT))
        root.addView(Ui.tv(this, "bd.desertfox.lightchat", 12f, Ui.DIM))
        val note = Ui.tv(this,
            "No data leaves the phone except to your providers. Very long chats cost many tokens.",
            12f, Ui.DIM)
        note.setPadding(0, Ui.dp(this, 6), 0, 0)
        root.addView(note)
        refreshDefault()
    }

    private fun refreshDefault() {
        val p = Store.defaultProvider()
        val model = Store.settings.defaultModel
        defaultBtn.text = (p?.name ?: "No default") +
            (if (model.isNotBlank()) " / " + model else " / (no model)")
    }

    override fun onPause() {
        super.onPause()
        Store.saveNow()
    }
}
