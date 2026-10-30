package com.gozar

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        deleteFile("last-crash.txt")
        AppCompatDelegate.setDefaultNightMode(nightModeOf(Store.theme(this)))
        UpdateChecker.schedule(this)
        UpdateChecker.check(this)
    }
}
