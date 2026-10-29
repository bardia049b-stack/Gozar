package com.gozar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LoginActivity : BaseActivity() {

    private companion object {
        const val ACCOUNT_PAGE = "https://accounts.firefox.com/signin"
        const val RESET_PAGE = "https://accounts.firefox.com/reset_password"
    }

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var mailBox: TextInputLayout
    private lateinit var secretBox: TextInputLayout
    private lateinit var confirmBox: TextInputLayout
    private lateinit var codeBox: TextInputLayout
    private lateinit var mail: TextInputEditText
    private lateinit var secret: TextInputEditText
    private lateinit var confirm: TextInputEditText
    private lateinit var codeInput: TextInputEditText
    private lateinit var primary: MaterialButton
    private lateinit var forgot: MaterialButton
    private lateinit var switchButton: MaterialButton
    private lateinit var busyRow: LinearLayout
    private lateinit var notice: TextView
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
        busyRow = findViewById(R.id.loginBusy)
        notice = findViewById(R.id.loginNotice)
        error = findViewById(R.id.loginError)

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
        secret.setText("")
        confirm.setText("")
        if (create) Fox.signUp(this, address, password) else Fox.signIn(this, address, password)
    }

    private fun render() {
        if (Fox.signedIn(this) && !Fox.busy) {
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
        busyRow.visibility = if (Fox.busy) View.VISIBLE else View.GONE

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
        primary.text = when {
            step && link -> getString(R.string.login_confirm_action)
            step -> getString(R.string.login_continue)
            create -> getString(R.string.login_create_action)
            else -> getString(R.string.login_action)
        }
        primary.isEnabled = !Fox.busy
        switchButton.text = if (create) getString(R.string.login_have_account) else getString(R.string.login_no_account)
        notice.text = Fox.stage.ifEmpty { getString(R.string.login_working) }

        val trouble = Fox.message.isNotEmpty() && Fox.message != "code" && Fox.message != "link"
        if (trouble) {
            show(Fox.message)
        } else {
            error.visibility = View.GONE
        }
    }
}
