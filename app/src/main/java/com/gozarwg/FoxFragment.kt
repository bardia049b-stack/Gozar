package com.gozarwg

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private lateinit var map: View
    private lateinit var stats: View
    private lateinit var down: RollingValueView
    private lateinit var up: RollingValueView
    private lateinit var total: RollingValueView
    private lateinit var session: TextView
    private lateinit var serverFlag: CountryFlagView
    private lateinit var serverName: TextView
    private lateinit var serverMeta: TextView
    private lateinit var signInCard: View
    private lateinit var signInTitle: TextView
    private lateinit var emailBox: TextInputLayout
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordBox: TextInputLayout
    private lateinit var passwordInput: TextInputEditText
    private lateinit var signInButton: MaterialButton
    private lateinit var signUpButton: MaterialButton
    private lateinit var codeBox: View
    private lateinit var codeFrame: TextInputLayout
    private lateinit var codeInput: TextInputEditText
    private lateinit var codeHint: TextView
    private lateinit var verifyButton: MaterialButton
    private lateinit var resendButton: MaterialButton
    private lateinit var busyRow: View
    private lateinit var busyText: TextView
    private lateinit var signError: TextView
    private var measured = false

    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            FoxVpnService.start(requireContext())
        } else {
            showError(getString(R.string.fox_perm_denied))
        }
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
        map = view.findViewById(R.id.foxMap)
        stats = view.findViewById(R.id.foxStats)
        down = view.findViewById(R.id.foxDown)
        up = view.findViewById(R.id.foxUp)
        total = view.findViewById(R.id.foxTotal)
        session = view.findViewById(R.id.foxSession)
        serverFlag = view.findViewById(R.id.foxServerFlag)
        serverName = view.findViewById(R.id.foxServerName)
        serverMeta = view.findViewById(R.id.foxServerMeta)
        signInCard = view.findViewById(R.id.foxSignIn)
        signInTitle = view.findViewById(R.id.foxSignInTitle)
        emailBox = view.findViewById(R.id.foxEmailBox)
        emailInput = view.findViewById(R.id.foxEmailInput)
        passwordBox = view.findViewById(R.id.foxPasswordBox)
        passwordInput = view.findViewById(R.id.foxPasswordInput)
        signInButton = view.findViewById(R.id.foxSignInButton)
        signUpButton = view.findViewById(R.id.foxSignUpButton)
        codeBox = view.findViewById(R.id.foxCodeBox)
        codeFrame = view.findViewById(R.id.foxCodeFrame)
        codeInput = view.findViewById(R.id.foxCodeInput)
        codeHint = view.findViewById(R.id.foxCodeHint)
        verifyButton = view.findViewById(R.id.foxVerifyButton)
        resendButton = view.findViewById(R.id.foxResendButton)
        busyRow = view.findViewById(R.id.foxBusyRow)
        busyText = view.findViewById(R.id.foxBusyText)
        signError = view.findViewById(R.id.foxSignError)

        connect.tune(
            requireContext().getColor(R.color.fox_cream),
            requireContext().getColor(R.color.fox_red_dark),
            requireContext().getColor(R.color.fox_peach),
            requireContext().getColor(R.color.fox_red_dark),
            requireContext().getColor(R.color.fox_orange),
            requireContext().getColor(R.color.fox_red_dark)
        )

        view.findViewById<View>(R.id.foxAccountButton).setOnClickListener { accountMenu() }
        connect.setOnClickListener { toggle() }
        map.setOnClickListener { startActivity(Intent(requireContext(), RouteMapActivity::class.java)) }
        view.findViewById<View>(R.id.foxServerRow).setOnClickListener { pickServer() }
        signInButton.setOnClickListener { submitAccount(false) }
        signUpButton.setOnClickListener { submitAccount(true) }
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

    override fun onDestroyView() {
        Fox.onChange = null
        FoxLink.onChange = null
        super.onDestroyView()
    }

    private fun toggle() {
        val c = requireContext()
        when {
            !Fox.signedIn(c) -> {
                focusSignIn()
                return
            }
            FoxLink.connected || FoxLink.connecting || FoxVpnService.isAlive() -> disconnect()
            else -> {
                val ask = VpnService.prepare(c)
                if (ask != null) permission.launch(ask) else FoxVpnService.start(c)
            }
        }
    }

    private fun disconnect() {
        val c = requireContext()
        FoxLog.add("link", "disconnect from the card")
        FoxVpnService.stop(c)
        FoxLink.stop()
        render()
    }

    private fun focusSignIn() {
        signInCard.visibility = View.VISIBLE
        if (emailInput.text.isNullOrEmpty()) emailInput.requestFocus() else passwordInput.requestFocus()
        val manager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        manager?.showSoftInput(if (emailInput.text.isNullOrEmpty()) emailInput else passwordInput, 0)
    }

    private fun submitAccount(create: Boolean) {
        val address = emailInput.text?.toString()?.trim().orEmpty()
        val secret = passwordInput.text?.toString().orEmpty()
        emailBox.error = null
        passwordBox.error = null
        signError.visibility = View.GONE
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
        hideKeyboard(codeInput)
        Fox.submitCode(c, code)
    }

    private fun pickServer() {
        FoxServers.show(requireContext()) {
            if (isAdded) render()
        }
    }

    private fun accountMenu() {
        val c = requireContext()
        if (!Fox.signedIn(c)) {
            MaterialAlertDialogBuilder(c)
                .setItems(arrayOf(getString(R.string.fox_sign_in), getString(R.string.fox_logs))) { _, which ->
                    if (which == 0) focusSignIn() else showLogs()
                }
                .show()
            return
        }
        MaterialAlertDialogBuilder(c)
            .setTitle(FoxStore.email(c))
            .setItems(
                arrayOf(
                    getString(R.string.fox_refresh),
                    getString(R.string.fox_logs),
                    getString(R.string.fox_sign_out)
                )
            ) { _, which ->
                when (which) {
                    0 -> Fox.refreshUsage(c)
                    1 -> showLogs()
                    else -> {
                        FoxVpnService.stop(c)
                        Fox.signOut(c)
                    }
                }
            }
            .show()
    }

    private fun showError(text: String) {
        if (!isAdded) return
        errorView.visibility = View.VISIBLE
        errorView.text = text
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

    private fun render() {
        if (!isAdded) return
        val c = requireContext()
        val mail = FoxStore.email(c)
        val signed = Fox.signedIn(c)
        val live = FoxLink.connected
        val busy = FoxLink.connecting || (FoxVpnService.isAlive() && !live)

        account.text = if (mail.isEmpty()) getString(R.string.fox_signed_out) else mail

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

        val wanted = when {
            busy -> ConnectControl.BUSY
            live -> ConnectControl.ON
            FoxLink.error.isNotEmpty() -> ConnectControl.ERROR
            else -> ConnectControl.OFF
        }
        if (connect.state != wanted) connect.state = wanted
        connect.elapsedSeconds = if (live) FoxLink.elapsed().toInt() else 0

        val failure = when {
            FoxLink.error.isNotEmpty() && !live -> FoxLink.error
            else -> ""
        }
        if (failure.isEmpty()) errorView.visibility = View.GONE else showError(failure)

        map.visibility = if (live) View.VISIBLE else View.GONE
        stats.visibility = if (live) View.VISIBLE else View.GONE
        if (live) {
            down.setValue(fmtSpeed(FoxLink.downRate))
            up.setValue(fmtSpeed(FoxLink.upRate))
            total.setValue(fmtBytes(FoxLink.down + FoxLink.up))
            session.text = fmtTime(FoxLink.elapsed())
        }

        paintServer(c)
        paintAccount(c, signed, mail)
        maybeMeasure()
    }

    private fun paintServer(c: Context) {
        val location = Fox.selectedLocation(c)
        if (location == null) {
            serverFlag.visibility = View.INVISIBLE
            serverName.text = getString(R.string.fox_server_empty)
            serverMeta.text = getString(R.string.fox_servers_sub)
            return
        }
        serverFlag.visibility = View.VISIBLE
        serverFlag.countryCode = location.code
        val place = when {
            location.city.isEmpty() || location.city.equals(location.country, true) -> location.country
            else -> location.country + " · " + location.city
        }
        serverName.text = place
        val delay = Fox.delayOf(location)
        val clock = when {
            delay > 0 -> "$delay ms"
            Fox.busy -> getString(R.string.fox_working)
            else -> getString(R.string.fox_ping_wait)
        }
        serverMeta.text = location.host + " · " + clock
    }

    private fun paintAccount(c: Context, signed: Boolean, mail: String) {
        signInCard.visibility = if (signed && !Fox.needsStep()) View.GONE else View.VISIBLE
        val step = Fox.needsStep()
        val link = Fox.isLinkStep()
        signInTitle.text = if (step) getString(R.string.fox_code_title) else getString(R.string.fox_auth_title)
        emailInput.isEnabled = !step && !Fox.busy
        passwordInput.isEnabled = !step && !Fox.busy
        signInButton.isEnabled = !step && !Fox.busy
        signUpButton.isEnabled = !step && !Fox.busy
        codeBox.visibility = if (step) View.VISIBLE else View.GONE
        codeFrame.visibility = if (link) View.GONE else View.VISIBLE
        codeHint.text = if (link) getString(R.string.fox_link_hint) else getString(R.string.fox_code_hint)
        verifyButton.text = if (link) getString(R.string.fox_confirm) else getString(R.string.fox_continue)
        verifyButton.isEnabled = !Fox.busy
        resendButton.visibility = if (link) View.GONE else View.VISIBLE
        resendButton.isEnabled = !Fox.busy
        signInButton.text = when {
            Fox.busy && Fox.stage.isNotEmpty() -> Fox.stage
            step -> getString(R.string.fox_continue)
            else -> getString(R.string.fox_sign_in)
        }
        busyRow.visibility = if (Fox.busy) View.VISIBLE else View.GONE
        busyText.text = Fox.stage.ifEmpty { getString(R.string.fox_working) }
        val trouble = Fox.message.isNotEmpty() && Fox.message != "code" && Fox.message != "link" &&
            Fox.message != "ping" && Fox.message != "quota" && Fox.message != "servers"
        if (trouble) {
            signError.visibility = View.VISIBLE
            signError.text = Fox.message
        } else {
            signError.visibility = View.GONE
        }
        if (mail.isNotEmpty() && emailInput.text.isNullOrEmpty()) emailInput.setText(mail)
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
}
