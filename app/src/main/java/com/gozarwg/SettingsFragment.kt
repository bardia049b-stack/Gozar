package com.gozarwg

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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

    private val rows = listOf(
        R.id.rowLang, R.id.rowTheme,
        R.id.rowKill, R.id.rowAuto, R.id.rowLan, R.id.rowNotif,
        R.id.rowProxy, R.id.rowPort,
        R.id.rowSplitMode, R.id.rowSplitApps,
        R.id.rowVpnSettings, R.id.rowGithub, R.id.rowCrash, R.id.rowCore, R.id.rowAbout
    )

    private fun <T : View> find(id: Int): T = view!!.findViewById(id)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.activity_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bind(R.id.rowKill, R.id.swKill, Store.kill(requireContext())) { Store.setKill(requireContext(), it) }
        bind(R.id.rowAuto, R.id.swAuto, Store.auto(requireContext())) { Store.setAuto(requireContext(), it) }
        bind(R.id.rowLan, R.id.swLan, Store.lan(requireContext())) { Store.setLan(requireContext(), it) }
        bind(R.id.rowNotif, R.id.swNotif, Store.notify(requireContext())) { Store.setNotify(requireContext(), it) }
        bind(R.id.rowProxy, R.id.swProxy, Store.proxy(requireContext())) { value ->
            Store.setProxy(requireContext(), value)
            toast(getString(R.string.proxy_note, "127.0.0.1:" + Store.proxyPort(requireContext())))
        }

        find<View>(R.id.btnBack).setOnClickListener { (activity as? MainActivity)?.showHome() }
        find<View>(R.id.rowPort).setOnClickListener { editPort() }
        find<View>(R.id.rowSplitMode).setOnClickListener { pickSplitMode() }
        find<View>(R.id.rowSplitApps).setOnClickListener {
            startActivity(Intent(requireContext(), AppsActivity::class.java))
        }
        find<View>(R.id.rowLang).setOnClickListener { pickLanguage() }
        find<View>(R.id.rowTheme).setOnClickListener { pickTheme() }
        find<View>(R.id.rowVpnSettings).setOnClickListener { openSystemVpn() }
        find<View>(R.id.rowGithub).setOnClickListener { openProject() }
        find<View>(R.id.rowCrash).setOnClickListener { shareCrashLog() }
        find<View>(R.id.bannerSupport).setOnClickListener { openProject() }

        find<TextInputEditText>(R.id.etSettingsSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter(s?.toString() ?: "")
            }
        })

        paint()
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    fun restore(intent: Intent?) {
        paint()
    }

    private fun filter(query: String) {
        if (view == null) return
        val text = query.trim().lowercase()
        for (id in rows) {
            val row = find<View>(id)
            if (text.isEmpty()) {
                row.visibility = View.VISIBLE
                continue
            }
            row.visibility = if (labelOf(row).contains(text)) View.VISIBLE else View.GONE
        }
        if (text.isEmpty()) paint()
    }

    private fun labelOf(row: View): String {
        val builder = StringBuilder()
        for (i in 0 until (row as ViewGroup).childCount) {
            val child = row.getChildAt(i)
            if (child is TextView) builder.append(child.text).append(' ')
            if (child is ViewGroup) {
                for (j in 0 until child.childCount) {
                    val nested = child.getChildAt(j)
                    if (nested is TextView) builder.append(nested.text).append(' ')
                }
            }
        }
        return builder.toString().lowercase()
    }

    private fun toast(res: Int) {
        toast(getString(res))
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
        if (view == null || context == null) return
        val context = requireContext()
        find<TextView>(R.id.tvProxyPort).text = "127.0.0.1:" + Store.proxyPort(context)
        find<TextView>(R.id.tvSplitMode).text = when (Store.splitMode(context)) {
            1 -> getString(R.string.split_exclude)
            2 -> getString(R.string.split_include)
            else -> getString(R.string.split_off)
        }
        find<TextView>(R.id.tvSplitApps).text =
            String.format(getString(R.string.selected_count), Store.splitApps(context).size)
        find<TextView>(R.id.tvLang).text = when (Store.lang(context)) {
            "fa" -> getString(R.string.lang_fa)
            "en" -> getString(R.string.lang_en)
            else -> getString(R.string.lang_system)
        }
        find<TextView>(R.id.tvTheme).text = when (Store.theme(context)) {
            "light" -> getString(R.string.theme_light)
            "dark" -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_auto)
        }
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        val core = find<TextView>(R.id.tvCore)
        if (core.text.isNullOrBlank()) {
            find<TextView>(R.id.tvAbout).text = getString(R.string.version, version)
            core.setText(R.string.built_in)
            thread {
                val value = try {
                    Vpn.coreVersion(context.applicationContext)
                } catch (e: Throwable) {
                    ""
                }
                if (value.isNotBlank()) {
                    activity?.runOnUiThread {
                        if (view != null) find<TextView>(R.id.tvCore).text = value
                    }
                }
            }
        }
        find<View>(R.id.rowCrash).visibility = if (Crash.log(context) != null) View.VISIBLE else View.GONE
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
                Store.setRecents(requireContext(), false)
                dialog.dismiss()
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
                AppCompatDelegate.setDefaultNightMode(nightModeOf(values[which]))
                paint()
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

    private fun openProject() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bardia049b-stack/GozarWG")))
        } catch (e: Exception) {
            toast(R.string.import_failed)
        }
    }

    private fun shareCrashLog() {
        val log = Crash.log(requireContext()) ?: return
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_title))
        intent.putExtra(Intent.EXTRA_TEXT, log)
        val chooser = Intent.createChooser(intent, getString(R.string.crash_share))
        try {
            startActivity(chooser)
        } catch (e: Exception) {
            toast(R.string.import_failed)
        }
    }
}
