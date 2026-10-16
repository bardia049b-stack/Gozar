package com.beofoq.wg

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

class ProfileAdapter(
    private val onPick: (Profile) -> Unit,
    private val onMenu: (Profile, View) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {

    private val items = mutableListOf<Profile>()
    private var selectedId = ""

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val tile: ImageView = view.findViewById(R.id.profileTile)
        val name: TextView = view.findViewById(R.id.profileName)
        val endpoint: TextView = view.findViewById(R.id.profileEndpoint)
        val check: ImageView = view.findViewById(R.id.profileCheck)
        val more: View = view.findViewById(R.id.profileMore)
    }

    fun submit(list: List<Profile>, selected: String) {
        items.clear()
        items.addAll(list)
        selectedId = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val active = item.id == selectedId
        val container = ContextCompat.getColor(
            context,
            if (active) R.color.m3_primary_container else R.color.m3_surface_container_high
        )
        val content = ContextCompat.getColor(
            context,
            if (active) R.color.m3_on_primary_container else R.color.m3_on_surface_variant
        )
        holder.tile.backgroundTintList = ColorStateList.valueOf(container)
        holder.tile.imageTintList = ColorStateList.valueOf(content)
        holder.name.text = item.name
        holder.endpoint.text = item.endpoint.ifBlank { context.getString(R.string.endpoint_unknown) }
        holder.check.visibility = if (active) View.VISIBLE else View.INVISIBLE
        holder.check.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.m3_primary))
        holder.itemView.setOnClickListener { onPick(item) }
        holder.more.setOnClickListener { onMenu(item, holder.more) }
        holder.itemView.setOnLongClickListener {
            onMenu(item, holder.more)
            true
        }
    }
}

class AppAdapter(
    private val isOn: (String) -> Boolean,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    class Row(val pkg: String, val label: String, val icon: Drawable?)

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val pkg: TextView = view.findViewById(R.id.appPackage)
        val toggle: MaterialSwitch = view.findViewById(R.id.appSwitch)
    }

    private val items = mutableListOf<Row>()
    private val all = mutableListOf<Row>()

    fun submit(list: List<Row>) {
        all.clear()
        all.addAll(list)
        filter("")
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        items.clear()
        if (q.isEmpty()) {
            items.addAll(all)
        } else {
            items.addAll(all.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) })
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = items[position]
        holder.name.text = row.label
        holder.pkg.text = row.pkg
        if (row.icon != null) holder.icon.setImageDrawable(row.icon)
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = isOn(row.pkg)
        holder.toggle.setOnCheckedChangeListener { _, checked -> onToggle(row.pkg, checked) }
        holder.itemView.setOnClickListener { holder.toggle.isChecked = !holder.toggle.isChecked }
    }
}
