package com.gozarwg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class Boot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Store.auto(context)) return
        val id = Store.selected(context)
        if (id.isBlank()) return
        val p = Profiles.get(context, id) ?: return
        try {
            Vpn.connect(context.applicationContext, p)
        } catch (e: Exception) {
        }
    }
}
