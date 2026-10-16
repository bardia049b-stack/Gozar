package com.gozarwg

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
        AppCompatDelegate.setDefaultNightMode(nightModeOf(Store.theme(this)))
    }
}
