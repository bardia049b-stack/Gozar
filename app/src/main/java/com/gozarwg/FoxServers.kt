package com.gozarwg

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

object FoxServers {

    fun show(context: Context, onPick: () -> Unit) {
        val host = context
        val dialog = BottomSheetDialog(host)
        val view = LayoutInflater.from(host).inflate(R.layout.sheet_fox_servers, null)
        dialog.setContentView(view)
        val list = view.findViewById<LinearLayout>(R.id.foxSheetList)
        val empty = view.findViewById<TextView>(R.id.foxSheetEmpty)
        val count = view.findViewById<TextView>(R.id.foxSheetCount)

        fun paint() {
            list.removeAllViews()
            val servers = Fox.servers
            empty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
            val ready = servers.count { Fox.delayOf(it) > 0 }
            count.text = if (servers.isEmpty()) "" else if (ready == 0) "" else "$ready / ${servers.size}"
            val chosen = FoxStore.selected(host)
            for (location in servers) {
                val row = LayoutInflater.from(host).inflate(R.layout.item_fox_server, list, false)
                row.findViewById<CountryFlagView>(R.id.foxFlag).countryCode = location.code
                row.findViewById<TextView>(R.id.foxCountry).text = location.country
                val city = row.findViewById<TextView>(R.id.foxCity)
                city.text = if (location.city.isEmpty()) host.getString(R.string.fox_recommended) else location.city
                val delay = Fox.delayOf(location)
                row.findViewById<TextView>(R.id.foxDelay).text = when {
                    delay < 0 -> host.getString(R.string.fox_ping_wait)
                    delay == 0 -> "—"
                    else -> "$delay ms"
                }
                row.findViewById<View>(R.id.foxCheck).visibility =
                    if (location.code == chosen) View.VISIBLE else View.INVISIBLE
                row.setOnClickListener {
                    Fox.select(host, location)
                    dialog.dismiss()
                    onPick()
                }
                list.addView(row)
            }
        }

        val listener: () -> Unit = {
            if (dialog.isShowing) {
                try {
                    paint()
                } catch (e: Exception) {
                }
            }
        }
        Fox.watch(listener)
        view.findViewById<View>(R.id.foxSheetMeasure).setOnClickListener { Fox.measure(host) }
        dialog.setOnDismissListener { Fox.unwatch(listener) }
        paint()
        if (!Fox.busy && Fox.servers.isNotEmpty() && !Fox.hasDelays()) Fox.measure(host)
        dialog.show()
    }
}
