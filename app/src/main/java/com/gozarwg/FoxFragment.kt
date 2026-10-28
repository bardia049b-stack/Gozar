package com.gozarwg

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

class FoxFragment : Fragment() {

    private lateinit var account: TextView
    private lateinit var state: TextView
    private lateinit var quotaText: TextView
    private lateinit var quotaBar: ProgressBar
    private lateinit var reset: TextView
    private lateinit var primary: MaterialButton
    private lateinit var errorView: TextView
    private lateinit var stats: LinearLayout
    private lateinit var statLocation: TextView
    private lateinit var statExit: TextView
    private lateinit var statSpeed: TextView
    private lateinit var statSession: TextView
    private lateinit var measureButton: MaterialButton
    private lateinit var hint: TextView
    private lateinit var serverBox: LinearLayout
    private var codeShown = false
    private var rowSignature = ""

    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) FoxVpnService.start(requireContext()) else showHint(getString(R.string.fox_perm_denied))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_fox, container, false)
        account = view.findViewById(R.id.foxAccount)
        state = view.findViewById(R.id.foxState)
        quotaText = view.findViewById(R.id.foxQuotaText)
        quotaBar = view.findViewById(R.id.foxQuotaBar)
        reset = view.findViewById(R.id.foxReset)
        primary = view.findViewById(R.id.foxPrimary)
        errorView = view.findViewById(R.id.foxError)
        stats = view.findViewById(R.id.foxStats)
        statLocation = view.findViewById(R.id.foxStatLocation)
        statExit = view.findViewById(R.id.foxStatExit)
        statSpeed = view.findViewById(R.id.foxStatSpeed)
        statSession = view.findViewById(R.id.foxStatSession)
        measureButton = view.findViewById(R.id.foxMeasure)
        hint = view.findViewById(R.id.foxHint)
        serverBox = view.findViewById(R.id.foxServers)

        view.findViewById<View>(R.id.foxAccountButton).setOnClickListener { accountMenu() }
        primary.setOnClickListener { primaryAction() }
        measureButton.setOnClickListener { Fox.measure(requireContext()) }
        return view
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        Fox.onChange = { if (isAdded) render() }
        FoxLink.onChange = { if (isAdded) render() }
        Fox.load(requireContext())
        Fox.loadServers(requireContext())
        render()
    }

    override fun onDestroyView() {
        Fox.onChange = null
        FoxLink.onChange = null
        super.onDestroyView()
    }

    private fun primaryAction() {
        val c = requireContext()
        when {
            !Fox.signedIn(c) -> showAuth()
            FoxLink.connected || FoxLink.connecting -> FoxVpnService.stop(c)
            else -> {
                val ask = VpnService.prepare(c)
                if (ask != null) permission.launch(ask) else FoxVpnService.start(c)
            }
        }
    }

    private fun accountMenu() {
        val c = requireContext()
        if (!Fox.signedIn(c)) {
            showAuth()
            return
        }
        MaterialAlertDialogBuilder(c)
            .setTitle(FoxStore.email(c))
            .setItems(arrayOf(getString(R.string.fox_refresh), getString(R.string.fox_sign_out))) { _, which ->
                if (which == 0) Fox.refreshUsage(c) else {
                    FoxVpnService.stop(c)
                    Fox.signOut(c)
                }
            }
            .show()
    }

    private fun showAuth() {
        val c = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_fox_auth, null)
        val mail = view.findViewById<TextInputEditText>(R.id.foxEmail)
        val password = view.findViewById<TextInputEditText>(R.id.foxPassword)
        val create = view.findViewById<MaterialCheckBox>(R.id.foxCreate)
        val dialog = MaterialAlertDialogBuilder(c)
            .setTitle(R.string.fox_auth_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.fox_continue, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = mail.text?.toString()?.trim().orEmpty()
                val secret = password.text?.toString().orEmpty()
                if (!address.contains("@") || secret.length < 8) {
                    if (!address.contains("@")) mail.error = getString(R.string.fox_email_bad)
                    else password.error = getString(R.string.fox_password_bad)
                    return@setOnClickListener
                }
                dialog.dismiss()
                if (create.isChecked) Fox.signUp(requireContext(), address, secret)
                else Fox.signIn(requireContext(), address, secret)
            }
        }
        dialog.show()
    }

    private fun showCode() {
        if (codeShown || !isAdded) return
        codeShown = true
        val c = requireContext()
        val link = Fox.isLinkStep()
        val view = layoutInflater.inflate(R.layout.dialog_fox_code, null)
        val input = view.findViewById<TextInputEditText>(R.id.foxCode)
        val hintView = view.findViewById<TextView>(R.id.foxCodeHint)
        if (link) {
            input.visibility = View.GONE
            (input.parent?.parent as? View)?.visibility = View.GONE
            hintView.text = getString(R.string.fox_link_hint)
        }
        val dialog = MaterialAlertDialogBuilder(c)
            .setTitle(if (link) R.string.fox_link_title else R.string.fox_code_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.fox_send_again, null)
            .setPositiveButton(if (link) R.string.fox_confirm else R.string.fox_continue, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (link) {
                    dialog.dismiss()
                    Fox.confirmLink(requireContext())
                    return@setOnClickListener
                }
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                Fox.submitCode(requireContext(), code)
            }
            val neutral = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
            if (link) neutral.visibility = View.GONE else neutral.setOnClickListener { Fox.resend(requireContext()) }
        }
        dialog.setOnDismissListener { if (Fox.needsStep()) codeShown = false }
        dialog.show()
    }

    private fun showHint(text: String) {
        if (!isAdded) return
        errorView.visibility = View.VISIBLE
        errorView.text = text
    }

    private fun render() {
        val c = context ?: return
        val mail = FoxStore.email(c)
        account.text = if (mail.isEmpty()) getString(R.string.fox_signed_out) else mail
        val live = FoxLink.connected
        val busy = FoxLink.connecting

        if (Fox.limit > 0) {
            val used = (Fox.limit - Fox.remaining).coerceAtLeast(0)
            quotaText.text = getString(R.string.fox_quota_value, fmtBytes(used), fmtBytes(Fox.limit))
            quotaBar.progress = ((used * 100) / Fox.limit).toInt().coerceIn(0, 100)
            reset.visibility = View.VISIBLE
            reset.text = if (Fox.reset.isEmpty()) "" else getString(R.string.fox_reset, resetText(Fox.reset))
        } else {
            quotaText.text = getString(R.string.fox_quota_empty)
            quotaBar.progress = 0
            reset.visibility = View.GONE
        }

        state.text = when {
            live -> getString(R.string.fox_connected)
            busy -> getString(R.string.fox_connecting)
            else -> getString(R.string.fox_plan_title)
        }

        primary.text = when {
            mail.isEmpty() -> getString(R.string.fox_sign_in)
            live || busy -> getString(R.string.disconnect)
            else -> getString(R.string.connect)
        }
        measureButton.isEnabled = Fox.servers.isNotEmpty() && !Fox.busy

        val failure = when {
            FoxLink.error.isNotEmpty() -> FoxLink.error
            Fox.message.isNotEmpty() && Fox.message != "code" && Fox.message != "link" && Fox.message != "ping" &&
                Fox.message != "quota" && Fox.message != "servers" -> Fox.message
            else -> ""
        }
        if (failure.isEmpty()) errorView.visibility = View.GONE else showHint(failure)

        stats.visibility = if (live) View.VISIBLE else View.GONE
        if (live) {
            val place = FoxLink.location
            statLocation.text = when {
                place == null -> "—"
                place.city.isEmpty() || place.city.equals(place.country, true) ->
                    if (place.recommended) getString(R.string.fox_recommended) else place.country
                else -> place.country + " · " + place.city
            }
            statExit.text = FoxLink.exitIp.ifEmpty { "—" }
            statSpeed.text = "↓ " + fmtSpeed(FoxLink.downRate) + "  ↑ " + fmtSpeed(FoxLink.upRate)
            statSession.text = fmtTime(FoxLink.elapsed())
        }

        hint.text = if (Fox.busy || busy) getString(R.string.fox_working) else
            getString(R.string.fox_build, versionName())

        if (Fox.needsStep()) {
            if (!codeShown) showCode()
        } else {
            codeShown = false
        }
        renderServers(c)
    }

    private fun versionName(): String {
        val c = context ?: return ""
        return try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun resetText(raw: String): String {
        val plain = raw.trim()
        val seconds = plain.toLongOrNull()
        val millis = when {
            seconds != null && seconds > 0 -> seconds * 1000L
            else -> try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(plain.take(19))?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
        if (millis <= 0L) return plain
        return android.text.format.DateFormat.getMediumDateFormat(context ?: return plain)
            .format(java.util.Date(millis))
    }

    private fun renderServers(c: Context) {
        val selected = FoxStore.selected(c)
        val list = Fox.servers
        val signature = list.size.toString() + "|" + selected + "|" + list.joinToString(",") { Fox.delayOf(it).toString() }
        if (signature == rowSignature) return
        rowSignature = signature
        serverBox.removeAllViews()
        for (location in list) {
            val row = layoutInflater.inflate(R.layout.item_fox_server, serverBox, false)
            row.findViewById<CountryFlagView>(R.id.foxFlag).countryCode = location.code
            row.findViewById<TextView>(R.id.foxCountry).text = location.country
            val city = row.findViewById<TextView>(R.id.foxCity)
            city.text = if (location.city.isEmpty()) getString(R.string.fox_recommended) else location.city
            val delay = Fox.delayOf(location)
            row.findViewById<TextView>(R.id.foxDelay).text = when {
                delay < 0 -> getString(R.string.fox_ping_wait)
                delay == 0 -> "—"
                else -> "$delay ms"
            }
            row.findViewById<View>(R.id.foxCheck).visibility =
                if (location.code == selected) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                Fox.select(c, location)
                rowSignature = ""
                render()
            }
            serverBox.addView(row)
        }
    }
}
