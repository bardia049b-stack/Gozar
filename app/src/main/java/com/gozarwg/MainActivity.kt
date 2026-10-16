package com.gozarwg

import android.content.Intent
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : BaseActivity() {

    private lateinit var home: HomeFragment
    private lateinit var settings: SettingsFragment
    private var onSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shell)
        val manager = supportFragmentManager
        val existingHome = manager.findFragmentByTag("home") as? HomeFragment
        val existingSettings = manager.findFragmentByTag("settings") as? SettingsFragment
        home = existingHome ?: HomeFragment()
        settings = existingSettings ?: SettingsFragment()
        if (existingHome == null || existingSettings == null) {
            val transaction = manager.beginTransaction()
            transaction.add(R.id.container, settings, "settings")
            transaction.add(R.id.container, home, "home")
            transaction.hide(settings)
            transaction.commitNow()
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container)) { target, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.updatePadding(top = bars.top)
            insets
        }
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showHome()
                    true
                }
                R.id.nav_settings -> {
                    showSettings()
                    true
                }
                else -> false
            }
        }
        nav.selectedItemId = if (onSettings) R.id.nav_settings else R.id.nav_home
        home.handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showHome()
        home.handleIntent(intent)
    }

    fun showHome() {
        if (!onSettings) return
        onSettings = false
        supportFragmentManager.beginTransaction().hide(settings).show(home).commit()
    }

    fun showSettings() {
        if (onSettings) return
        onSettings = true
        supportFragmentManager.beginTransaction().hide(home).show(settings).commit()
    }

    fun openSettings() {
        showSettings()
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_settings
    }

    fun openHome() {
        showHome()
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_home
    }
}
