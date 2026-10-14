package com.beofoq.wg

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        bindSwitch(R.id.swKill, Store.kill(this)) { Store.setKill(this, it) }
        bindSwitch(R.id.swAuto, Store.auto(this)) { Store.setAuto(this, it) }
        bindSwitch(R.id.swLan, Store.lan(this)) { Store.setLan(this, it) }
        bindSwitch(R.id.swProxy, Store.proxy(this)) { v ->
            Store.setProxy(this, v)
            toast(getString(R.string.proxy_note, "127.0.0.1:" + Store.proxyPort(this)))
        }

        updateProxyText()
        findViewById<View>(R.id.tvProxyPort).setOnClickListener { editPort() }

        updateSplitTexts()
        findViewById<View>(R.id.tvSplitMode).setOnClickListener { pickSplitMode() }
        findViewById<View>(R.id.tvSplitApps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        updateLangThemeTexts()
        findViewById<View>(R.id.tvLang).setOnClickListener { pickLang() }
        findViewById<View>(R.id.tvTheme).setOnClickListener { pickTheme() }
        findViewById<View>(R.id.tvVpnSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (e2: Exception) {
                }
            }
        }

        val tvAbout = findViewById<TextView>(R.id.tvAbout)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        tvAbout.text = getString(R.string.app_name) + " · " + String.format(getString(R.string.version), version)
        thread {
            val core = try {
                Vpn.coreVersion(applicationContext)
            } catch (e: Throwable) {
                ""
            }
            if (core.isNotBlank()) {
                runOnUiThread {
                    tvAbout.text = tvAbout.text.toString() + "\n" + String.format(getString(R.string.core_version), core)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSplitTexts()
        updateProxyText()
    }

    private fun bindSwitch(id: Int, initial: Boolean, save: (Boolean) -> Unit) {
        val sw = findViewById<GlassSwitch>(id)
        sw.setChecked(initial, false)
        sw.onChange = { save(it) }
    }

    private fun updateProxyText() {
        findViewById<TextView>(R.id.tvProxyPort).text = "SOCKS5 · 127.0.0.1:" + Store.proxyPort(this)
    }

    private fun editPort() {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(Store.proxyPort(this).toString())
        AlertDialog.Builder(this)
            .setTitle(R.string.proxy_port)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val port = input.text.toString().toIntOrNull()
                if (port != null && port in 1024..65535) {
                    Store.setProxyPort(this, port)
                    updateProxyText()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun splitModeName(mode: Int): String {
        return when (mode) {
            1 -> getString(R.string.split_exclude)
            2 -> getString(R.string.split_include)
            else -> getString(R.string.split_off)
        }
    }

    private fun updateSplitTexts() {
        findViewById<TextView>(R.id.tvSplitMode).text = splitModeName(Store.splitMode(this))
        findViewById<TextView>(R.id.tvSplitApps).text =
            String.format(getString(R.string.selected_count), Store.splitApps(this).size)
    }

    private fun pickSplitMode() {
        val items = arrayOf(
            getString(R.string.split_off),
            getString(R.string.split_exclude),
            getString(R.string.split_include)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.split_mode)
            .setSingleChoiceItems(items, Store.splitMode(this)) { dialog, which ->
                Store.setSplitMode(this, which)
                updateSplitTexts()
                dialog.dismiss()
            }
            .show()
    }

    private fun updateLangThemeTexts() {
        findViewById<TextView>(R.id.tvLang).text = when (Store.lang(this)) {
            "fa" -> getString(R.string.lang_fa)
            "en" -> getString(R.string.lang_en)
            else -> getString(R.string.lang_system)
        }
        findViewById<TextView>(R.id.tvTheme).text = when (Store.theme(this)) {
            "light" -> getString(R.string.theme_light)
            "dark" -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_auto)
        }
    }

    private fun pickLang() {
        val items = arrayOf(getString(R.string.lang_fa), getString(R.string.lang_en), getString(R.string.lang_system))
        val values = arrayOf("fa", "en", "")
        val current = values.indexOf(Store.lang(this)).coerceAtLeast(2)
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(items, current) { dialog, which ->
                Store.setLang(this, values[which])
                dialog.dismiss()
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .show()
    }

    private fun pickTheme() {
        val items = arrayOf(getString(R.string.theme_auto), getString(R.string.theme_light), getString(R.string.theme_dark))
        val values = arrayOf("auto", "light", "dark")
        val current = values.indexOf(Store.theme(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(items, current) { dialog, which ->
                Store.setTheme(this, values[which])
                dialog.dismiss()
                recreate()
            }
            .show()
    }
}
