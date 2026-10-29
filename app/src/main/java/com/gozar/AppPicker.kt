package com.gozar

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

class AppPicker(private val context: Context, private val done: () -> Unit) {

    private class Row(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val system: Boolean
    )

    private val loader = Thread {
        val manager = context.packageManager
        val rows = ArrayList<Row>()
        val seen = HashSet<String>()
        val flags = PackageManager.GET_META_DATA
        for (info in manager.getInstalledApplications(flags)) {
            if (info.packageName == context.packageName) continue
            if (!seen.add(info.packageName)) continue
            val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val label = try {
                manager.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                info.packageName
            }
            val icon = try {
                manager.getApplicationIcon(info)
            } catch (e: Exception) {
                null
            }
            rows.add(Row(info.packageName, label, icon, system))
        }
        rows.sortWith(compareBy({ it.system }, { it.label.lowercase() }))
        android.os.Handler(android.os.Looper.getMainLooper()).post { show(rows) }
    }

    fun open() {
        loader.start()
    }

    private fun show(rows: List<Row>) {
        if (rows.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.apps)
                .setMessage(R.string.apps_none)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val chosen = Store.apps(context).toMutableSet()
        val filtered = ArrayList<Row>(rows)

        val builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.apps)
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val search = EditText(context).apply {
            hint = context.getString(R.string.apps_search)
            setSingleLine(true)
            setPadding(56, 24, 56, 24)
        }
        val list = android.widget.ListView(context).apply { dividerHeight = 0 }
        layout.addView(search, android.widget.LinearLayout.LayoutParams(-1, -2))
        layout.addView(list, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        builder.setView(layout)

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = filtered.size
            override fun getItem(position: Int): Any = filtered[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_app_pick, parent, false)
                val row = filtered[position]
                view.findViewById<TextView>(R.id.appLabel).text = row.label
                view.findViewById<TextView>(R.id.appSystem).visibility =
                    if (row.system) View.VISIBLE else View.GONE
                view.findViewById<TextView>(R.id.appPackage).text = row.packageName
                val picture = view.findViewById<ImageView>(R.id.appIcon)
                if (row.icon != null) picture.setImageDrawable(row.icon) else picture.setImageResource(R.drawable.ic_apps)
                view.findViewById<CheckBox>(R.id.appCheck).isChecked = row.packageName in chosen
                return view
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val row = filtered[position]
            if (!chosen.add(row.packageName)) chosen.remove(row.packageName)
            adapter.notifyDataSetChanged()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase().orEmpty()
                filtered.clear()
                if (query.isEmpty()) {
                    filtered.addAll(rows)
                } else {
                    rows.filterTo(filtered) {
                        it.label.lowercase().contains(query) || it.packageName.contains(query)
                    }
                }
                adapter.notifyDataSetChanged()
            }
        })

        builder.setNeutralButton(R.string.apps_all) { _, _ ->
            Store.setApps(context, emptySet())
            done()
        }
        builder.setNegativeButton(R.string.cancel, null)
        builder.setPositiveButton(R.string.routing_save) { _, _ ->
            Store.setApps(context, chosen)
            done()
        }
        builder.show()
    }
}
