package com.gozarwg

import android.content.Intent
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : BaseActivity() {

    private lateinit var home: HomeFragment
    private lateinit var settings: SettingsFragment
    private var onSettings = false
    private var nav: BottomNavigationView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shell)
        if (savedInstanceState != null) onSettings = savedInstanceState.getBoolean("onSettings", false)

        val manager = supportFragmentManager
        val existingHome = manager.findFragmentByTag("home") as? HomeFragment
        val existingSettings = manager.findFragmentByTag("settings") as? SettingsFragment
        home = existingHome ?: HomeFragment()
        settings = existingSettings ?: SettingsFragment()

        if (existingHome == null || existingSettings == null) {
            manager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.container, settings, "settings")
                .add(R.id.container, home, "home")
                .commitNow()
        }
        home.restore(intent)
        settings.restore(intent)

        applyState()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.shell)) { target, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val bar = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav = bar
        bar.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    onSettings = false
                    applyState()
                    true
                }
                R.id.nav_settings -> {
                    onSettings = true
                    applyState()
                    true
                }
                else -> false
            }
        }
        bar.selectedItemId = if (onSettings) R.id.nav_settings else R.id.nav_home
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("onSettings", onSettings)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onSettings = false
        applyState()
        nav?.selectedItemId = R.id.nav_home
        home.restore(intent)
    }

    fun showHome() {
        if (!onSettings) return
        onSettings = false
        applyState()
        nav?.selectedItemId = R.id.nav_home
    }

    fun showSettings() {
        if (onSettings) return
        onSettings = true
        applyState()
        nav?.selectedItemId = R.id.nav_settings
    }

    private fun applyState() {
        if (!::home.isInitialized || !::settings.isInitialized) return
        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        if (onSettings) {
            transaction.hide(home).show(settings)
        } else {
            transaction.hide(settings).show(home)
        }
        transaction.commitAllowingStateLoss()
    }
}
