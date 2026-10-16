package com.beofoq.wg

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.concurrent.thread

class SettingsFragment : Fragment() {

    private fun <T : View> find(id: Int): T = view!!.findViewById(id)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.activity_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bind(R.id.rowKill, R.id.swKill, Store.kill(requireContext())) { Store.setKill(requireContext(), it) }
        bind(R.id.rowAuto, R.id.swAuto, Store.auto(requireContext())) { Store.setAuto(requireContext(), it) }
        bind(R.id.rowLan, R.id.swLan, Store.lan(requireContext())) { Store.setLan(requireContext(), it) }
        bind(R.id.rowProxy, R.id.swProxy, Store.proxy(requireContext())) { value ->
            Store.setProxy(requireContext(), value)
            toast(getString(R.string.proxy_note, "127.0.0.1:" + Store.proxyPort(requireContext())))
        }

        find<View>(R.id.rowPort).setOnClickListener { editPort() }
        find<View>(R.id.rowSplitMode).setOnClickListener { pickSplitMode() }
        find<View>(R.id.rowSplitApps).setOnClickListener {
            startActivity(Intent(requireContext(), AppsActivity::class.java))
        }
        find<View>(R.id.rowLang).setOnClickListener { pickLanguage() }
        find<View>(R.id.rowTheme).setOnClickListener { pickTheme() }
        find<View>(R.id.rowVpnSettings).setOnClickListener { openSystemVpn() }

        val version = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        find<TextView>(R.id.tvAbout).text = getString(R.string.app_name) + " · " + String.format(getString(R.string.version), version)
        find<TextView>(R.id.tvCore).setText(R.string.built_in)
        thread {
            val core = try {
                Vpn.coreVersion(requireContext().applicationContext)
            } catch (e: Throwable) {
                ""
            }
            if (core.isNotBlank()) {
                activity?.runOnUiThread {
                    if (view != null) find<TextView>(R.id.tvCore).text = String.format(getString(R.string.core_version), core)
                }
            }
        }
        paint()
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    private fun toast(message: String) {
        activity?.runOnUiThread { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun bind(row: Int, switchId: Int, initial: Boolean, save: (Boolean) -> Unit) {
        val toggle = find<MaterialSwitch>(switchId)
        toggle.isChecked = initial
        toggle.setOnCheckedChangeListener { _, checked -> save(checked) }
        find<View>(row).setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    private fun paint() {
        if (view == null) return
        find<TextView>(R.id.tvProxyPort).text = "SOCKS5 · 127.0.0.1:" + Store.proxyPort(requireContext())
        val mode = Store.splitMode(requireContext())
        find<TextView>(R.id.tvSplitMode).text = when (mode) {
            1 -> getString(R.string.split_exclude)
            2 -> getString(R.string.split_include)
            else -> getString(R.string.split_off)
        }
        find<TextView>(R.id.tvSplitApps).text =
            String.format(getString(R.string.selected_count), Store.splitApps(requireContext()).size)
        find<TextView>(R.id.tvLang).text = when (Store.lang(requireContext())) {
            "fa" -> getString(R.string.lang_fa)
            "en" -> getString(R.string.lang_en)
            else -> getString(R.string.lang_system)
        }
        find<TextView>(R.id.tvTheme).text = when (Store.theme(requireContext())) {
            "light" -> getString(R.string.theme_light)
            "dark" -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_auto)
        }
    }

    private fun editPort() {
        val content = layoutInflater.inflate(R.layout.dialog_port, null)
        val box = content.findViewById<TextInputLayout>(R.id.portBox)
        val input = content.findViewById<TextInputEditText>(R.id.etPort)
        input.setText(Store.proxyPort(requireContext()).toString())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.proxy_port)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ ->
                val port = input.text.toString().toIntOrNull()
                if (port == null || port !in 1024..65535) {
                    box.error = getString(R.string.invalid_port)
                } else {
                    Store.setProxyPort(requireContext(), port)
                    paint()
                }
            }
            .show()
    }

    private fun pickSplitMode() {
        val items = arrayOf(
            getString(R.string.split_off),
            getString(R.string.split_exclude),
            getString(R.string.split_include)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.split_mode)
            .setSingleChoiceItems(items, Store.splitMode(requireContext())) { dialog, which ->
                Store.setSplitMode(requireContext(), which)
                dialog.dismiss()
                paint()
            }
            .show()
    }

    private fun pickLanguage() {
        val items = arrayOf(getString(R.string.lang_system), getString(R.string.lang_fa), getString(R.string.lang_en))
        val values = arrayOf("", "fa", "en")
        val current = values.indexOf(Store.lang(requireContext())).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.language)
            .setSingleChoiceItems(items, current) { dialog, which ->
                Store.setLang(requireContext(), values[which])
                dialog.dismiss()
                paint()
                activity?.recreate()
            }
            .show()
    }

    private fun pickTheme() {
        val items = arrayOf(getString(R.string.theme_auto), getString(R.string.theme_light), getString(R.string.theme_dark))
        val values = arrayOf("auto", "light", "dark")
        val current = values.indexOf(Store.theme(requireContext())).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.theme)
            .setSingleChoiceItems(items, current) { dialog, which ->
                Store.setTheme(requireContext(), values[which])
                dialog.dismiss()
                paint()
                AppCompatDelegate.setDefaultNightMode(nightModeOf(values[which]))
            }
            .show()
    }

    private fun openSystemVpn() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (e2: Exception) {
            }
        }
    }
}
