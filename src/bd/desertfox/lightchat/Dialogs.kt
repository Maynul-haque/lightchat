package bd.desertfox.lightchat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.view.Gravity
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

object Dialogs {

    fun confirm(act: Activity, title: String, msg: String, yesLabel: String, onYes: () -> Unit) {
        AlertDialog.Builder(act).setTitle(title).setMessage(msg)
            .setPositiveButton(yesLabel) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun input(act: Activity, title: String, prefill: String, onOk: (String) -> Unit) {
        val et = EditText(act)
        et.setText(prefill)
        et.setPadding(Ui.dp(act, 20), Ui.dp(act, 12), Ui.dp(act, 20), Ui.dp(act, 12))
        AlertDialog.Builder(act).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Model picker grouped by provider. Model tap -> onPick. "Fetch models first" opens Providers. */
    fun modelPicker(act: Activity, onPick: (Provider, String) -> Unit) {
        var dlgRef: AlertDialog? = null
        // rows: provider header (0) / model (1) / fetch-hint (2)
        class Row(val type: Int, val text: String, val provider: Provider?, val model: String?)

        val rows = ArrayList<Row>()
        var selProv: Provider? = null
        var query = ""

        fun buildRows() {
            rows.clear()
            val t = query.trim().lowercase()
            val targets = if (selProv != null) listOf<Provider>(selProv!!)
            else Store.providers.filter { it.enabled }
            for (p in targets) {
                val visible = ArrayList<String>()
                for (m in p.models) {
                    if (p.allowed.isNotEmpty() && !p.allowed.contains(m)) continue
                    if (t.isNotEmpty() && !m.lowercase().contains(t)) continue
                    visible.add(m)
                }
                if (selProv == null && t.isNotEmpty() && visible.isEmpty()) continue
                rows.add(Row(0, p.name, p, null))
                if (p.models.isEmpty()) rows.add(Row(2, "Fetch models first", p, null))
                else if (visible.isEmpty()) rows.add(Row(2, "No allowed models — pick them in Providers", p, null))
                else for (m in visible) rows.add(Row(1, m, p, m))
            }
        }
        buildRows()

        val search = EditText(act)
        search.hint = "Search models…"
        search.setHintTextColor(Ui.DIM)
        search.setTextColor(Ui.TEXT)
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        search.background = Ui.round(Ui.INPUT_BG, 10f)
        search.isSingleLine = true
        search.setPadding(Ui.dp(act, 14), Ui.dp(act, 10), Ui.dp(act, 14), Ui.dp(act, 10))

        val list = ListView(act)
        list.divider = null
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Row = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getViewTypeCount(): Int = 1
            override fun getItemViewType(position: Int): Int = 0
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val r = rows[position]
                val box = LinearLayout(act)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding(Ui.dp(act, 20), Ui.dp(act, 9), Ui.dp(act, 20), Ui.dp(act, 9))
                if (r.type == 0) {
                    val t = Ui.tv(act, r.text, 12f, Ui.DIM)
                    t.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    box.addView(t)
                    // provider name opens the Providers screen
                    box.setOnClickListener {
                        act.startActivity(Intent(act, ProvidersActivity::class.java))
                        dlgRef?.dismiss()
                    }
                } else {
                    box.addView(Ui.tv(act, r.text, 15f, Ui.TEXT))
                    box.setOnClickListener {
                        if (r.type == 1 && r.provider != null && r.model != null) {
                            onPick(r.provider, r.model)
                            dlgRef?.dismiss()
                        } else if (r.type == 2) {
                            act.startActivity(Intent(act, ProvidersActivity::class.java))
                            dlgRef?.dismiss()
                        }
                    }
                }
                return box
            }
        }
        list.adapter = adapter

        fun refresh() {
            buildRows()
            adapter.notifyDataSetChanged()
            if (rows.isNotEmpty()) list.setSelection(0)
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                query = s?.toString() ?: ""
                refresh()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // provider filter chips, horizontally scrollable (RikkaHub style)
        val chipRow = LinearLayout(act)
        chipRow.orientation = LinearLayout.HORIZONTAL
        val hscroll = HorizontalScrollView(act)
        hscroll.isHorizontalScrollBarEnabled = false
        hscroll.addView(chipRow, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        fun buildChips() {
            chipRow.removeAllViews()
            fun chip(label: String, sel: Boolean, onClick: () -> Unit) {
                val c = TextView(act)
                c.text = label
                c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                c.setPadding(Ui.dp(act, 14), Ui.dp(act, 7), Ui.dp(act, 14), Ui.dp(act, 7))
                c.background = Ui.round(if (sel) Ui.ACCENT else Ui.SURFACE, 20f)
                c.setTextColor(if (sel) 0xFF171A20.toInt() else Ui.TEXT)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = Ui.dp(act, 8)
                chipRow.addView(c, lp)
                c.setOnClickListener { onClick() }
            }
            chip("All", selProv == null) { selProv = null; buildChips(); refresh() }
            for (p in Store.providers) {
                if (!p.enabled) continue
                chip(p.name, selProv === p) { selProv = p; buildChips(); refresh() }
            }
        }
        buildChips()

        val wrap = LinearLayout(act)
        wrap.orientation = LinearLayout.VERTICAL
        val slp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.setMargins(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8))
        wrap.addView(search, slp)
        val hlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.setMargins(Ui.dp(act, 12), 0, Ui.dp(act, 12), Ui.dp(act, 6))
        wrap.addView(hscroll, hlp)
        wrap.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val dlg = AlertDialog.Builder(act).setTitle("Select model").setView(wrap)
            .setNegativeButton("Cancel", null).create()
        dlgRef = dlg
        dlg.show()
        dlg.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.85f).toInt(),
            (act.resources.displayMetrics.heightPixels * 0.72f).toInt())
    }

    fun toastShort(act: Activity, s: String) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
    }
}
