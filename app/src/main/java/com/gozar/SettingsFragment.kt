package com.gozar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator

class SettingsFragment : Fragment() {

    private var updateChecking = false
    private val listener: () -> Unit = { if (isAdded && view != null) paint() }

    private fun <T : View> find(id: Int): T = view!!.findViewById(id)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.activity_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bind(R.id.rowGeo, R.id.swGeo, Store.geolocation(requireContext())) { Store.setGeolocation(requireContext(), it) }
        bind(R.id.rowRecents, R.id.swRecents, Store.recents(requireContext())) { Store.setRecents(requireContext(), it) }

        find<View>(R.id.btnBack).setOnClickListener { (activity as? MainActivity)?.showHome() }
        find<View>(R.id.rowLang).setOnClickListener { pickLanguage() }
        find<View>(R.id.rowTheme).setOnClickListener { pickTheme() }
        find<View>(R.id.rowUpdate).setOnClickListener { checkUpdates() }
        find<View>(R.id.rowGithub).setOnClickListener { openProject() }
        find<View>(R.id.rowAbout).setOnClickListener { showLegalNotice() }
        find<View>(R.id.btnAccountRefresh).setOnClickListener {
            Fox.refreshUsage(requireContext())
            toast(R.string.account_refreshing)
        }
        find<View>(R.id.btnSignOut).setOnClickListener { askSignOut() }
        Fox.watch(listener)
        paint()
    }

    override fun onDestroyView() {
        Fox.unwatch(listener)
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    fun restore(intent: Intent?) {
        paint()
    }

    private fun bind(row: Int, switchId: Int, initial: Boolean, save: (Boolean) -> Unit) {
        val toggle = find<MaterialSwitch>(switchId)
        toggle.isChecked = initial
        toggle.setOnCheckedChangeListener { _, checked -> save(checked) }
        find<View>(row).setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    private fun paint() {
        val context = context ?: return
        val signed = Fox.signedIn(context)
        val mail = FoxStore.email(context)
        find<TextView>(R.id.tvAccountEmail).text =
            if (signed) mail else getString(R.string.fox_signed_out)
        find<TextView>(R.id.tvAccountInitial).text =
            if (signed && mail.isNotEmpty()) mail.substring(0, 1).uppercase() else "?"
        val limit = Fox.limit
        val used = (limit - Fox.remaining).coerceAtLeast(0)
        find<TextView>(R.id.tvAccountPlan).text = when {
            limit <= 0 -> getString(R.string.account_free)
            else -> getString(R.string.account_usage, plain(used), plain(limit))
        }
        val bar = find<LinearProgressIndicator>(R.id.pbAccountUsage)
        bar.progress = if (limit > 0) ((used * 100) / limit).toInt().coerceIn(0, 100) else 0
        find<View>(R.id.btnAccountRefresh).isEnabled = signed

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
        find<TextView>(R.id.tvAbout).text = getString(R.string.version_license, version)
        val available = Store.updateVersion(context)
        find<TextView>(R.id.tvUpdate).text = when {
            updateChecking -> getString(R.string.update_checking)
            available.isNotBlank() -> getString(R.string.update_available, available)
            else -> getString(R.string.update_current, version)
        }
    }

    private fun askSignOut() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.fox_sign_out)
            .setMessage(R.string.sign_out_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.fox_sign_out) { _, _ -> signOut() }
            .show()
    }

    private fun signOut() {
        val context = requireContext()
        Fox.signOut(context)
        val intent = Intent(context, LoginActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        activity?.finish()
    }

    private fun checkUpdates() {
        if (updateChecking) return
        updateChecking = true
        paint()
        UpdateChecker.check(requireContext(), notify = false) updateResult@{ result ->
            if (!isAdded || view == null) return@updateResult
            updateChecking = false
            when (result) {
                is UpdateChecker.Result.Available -> {
                    Store.setUpdate(requireContext(), result.version, result.url)
                    paint()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.update_title)
                        .setMessage(getString(R.string.update_message, result.version))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.update_action) { _, _ -> openUrl(result.url) }
                        .show()
                }
                is UpdateChecker.Result.Current -> {
                    paint()
                    toast(R.string.update_latest)
                }
                is UpdateChecker.Result.Failed -> {
                    paint()
                    toast(R.string.update_failed)
                }
            }
        }
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

    private fun openProject() = openUrl("https://github.com/bardia049b-stack/Gozar")

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(R.string.import_failed)
        }
    }

    private fun showLegalNotice() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.legal_title)
            .setMessage(R.string.legal_notice)
            .setNeutralButton(R.string.source_code) { _, _ -> openProject() }
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun toast(res: Int) {
        try {
            android.widget.Toast.makeText(requireContext(), res, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
        }
    }

    private fun plain(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = -1
        while (size >= 1024 && unit < units.size - 1) {
            size /= 1024
            unit++
        }
        return String.format("%.1f %s", size, units[unit])
    }
}
