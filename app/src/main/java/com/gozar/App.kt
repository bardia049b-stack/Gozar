package com.gozar

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        deleteFile("last-crash.txt")
        val updates = FoxUpdateStore(java.io.File(filesDir, "fox-updates"))
        FoxGeo.attachStore(updates)
        UpdateChecker.attachStore(updates)
        AppCompatDelegate.setDefaultNightMode(nightModeOf(Store.theme(this)))
        UpdateChecker.schedule(this)
        UpdateChecker.check(this)
    }
}
