package com.gozar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView

class LoginActivity : BaseActivity() {

    private companion object {
        const val ACCOUNT_PAGE = "https://accounts.firefox.com/signin"
        const val RESET_PAGE = "https://accounts.firefox.com/reset_password"
    }

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var mailBox: View
    private lateinit var secretBox: View
    private lateinit var confirmBox: View
    private lateinit var codeBox: View
    private lateinit var mail: EditText
    private lateinit var secret: EditText
    private lateinit var confirm: EditText
    private lateinit var codeInput: EditText
    private lateinit var reveal: ImageView
    private var shown = false
    private lateinit var primary: MaterialButton
    private lateinit var forgot: MaterialButton
    private lateinit var switchButton: MaterialButton
    private lateinit var languageButton: MaterialButton
    private lateinit var progress: CircularProgressIndicator
    private lateinit var error: TextView

    private var create = false
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.color.login_bg)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_login)
        create = savedInstanceState?.getBoolean("create", false) ?: false
        languageButton = findViewById(R.id.loginLanguage)
        languageButton.setOnClickListener { if (!Fox.busy) showLanguagePicker(this) }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginRoot)) { _, insets ->
            val params = languageButton.layoutParams as android.widget.FrameLayout.LayoutParams
            params.topMargin = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            languageButton.layoutParams = params
            insets
        }
        title = findViewById(R.id.loginTitle)
        subtitle = findViewById(R.id.loginSubtitle)
        mailBox = findViewById(R.id.loginMailBox)
        secretBox = findViewById(R.id.loginSecretBox)
        confirmBox = findViewById(R.id.loginConfirmBox)
        codeBox = findViewById(R.id.loginCodeBox)
        mail = findViewById(R.id.loginMail)
        secret = findViewById(R.id.loginSecret)
        confirm = findViewById(R.id.loginConfirm)
        codeInput = findViewById(R.id.loginCode)
        primary = findViewById(R.id.loginPrimary)
        forgot = findViewById(R.id.loginForgot)
        switchButton = findViewById(R.id.loginSwitch)
        progress = findViewById(R.id.loginProgress)
        error = findViewById(R.id.loginError)
        reveal = findViewById(R.id.loginReveal)
        reveal.setOnClickListener {
            shown = !shown
            val mode = if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            secret.inputType = mode
            secret.setSelection(secret.text.length)
        }

        primary.setOnClickListener { act() }
        forgot.setOnClickListener { web(RESET_PAGE) }
        switchButton.setOnClickListener {
            create = !create
            error.visibility = View.GONE
            render()
        }
        secret.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                act()
                true
            } else {
                false
            }
        }
        confirm.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                act()
                true
            } else {
                false
            }
        }
        codeInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                act()
                true
            } else {
                false
            }
        }
        Fox.watch(listener)
        Fox.load(this)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("create", create)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        Fox.unwatch(listener)
        super.onDestroy()
    }

    private fun web(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            show(getString(R.string.login_no_browser))
        }
    }

    private fun show(text: String) {
        error.text = text
        error.visibility = View.VISIBLE
    }

    private fun act() {
        if (Fox.busy) return
        error.visibility = View.GONE
        if (Fox.needsStep()) {
            if (Fox.isLinkStep()) Fox.confirmLink(this) else Fox.submitCode(this, codeInput.text?.toString().orEmpty().trim())
            render()
            return
        }
        val address = mail.text?.toString()?.trim().orEmpty()
        val password = secret.text?.toString().orEmpty()
        if (address.isEmpty() || password.isEmpty()) {
            show(getString(R.string.login_fill))
            return
        }
        if (create && confirm.text?.toString().orEmpty() != password) {
            show(getString(R.string.login_mismatch))
            return
        }
        if (create) Fox.signUp(this, address, password) else Fox.signIn(this, address, password)
        render()
    }

    private fun render() {
        val signed = Fox.signedIn(this)
        if (signed && !Fox.busy) {
            secret.text?.clear()
            confirm.text?.clear()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val step = Fox.needsStep()
        val link = Fox.isLinkStep()
        mailBox.visibility = if (step) View.GONE else View.VISIBLE
        secretBox.visibility = if (step) View.GONE else View.VISIBLE
        confirmBox.visibility = if (create && !step) View.VISIBLE else View.GONE
        codeBox.visibility = if (step && !link) View.VISIBLE else View.GONE
        forgot.visibility = if (step || create) View.GONE else View.VISIBLE
        switchButton.visibility = if (step) View.GONE else View.VISIBLE

        title.text = when {
            step -> getString(R.string.login_check_title)
            create -> getString(R.string.login_create_title)
            else -> getString(R.string.login_signin_title)
        }
        subtitle.text = when {
            step && link -> getString(R.string.login_check_link)
            step -> getString(R.string.login_check_code)
            create -> getString(R.string.login_create_sub)
            else -> getString(R.string.login_signin_sub)
        }
        val action = when {
            step && link -> getString(R.string.login_confirm_action)
            step -> getString(R.string.login_continue)
            create -> getString(R.string.login_create_action)
            else -> getString(R.string.login_action)
        }
        val busy = Fox.busy
        primary.text = if (busy) "" else action
        primary.contentDescription = if (busy) getString(R.string.login_working) else null
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        primary.isEnabled = !busy
        languageButton.isEnabled = !busy
        mail.isEnabled = !busy
        secret.isEnabled = !busy
        confirm.isEnabled = !busy
        codeInput.isEnabled = !busy
        reveal.isEnabled = !busy
        forgot.isEnabled = !busy
        switchButton.isEnabled = !busy

        val trouble = !busy && Fox.message.isNotEmpty() &&
            Fox.message != "code" && Fox.message != "link" &&
            Fox.message != "ping" && Fox.message != "servers" && Fox.message != "quota"
        if (trouble) {
            error.text = Fox.message
            error.visibility = View.VISIBLE
        } else {
            error.visibility = View.GONE
        }
    }
}