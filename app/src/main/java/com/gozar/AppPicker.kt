package com.gozar

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

class AppPicker(private val context: Context, private val done: () -> Unit) {

    private class Row(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val system: Boolean
    )

    private val chosen = Store.apps(context).toMutableSet()
    private var dialog: BottomSheetDialog? = null

    fun open() {
        Thread {
            val manager = context.packageManager
            val rows = ArrayList<Row>()
            val seen = HashSet<String>()
            for (info in manager.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if (info.packageName == context.packageName) continue
                if (!seen.add(info.packageName)) continue
                val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                    (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
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
            Handler(Looper.getMainLooper()).post { show(rows) }
        }.start()
    }

    private fun show(rows: List<Row>) {
        if (rows.isEmpty()) return
        val sheet = BottomSheetDialog(context, R.style.ThemeOverlay_Gozar_BottomSheet)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_app_picker, null)
        sheet.setContentView(view)

        val list = view.findViewById<android.widget.ListView>(R.id.appList)
        val search = view.findViewById<EditText>(R.id.appSearch)
        val count = view.findViewById<TextView>(R.id.appCount)
        val empty = view.findViewById<TextView>(R.id.appEmpty)
        val all = view.findViewById<MaterialButton>(R.id.appAll)
        val save = view.findViewById<MaterialButton>(R.id.appSave)

        val filtered = ArrayList<Row>()
        filtered.addAll(rows)

        fun refreshCount() {
            count.text = if (chosen.isEmpty()) {
                context.getString(R.string.apps_all_hint)
            } else {
                context.getString(R.string.apps_picked, chosen.size)
            }
            save.isEnabled = true
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = filtered.size
            override fun getItem(position: Int): Any = filtered[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = filtered[position]
                val cell = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app_pick, parent, false)
                cell.findViewById<TextView>(R.id.appLabel).text = row.label
                cell.findViewById<TextView>(R.id.appPackage).text = row.packageName
                cell.findViewById<TextView>(R.id.appSystem).visibility =
                    if (row.system) View.VISIBLE else View.GONE
                val picture = cell.findViewById<ImageView>(R.id.appIcon)
                if (row.icon != null) picture.setImageDrawable(row.icon) else picture.setImageResource(R.drawable.ic_apps)
                cell.findViewById<CheckBox>(R.id.appCheck).isChecked = row.packageName in chosen
                return cell
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val row = filtered[position]
            if (!chosen.add(row.packageName)) chosen.remove(row.packageName)
            adapter.notifyDataSetChanged()
            refreshCount()
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
                empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        })

        all.setOnClickListener {
            chosen.clear()
            adapter.notifyDataSetChanged()
            refreshCount()
        }
        save.setOnClickListener {
            Store.setApps(context, HashSet(chosen))
            sheet.dismiss()
            done()
        }
        refreshCount()
        dialog = sheet
        sheet.show()
    }
}
