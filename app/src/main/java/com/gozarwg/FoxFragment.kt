package com.gozarwg

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
    private lateinit var measureButton: MaterialButton
    private lateinit var hint: TextView
    private lateinit var serverBox: LinearLayout
    private var codeShown = false
    private var rowSignature = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_fox, container, false)
        account = view.findViewById(R.id.foxAccount)
        state = view.findViewById(R.id.foxState)
        quotaText = view.findViewById(R.id.foxQuotaText)
        quotaBar = view.findViewById(R.id.foxQuotaBar)
        reset = view.findViewById(R.id.foxReset)
        primary = view.findViewById(R.id.foxPrimary)
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
        Fox.load(requireContext())
        Fox.loadServers(requireContext())
        render()
    }

    override fun onDestroyView() {
        Fox.onChange = null
        super.onDestroyView()
    }

    private fun primaryAction() {
        val c = requireContext()
        if (!Fox.signedIn(c)) showAuth() else Fox.refreshUsage(c)
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
                if (which == 0) Fox.refreshUsage(c) else Fox.signOut(c)
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
        val view = layoutInflater.inflate(R.layout.dialog_fox_code, null)
        val input = view.findViewById<TextInputEditText>(R.id.foxCode)
        val dialog = MaterialAlertDialogBuilder(c)
            .setTitle(R.string.fox_code_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.fox_continue, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                Fox.submitCode(requireContext(), code)
            }
        }
        dialog.setOnDismissListener { if (Fox.needsCode()) codeShown = false }
        dialog.show()
    }

    private fun render() {
        val c = context ?: return
        val mail = FoxStore.email(c)
        account.text = if (mail.isEmpty()) getString(R.string.fox_signed_out) else mail

        if (Fox.limit > 0) {
            val used = (Fox.limit - Fox.remaining).coerceAtLeast(0)
            quotaText.text = getString(R.string.fox_quota_value, fmtBytes(used), fmtBytes(Fox.limit))
            quotaBar.progress = ((used * 100) / Fox.limit).toInt().coerceIn(0, 100)
            state.text = getString(R.string.fox_plan_title)
            reset.visibility = View.VISIBLE
            reset.text = if (Fox.reset.isEmpty()) "" else getString(R.string.fox_reset, resetText(Fox.reset))
        } else {
            quotaText.text = getString(R.string.fox_quota_empty)
            quotaBar.progress = 0
            state.text = getString(R.string.fox_plan_title)
            reset.visibility = View.GONE
        }

        primary.text = if (mail.isEmpty()) getString(R.string.fox_sign_in) else getString(R.string.fox_refresh)
        measureButton.isEnabled = Fox.servers.isNotEmpty() && !Fox.busy

        val note = Fox.message
        hint.text = when {
            Fox.busy -> getString(R.string.fox_working)
            note.isNotEmpty() && note != "code" && note != "ping" && note != "quota" && note != "servers" -> note
            else -> getString(R.string.fox_servers_hint)
        }

        if (Fox.needsCode()) {
            if (!codeShown) showCode()
        } else {
            codeShown = false
        }
        renderServers(c)
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

    private fun renderServers(c: android.content.Context) {
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
