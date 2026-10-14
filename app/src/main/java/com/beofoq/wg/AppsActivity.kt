package com.beofoq.wg

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

class AppsActivity : BaseActivity() {
    private data class AppRow(val pkg: String, val label: String, val icon: Drawable?)

    private lateinit var llApps: LinearLayout
    private lateinit var tvCount: TextView
    private var apps: List<AppRow> = emptyList()
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        llApps = findViewById(R.id.llApps)
        tvCount = findViewById(R.id.tvCount)
        findViewById<View>(R.id.btnBackApps).setOnClickListener { finish() }
        selected.addAll(Store.splitApps(this))
        updateCount()

        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                render(s?.toString() ?: "")
            }
        })

        thread {
            val pm = packageManager
            val list = mutableListOf<AppRow>()
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (info: ApplicationInfo in installed) {
                if (info.packageName == packageName) continue
                val label = pm.getApplicationLabel(info).toString()
                val icon = try {
                    pm.getApplicationIcon(info)
                } catch (e: Exception) {
                    null
                }
                list.add(AppRow(info.packageName, label, icon))
            }
            list.sortBy { it.label.lowercase() }
            apps = list
            runOnUiThread { render("") }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateCount() {
        tvCount.text = String.format(getString(R.string.selected_count), selected.size)
    }

    private fun render(filter: String) {
        if (!::llApps.isInitialized) return
        llApps.removeAllViews()
        val f = filter.trim().lowercase()
        val shown = apps.filter { f.isEmpty() || it.label.lowercase().contains(f) || it.pkg.lowercase().contains(f) }
        shown.forEachIndexed { index, app ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(14), dp(11), dp(14), dp(11))

            val icon = ImageView(this)
            icon.layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            if (app.icon != null) icon.setImageDrawable(app.icon)
            row.addView(icon)

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val cp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            cp.marginStart = dp(12)
            col.layoutParams = cp

            val name = TextView(this)
            name.text = app.label
            name.textSize = 15f
            name.setTypeface(name.typeface, Typeface.BOLD)
            name.setTextColor(getColor(R.color.text))
            col.addView(name)

            val pkg = TextView(this)
            pkg.text = app.pkg
            pkg.textSize = 11f
            pkg.setTextColor(getColor(R.color.text2))
            col.addView(pkg)
            row.addView(col)

            val sw = GlassSwitch(this)
            sw.layoutParams = LinearLayout.LayoutParams(dp(46), dp(28))
            sw.setChecked(selected.contains(app.pkg), false)
            sw.onChange = { checked ->
                if (checked) selected.add(app.pkg) else selected.remove(app.pkg)
                Store.setSplitApps(this, selected.toSet())
                updateCount()
            }
            row.addView(sw)
            row.setOnClickListener { sw.setChecked(!sw.checked, true) }
            llApps.addView(row)

            if (index < shown.size - 1) {
                val divider = View(this)
                val dp2 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                dp2.marginStart = dp(64)
                divider.layoutParams = dp2
                divider.setBackgroundColor(getColor(R.color.separator))
                llApps.addView(divider)
            }
        }
    }
}
