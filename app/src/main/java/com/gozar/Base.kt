package com.gozar

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat

abstract class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Store.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    protected fun <T : android.view.View> bind(id: Int): T = findViewById(id)

    override fun onResume() {
        super.onResume()
        val mode = nightModeOf(Store.theme(this))
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    fun toast(res: Int) {
        runOnUiThread { Toast.makeText(this, res, Toast.LENGTH_SHORT).show() }
    }
}

fun nightModeOf(theme: String): Int = when (theme) {
    "light" -> AppCompatDelegate.MODE_NIGHT_NO
    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
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
