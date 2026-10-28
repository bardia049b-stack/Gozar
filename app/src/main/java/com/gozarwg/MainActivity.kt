package com.gozarwg

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButtonToggleGroup

class MainActivity : BaseActivity() {

    private lateinit var home: HomeFragment
    private lateinit var settings: SettingsFragment
    private lateinit var fox: FoxFragment
    private var onSettings = false
    private var onFox = false
    private var nav: BottomNavigationView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        onFox = Store.foxMode(this)
        setTheme(if (onFox) R.style.AppTheme_Fox else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shell)
        if (savedInstanceState != null) onSettings = savedInstanceState.getBoolean("onSettings", false)

        val manager = supportFragmentManager
        val existingHome = manager.findFragmentByTag("home") as? HomeFragment
        val existingSettings = manager.findFragmentByTag("settings") as? SettingsFragment
        val existingFox = manager.findFragmentByTag("fox") as? FoxFragment
        home = existingHome ?: HomeFragment()
        settings = existingSettings ?: SettingsFragment()
        fox = existingFox ?: FoxFragment()

        if (existingHome == null || existingSettings == null || existingFox == null) {
            val transaction = manager.beginTransaction().setReorderingAllowed(true)
            if (existingSettings == null) transaction.add(R.id.container, settings, "settings")
            if (existingHome == null) transaction.add(R.id.container, home, "home")
            if (existingFox == null) transaction.add(R.id.container, fox, "fox")
            transaction.commitNow()
        }
        home.restore(intent)
        settings.restore(intent)

        back()
        applyState()
        switch()

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

    private fun switch() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.modeSwitch)
        group.isSelectionRequired = true
        group.check(if (onFox) R.id.modeFox else R.id.modeWg)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val wantFox = checkedId == R.id.modeFox
            if (wantFox == Store.foxMode(this)) return@addOnButtonCheckedListener
            Store.setFoxMode(this, wantFox)
            Store.setWantUp(this, false)
            recreate()
        }
    }

    private fun back() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    onFox -> {
                        Store.setFoxMode(this@MainActivity, false)
                        recreate()
                    }
                    onSettings -> showHome()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
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
        if (!::home.isInitialized || !::settings.isInitialized || !::fox.isInitialized) return
        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        if (onFox) {
            transaction.hide(home).hide(settings).show(fox)
        } else {
            transaction.hide(fox)
            if (onSettings) transaction.hide(home).show(settings) else transaction.hide(settings).show(home)
        }
        transaction.commitAllowingStateLoss()
        nav?.visibility = if (onFox) android.view.View.GONE else android.view.View.VISIBLE
    }
}
