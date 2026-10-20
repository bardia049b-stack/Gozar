package com.gozarwg

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

class AppsActivity : BaseActivity() {

    private lateinit var adapter: AppAdapter
    private lateinit var empty: TextView
    private val selected = mutableSetOf<String>()
    private var selectionChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        selected.addAll(Store.splitApps(this))
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        empty = findViewById(R.id.tvEmptyApps)
        adapter = AppAdapter(
            isOn = { pkg -> selected.contains(pkg) },
            onToggle = { pkg, checked ->
                if (checked) selected.add(pkg) else selected.remove(pkg)
                Store.setSplitApps(this, selected.toSet())
                selectionChanged = true
                count()
            }
        )
        val list = findViewById<RecyclerView>(R.id.rvApps)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<TextInputEditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString() ?: "")
                empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            }
        })
        count()
        load()
    }


    override fun onStop() {
        super.onStop()
        if (!selectionChanged) return
        // Android cannot apply an allowed-app list to a local SOCKS endpoint. Switching to
        // app routing therefore uses the regular system-VPN backend and restarts it once.
        if (Store.splitMode(this) != 0 && Store.proxy(this)) Store.setProxy(this, false)
        Vpn.reconfigureRouting(this)
        selectionChanged = false
    }

    private fun count() {
        findViewById<TextView>(R.id.tvCount).text =
            String.format(getString(R.string.selected_count), selected.size)
    }

    private fun load() {
        thread {
            val manager = packageManager
            val rows = mutableListOf<AppAdapter.Row>()
            val installed = manager.getInstalledApplications(PackageManager.GET_META_DATA)
            for (info: ApplicationInfo in installed) {
                if (info.packageName == packageName) continue
                val label = manager.getApplicationLabel(info).toString()
                val icon = try {
                    manager.getApplicationIcon(info)
                } catch (e: Exception) {
                    null
                }
                rows.add(AppAdapter.Row(info.packageName, label, icon))
            }
            rows.sortBy { it.label.lowercase() }
            runOnUiThread {
                adapter.submit(rows)
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
