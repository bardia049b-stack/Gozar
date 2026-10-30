package com.gozar

import android.app.Activity
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
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

class AppPicker(private val context: Context, private val done: () -> Unit) {

    internal class Row(
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
                if (info.packageName == context.packageName || !seen.add(info.packageName)) continue
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
            Handler(Looper.getMainLooper()).post {
                val activity = context as? Activity
                if (activity == null || (!activity.isFinishing && !activity.isDestroyed)) show(rows)
            }
        }.start()
    }

    internal fun show(rows: List<Row>): BottomSheetDialog {
        val sheet = BottomSheetDialog(context, R.style.ThemeOverlay_Gozar_BottomSheet)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_app_picker, null)
        sheet.setContentView(view)
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.behavior.isDraggable = false
        sheet.behavior.skipCollapsed = true
        sheet.setOnShowListener {
            sheet.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottom ->
                val params = bottom.layoutParams
                params.height = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
                bottom.layoutParams = params
            }
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        val list = view.findViewById<RecyclerView>(R.id.appList)
        val search = view.findViewById<EditText>(R.id.appSearch)
        val count = view.findViewById<TextView>(R.id.appCount)
        val empty = view.findViewById<TextView>(R.id.appEmpty)
        val all = view.findViewById<MaterialButton>(R.id.appAll)
        val save = view.findViewById<MaterialButton>(R.id.appSave)
        val filtered = ArrayList(rows)

        fun refreshCount() {
            count.text = if (chosen.isEmpty()) {
                context.getString(R.string.apps_all_hint)
            } else {
                context.getString(R.string.apps_picked, chosen.size)
            }
        }

        class Holder(cell: View) : RecyclerView.ViewHolder(cell)

        val adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount(): Int = filtered.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
                Holder(LayoutInflater.from(context).inflate(R.layout.item_app_pick, parent, false))

            override fun onBindViewHolder(holder: Holder, position: Int) {
                val row = filtered[position]
                val cell = holder.itemView
                cell.findViewById<TextView>(R.id.appLabel).text = row.label
                cell.findViewById<TextView>(R.id.appPackage).text = row.packageName
                cell.findViewById<TextView>(R.id.appSystem).visibility =
                    if (row.system) View.VISIBLE else View.GONE
                val picture = cell.findViewById<ImageView>(R.id.appIcon)
                if (row.icon != null) picture.setImageDrawable(row.icon) else picture.setImageResource(R.drawable.ic_apps)
                cell.findViewById<CheckBox>(R.id.appCheck).isChecked = row.packageName in chosen
                cell.setOnClickListener {
                    if (!chosen.add(row.packageName)) chosen.remove(row.packageName)
                    val index = holder.bindingAdapterPosition
                    if (index != RecyclerView.NO_POSITION) notifyItemChanged(index)
                    refreshCount()
                }
            }
        }
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

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
                        it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
                    }
                }
                empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
                list.scrollToPosition(0)
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
        return sheet
    }
}
