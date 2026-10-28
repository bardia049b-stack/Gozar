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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class FoxFragment : Fragment() {

    private lateinit var account: TextView
    private lateinit var state: TextView
    private lateinit var quotaText: TextView
    private lateinit var quotaBar: ProgressBar
    private lateinit var reset: TextView
    private lateinit var connect: ConnectControl
    private lateinit var errorView: TextView
    private lateinit var stats: LinearLayout
    private lateinit var statLocation: TextView
    private lateinit var statExit: TextView
    private lateinit var statSpeed: TextView
    private lateinit var statSession: TextView
    private lateinit var measureButton: MaterialButton
    private lateinit var hint: TextView
    private lateinit var serverBox: LinearLayout
    private lateinit var serverHead: View
    private lateinit var serverBody: View
    private lateinit var chevron: View
    private lateinit var selectedLabel: TextView
    private lateinit var signInCard: View
    private lateinit var signInTitle: TextView
    private lateinit var emailBox: TextInputLayout
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordBox: TextInputLayout
    private lateinit var passwordInput: TextInputEditText
    private lateinit var signButtons: View
    private lateinit var codeBox: View
    private lateinit var codeFrame: TextInputLayout
    private lateinit var codeInput: TextInputEditText
    private lateinit var codeHint: TextView
    private lateinit var verifyButton: MaterialButton
    private lateinit var resendButton: MaterialButton
    private var expanded = false
    private var measured = false
    private var rowSignature = ""

    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) FoxVpnService.start(requireContext())
        else showHint(getString(R.string.fox_perm_denied))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_fox, container, false)
        account = view.findViewById(R.id.foxAccount)
        state = view.findViewById(R.id.foxState)
        quotaText = view.findViewById(R.id.foxQuotaText)
        quotaBar = view.findViewById(R.id.foxQuotaBar)
        reset = view.findViewById(R.id.foxReset)
        connect = view.findViewById(R.id.foxConnect)
        errorView = view.findViewById(R.id.foxError)
        stats = view.findViewById(R.id.foxStats)
        statLocation = view.findViewById(R.id.foxStatLocation)
        statExit = view.findViewById(R.id.foxStatExit)
        statSpeed = view.findViewById(R.id.foxStatSpeed)
        statSession = view.findViewById(R.id.foxStatSession)
        measureButton = view.findViewById(R.id.foxMeasure)
        hint = view.findViewById(R.id.foxHint)
        serverBox = view.findViewById(R.id.foxServers)
        serverHead = view.findViewById(R.id.foxServerHead)
        serverBody = view.findViewById(R.id.foxServerBody)
        chevron = view.findViewById(R.id.foxChevron)
        selectedLabel = view.findViewById(R.id.foxSelected)
        signInCard = view.findViewById(R.id.foxSignIn)
        signInTitle = view.findViewById(R.id.foxSignInTitle)
        emailBox = view.findViewById(R.id.foxEmailBox)
        emailInput = view.findViewById(R.id.foxEmailInput)
        passwordBox = view.findViewById(R.id.foxPasswordBox)
        passwordInput = view.findViewById(R.id.foxPasswordInput)
        signButtons = view.findViewById(R.id.foxSignButtons)
        codeBox = view.findViewById(R.id.foxCodeBox)
        codeFrame = view.findViewById(R.id.foxCodeFrame)
        codeInput = view.findViewById(R.id.foxCodeInput)
        codeHint = view.findViewById(R.id.foxCodeHint)
        verifyButton = view.findViewById(R.id.foxVerifyButton)
        resendButton = view.findViewById(R.id.foxResendButton)

        connect.tune(
            requireContext().getColor(R.color.fox_cream),
            requireContext().getColor(R.color.fox_red_dark),
            requireContext().getColor(R.color.fox_peach),
            requireContext().getColor(R.color.fox_red_dark),
            requireContext().getColor(R.color.fox_orange),
            requireContext().getColor(R.color.fox_red_dark)
        )

        view.findViewById<View>(R.id.foxAccountButton).setOnClickListener { accountMenu() }
        connect.setOnClickListener { primaryAction() }
        measureButton.setOnClickListener {
            measured = true
            Fox.measure(requireContext())
        }
        view.findViewById<View>(R.id.foxLogs).setOnClickListener { showLogs() }
        serverHead.setOnClickListener { expand(!expanded) }
        view.findViewById<View>(R.id.foxSignInButton).setOnClickListener { submitAccount(false) }
        view.findViewById<View>(R.id.foxSignUpButton).setOnClickListener { submitAccount(true) }
        verifyButton.setOnClickListener { submitCode() }
        resendButton.setOnClickListener { Fox.resend(requireContext()) }
        return view
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        Fox.onChange = { if (isAdded) render() }
        FoxLink.onChange = { if (isAdded) render() }
        val stored = FoxStore.email(requireContext())
        if (stored.isNotEmpty() && emailInput.text.isNullOrEmpty()) emailInput.setText(stored)
        Fox.load(requireContext())
        Fox.loadServers(requireContext())
        render()
    }

    private fun maybeMeasure() {
        if (measured || Fox.busy) return
        if (Fox.servers.isEmpty()) return
        measured = true
        if (!Fox.hasDelays()) Fox.measure(requireContext())
    }

    private fun hideKeyboard(view: View) {
        val manager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        manager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroyView() {
        Fox.onChange = null
        FoxLink.onChange = null
        super.onDestroyView()
    }

    private fun expand(open: Boolean) {
        expanded = open
        serverBody.visibility = if (open) View.VISIBLE else View.GONE
        chevron.animate().rotation(if (open) 180f else 0f).setDuration(180).start()
        if (open) renderServers(requireContext())
    }

    private fun primaryAction() {
        val c = requireContext()
        when {
            !Fox.signedIn(c) -> {
                showSignIn()
            }
            FoxLink.connected || FoxLink.connecting -> FoxVpnService.stop(c)
            else -> {
                val ask = VpnService.prepare(c)
                if (ask != null) permission.launch(ask) else FoxVpnService.start(c)
            }
        }
    }

    private fun showSignIn() {
        signInCard.visibility = View.VISIBLE
        signInCard.announceForAccessibility(getString(R.string.fox_auth_title))
        signInCard.requestFocus()
    }

    private fun submitAccount(create: Boolean) {
        val address = emailInput.text?.toString()?.trim().orEmpty()
        val secret = passwordInput.text?.toString().orEmpty()
        emailBox.error = null
        passwordBox.error = null
        if (!address.contains("@")) {
            emailBox.error = getString(R.string.fox_email_bad)
            return
        }
        if (secret.length < 8) {
            passwordBox.error = getString(R.string.fox_password_bad)
            return
        }
        hideKeyboard(passwordInput)
        passwordInput.setText("")
        if (create) Fox.signUp(requireContext(), address, secret) else Fox.signIn(requireContext(), address, secret)
    }

    private fun submitCode() {
        val c = requireContext()
        if (Fox.isLinkStep()) {
            Fox.confirmLink(c)
            return
        }
        val code = codeInput.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            codeFrame.error = getString(R.string.fox_code)
            return
        }
        codeFrame.error = null
        codeInput.setText("")
        Fox.submitCode(c, code)
    }

    private fun accountMenu() {
        val c = requireContext()
        if (!Fox.signedIn(c)) {
            showSignIn()
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

    private fun showHint(text: String) {
        if (!isAdded) return
        errorView.visibility = View.VISIBLE
        errorView.text = text
    }

    private fun render() {
        val c = context ?: return
        val mail = FoxStore.email(c)
        val signed = Fox.signedIn(c)
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
            !signed -> getString(R.string.fox_plan_title)
            else -> getString(R.string.fox_plan_title)
        }

        val wanted = when {
            FoxLink.error.isNotEmpty() && !live -> ConnectControl.ERROR
            busy -> ConnectControl.BUSY
            live -> ConnectControl.ON
            else -> ConnectControl.OFF
        }
        if (connect.state != wanted) connect.state = wanted
        if (live) connect.elapsedSeconds = FoxLink.elapsed().toInt() else if (!busy) connect.elapsedSeconds = 0

        val failure = when {
            FoxLink.error.isNotEmpty() -> FoxLink.error
            Fox.message.isNotEmpty() && Fox.message != "code" && Fox.message != "link" && Fox.message != "ping" &&
                Fox.message != "quota" && Fox.message != "servers" -> Fox.message
            else -> ""
        }
        if (failure.isEmpty()) errorView.visibility = View.GONE else showHint(failure)

        signInCard.visibility = if (signed) View.GONE else View.VISIBLE
        signInTitle.text = if (Fox.needsStep()) getString(R.string.fox_code_title) else getString(R.string.fox_auth_title)
        signButtons.visibility = if (Fox.needsStep()) View.GONE else View.VISIBLE
        codeBox.visibility = if (Fox.needsStep()) View.VISIBLE else View.GONE
        if (Fox.needsStep()) {
            val link = Fox.isLinkStep()
            codeFrame.visibility = if (link) View.GONE else View.VISIBLE
            verifyButton.text = if (link) getString(R.string.fox_confirm) else getString(R.string.fox_continue)
            resendButton.visibility = if (link) View.GONE else View.VISIBLE
            codeHint.text = if (link) getString(R.string.fox_link_hint) else getString(R.string.fox_code_hint)
        }

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

        hint.text = if (Fox.busy) getString(R.string.fox_working) else getString(R.string.fox_servers_hint)
        measureButton.isEnabled = Fox.servers.isNotEmpty() && !Fox.busy

        maybeMeasure()
        renderServers(c)
    }

    private fun showLogs() {
        val c = context ?: return
        val view = TextView(c).apply {
            text = FoxLog.dump().ifEmpty { getString(R.string.fox_log_empty) }
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = android.widget.ScrollView(c).apply { addView(view) }
        MaterialAlertDialogBuilder(c)
            .setTitle(getString(R.string.fox_logs))
            .setView(scroll)
            .setPositiveButton(R.string.fox_copy, null)
            .setNeutralButton(R.string.fox_clear, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val clipboard = c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("gozarwg", FoxLog.dump()))
                        dismiss()
                    }
                    getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        FoxLog.clear()
                        dismiss()
                    }
                }
                show()
            }
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
        val selected = Fox.selectedLocation(c)
        val chosen = selected?.code ?: ""
        selectedLabel.text = when {
            selected == null -> ""
            Fox.delayOf(selected) > 0 -> label(selected) + " · " + Fox.delayOf(selected) + " ms"
            else -> label(selected)
        }
        if (!expanded) return
        val list = Fox.servers
        val signature = list.size.toString() + "|" + chosen + "|" + list.joinToString(",") { Fox.delayOf(it).toString() }
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
                if (location.code == chosen) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                Fox.select(c, location)
                rowSignature = ""
                render()
            }
            serverBox.addView(row)
        }
    }

    private fun label(location: FoxLocation): String = when {
        location.city.isEmpty() || location.city.equals(location.country, true) -> location.country
        else -> location.country + " · " + location.city
    }
}
