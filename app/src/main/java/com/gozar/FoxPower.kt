package com.gozar

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Battery-aware gating for optional background work: when the battery is low
 * and unplugged, refreshes that can wait (like the geo-list update) are
 * skipped instead of burning the last percent.
 */
object FoxPower {

    fun defer(batteryPct: Int, charging: Boolean): Boolean = batteryPct in 1..14 && !charging

    fun deferNow(app: Context): Boolean =
        try {
            val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return false
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level < 0 || scale <= 0) return false
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            defer(level * 100 / scale, charging)
        } catch (e: Exception) {
            false
        }
}
