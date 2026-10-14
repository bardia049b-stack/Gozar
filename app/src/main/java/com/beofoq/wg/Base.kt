package com.beofoq.wg

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.widget.Toast

abstract class BaseActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Store.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        val want = when (Store.theme(this)) {
            "light" -> Configuration.UI_MODE_NIGHT_NO
            "dark" -> Configuration.UI_MODE_NIGHT_YES
            else -> Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        val now = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (want != now) recreate()
    }

    fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    fun toast(res: Int) {
        runOnUiThread { Toast.makeText(this, res, Toast.LENGTH_SHORT).show() }
    }
}

fun fmtBytes(v: Long): String {
    if (v < 1024) return "$v B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var d = v.toDouble()
    var i = -1
    while (d >= 1024 && i < units.size - 1) {
        d /= 1024
        i++
    }
    return String.format("%.1f %s", d, units[i])
}

fun fmtSpeed(bytesPerSec: Double): String {
    return fmtBytes(bytesPerSec.toLong()) + "/s"
}

fun fmtTime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
