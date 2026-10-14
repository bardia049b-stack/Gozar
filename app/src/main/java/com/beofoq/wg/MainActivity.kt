package com.beofoq.wg

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView

class MainActivity : BaseActivity() {
    private lateinit var home: HomeFragment
    private lateinit var settings: SettingsFragment
    private var onSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shell)
        home = HomeFragment()
        settings = SettingsFragment()
        fragmentManager.beginTransaction()
            .add(R.id.container, settings, "settings")
            .hide(settings)
            .add(R.id.container, home, "home")
            .commit()
        fragmentManager.executePendingTransactions()
        findViewById<View>(R.id.btnHome).setOnClickListener { showHome() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.btnAdd).setOnClickListener {
            showHome()
            findViewById<View>(R.id.container).post { home.showAddSheet() }
        }
        updateNav()
        home.handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showHome()
        findViewById<View>(R.id.container).post { home.handleIntent(intent) }
    }

    fun showHome() {
        if (!onSettings) return
        onSettings = false
        fragmentManager.beginTransaction().hide(settings).show(home).commit()
        updateNav()
    }

    fun showSettings() {
        if (onSettings) return
        onSettings = true
        fragmentManager.beginTransaction().hide(home).show(settings).commit()
        updateNav()
    }

    private fun updateNav() {
        findViewById<ImageView>(R.id.tabHomeIcon).setColorFilter(getColor(if (onSettings) R.color.tab_icon_off else R.color.accent))
        findViewById<TextView>(R.id.tabHomeText).setTextColor(getColor(if (onSettings) R.color.tab_icon_off else R.color.accent))
        findViewById<ImageView>(R.id.tabSettingsIcon).setColorFilter(getColor(if (onSettings) R.color.accent else R.color.tab_icon_off))
        findViewById<TextView>(R.id.tabSettingsText).setTextColor(getColor(if (onSettings) R.color.accent else R.color.tab_icon_off))
    }

    override fun onBackPressed() {
        if (onSettings) showHome() else super.onBackPressed()
    }
}
