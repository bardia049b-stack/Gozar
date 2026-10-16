package com.beofoq.wg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object Actions {
    const val DISCONNECT = "com.beofoq.wg.action.DISCONNECT"
    const val TOGGLE = "com.beofoq.wg.action.TOGGLE"
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
