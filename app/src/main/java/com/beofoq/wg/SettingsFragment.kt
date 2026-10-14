package com.beofoq.wg

import android.app.Activity
import android.app.AlertDialog
import android.app.Fragment
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

class SettingsFragment : Fragment() {
    private lateinit var root: View

    private val act: Activity get() = requireActivity()

    private fun <T : View> find(id: Int): T = root.findViewById(id)

    fun toast(msg: String) {
        act.runOnUiThread { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show() }
    }

    fun toast(res: Int) {
        act.runOnUiThread { Toast.makeText(act, res, Toast.LENGTH_SHORT).show() }
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = inflater.inflate(R.layout.activity_settings, container, false)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        find<View>(R.id.btnBack).setOnClickListener { (activity as? MainActivity)?.showHome() }

        bindSwitch(R.id.swKill, Store.kill(act)) { Store.setKill(act, it) }
        bindSwitch(R.id.swAuto, Store.auto(act)) { Store.setAuto(act, it) }
        bindSwitch(R.id.swLan, Store.lan(act)) { Store.setLan(act, it) }
        bindSwitch(R.id.swProxy, Store.proxy(act)) { v ->
            Store.setProxy(act, v)
            toast(getString(R.string.proxy_note, "127.0.0.1:" + Store.proxyPort(act)))
        }

        updateProxyText()
        rowClick(R.id.tvProxyPort) { editPort() }

        updateSplitTexts()
        rowClick(R.id.tvSplitMode) { pickSplitMode() }
        rowClick(R.id.tvSplitApps) {
            startActivity(Intent(act, AppsActivity::class.java))
        }

        updateLangThemeTexts()
        rowClick(R.id.tvLang) { pickLang() }
        rowClick(R.id.tvTheme) { pickTheme() }
        rowClick(R.id.tvVpnSettings) {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (e2: Exception) {
                }
            }
        }

        val tvAbout = find<TextView>(R.id.tvAbout)
        val version = try {
            act.packageManager.getPackageInfo(act.packageName, 0).versionName
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
                act.runOnUiThread {
                    tvAbout.text = tvAbout.text.toString() + "\n" + String.format(getString(R.string.core_version), core)
                }
            }
        }
    }

    }

    override fun onResume() {
        super.onResume()
        updateSplitTexts()
        updateProxyText()
    }

    private fun rowClick(id: Int, action: () -> Unit) {
        var v: View? = find(id)
        repeat(3) {
            v?.setOnClickListener { action() }
            v = v?.parent as? View
        }
    }

    private fun bindSwitch(id: Int, initial: Boolean, save: (Boolean) -> Unit) {
        val sw = find<GlassSwitch>(id)
        sw.setChecked(initial, false)
        sw.onChange = { save(it) }
    }

    private fun updateProxyText() {
        find<TextView>(R.id.tvProxyPort).text = "SOCKS5 · 127.0.0.1:" + Store.proxyPort(act)
    }

    private fun editPort() {
        val input = EditText(act)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText(Store.proxyPort(act).toString())
        AlertDialog.Builder(act)
            .setTitle(R.string.proxy_port)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val port = input.text.toString().toIntOrNull()
                if (port != null && port in 1024..65535) {
                    Store.setProxyPort(act, port)
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
        find<TextView>(R.id.tvSplitMode).text = splitModeName(Store.splitMode(act))
        find<TextView>(R.id.tvSplitApps).text =
            String.format(getString(R.string.selected_count), Store.splitApps(act).size)
    }

    private fun pickSplitMode() {
        val items = arrayOf(
            getString(R.string.split_off),
            getString(R.string.split_exclude),
            getString(R.string.split_include)
        )
        AlertDialog.Builder(act)
            .setTitle(R.string.split_mode)
            .setSingleChoiceItems(items, Store.splitMode(act)) { dialog, which ->
                Store.setSplitMode(act, which)
                updateSplitTexts()
                dialog.dismiss()
            }
            .show()
    }

    private fun updateLangThemeTexts() {
        find<TextView>(R.id.tvLang).text = when (Store.lang(act)) {
            "fa" -> getString(R.string.lang_fa)
            "en" -> getString(R.string.lang_en)
            else -> getString(R.string.lang_system)
        }
        find<TextView>(R.id.tvTheme).text = when (Store.theme(act)) {
            "light" -> getString(R.string.theme_light)
            "dark" -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_auto)
        }
    }

    private fun pickLang() {
        val items = arrayOf(getString(R.string.lang_fa), getString(R.string.lang_en), getString(R.string.lang_system))
        val values = arrayOf("fa", "en", "")
        val current = values.indexOf(Store.lang(act)).coerceAtLeast(2)
        AlertDialog.Builder(act)
            .setTitle(R.string.language)
            .setSingleChoiceItems(items, current) { dialog, which ->
                Store.setLang(act, values[which])
                dialog.dismiss()
                act.recreate()
            }
            .show()
    }

    private fun pickTheme() {
        val items = arrayOf(getString(R.string.theme_auto), getString(R.string.theme_light), getString(R.string.theme_dark))
        val values = arrayOf("auto", "light", "dark")
        val current = values.indexOf(Store.theme(act)).coerceAtLeast(0)
        AlertDialog.Builder(act)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(items, current) { dialog, which ->
                Store.setTheme(act, values[which])
                dialog.dismiss()
                act.recreate()
            }
            .show()
    }
}
