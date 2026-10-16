package com.gozarwg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object Actions {
    const val DISCONNECT = "com.gozarwg.action.DISCONNECT"
    const val TOGGLE = "com.gozarwg.action.TOGGLE"
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Actions.DISCONNECT -> Vpn.disconnect()
            Actions.TOGGLE -> {
                if (Vpn.up || Vpn.connecting) Vpn.disconnect() else Vpn.restore(context.applicationContext)
            }
        }
    }
}
