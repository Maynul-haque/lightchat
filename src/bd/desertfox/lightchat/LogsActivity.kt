package bd.desertfox.lightchat

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LogsActivity : Activity() {

    private lateinit var body: TextView

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
        bar.addView(Ui.tv(this, "Logs", 18f, Ui.TEXT),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val copy = Button(this)
        copy.text = "Copy all"
        copy.textSize = 12f
        copy.setTextColor(Ui.TEXT)
        copy.background = Ui.round(0xFF2A2E38.toInt(), 10f)
        copy.isAllCaps = false
        copy.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4))
        copy.setOnClickListener { Ui.copy(this, body.text.toString()) }
        bar.addView(copy)
        val clear = Button(this)
        clear.text = "Clear"
        clear.textSize = 12f
        clear.setTextColor(Ui.TEXT)
        clear.background = Ui.round(0xFF2A2E38.toInt(), 10f)
        clear.isAllCaps = false
        clear.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4))
        val clp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clp.marginStart = Ui.dp(this, 6)
        clp.marginEnd = Ui.dp(this, 10)
        clear.setOnClickListener {
            Store.clearCrashLog()
            synchronized(Store.logLines) { Store.logLines.clear() }
            Store.save()
            refresh()
        }
        bar.addView(clear, clp)
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))

        val scroll = ScrollView(this)
        body = TextView(this)
        body.typeface = Typeface.MONOSPACE
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        body.setTextColor(Ui.TEXT)
        body.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12))
        body.setTextIsSelectable(true)
        scroll.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    private fun refresh() {
        val sb = StringBuilder()
        val crash = Store.crashLog()
        if (crash.isNotBlank()) {
            sb.append("=== LAST CRASH ===").append('\n').append(crash).append("\n\n")
        }
        synchronized(Store.logLines) {
            if (Store.logLines.isEmpty()) sb.append("(no log entries)")
            else for (l in Store.logLines) sb.append(l).append('\n')
        }
        body.text = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
