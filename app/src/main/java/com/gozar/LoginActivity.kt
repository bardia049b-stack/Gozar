package com.gozar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LoginActivity : BaseActivity() {

    private companion object {
        const val SIGN_IN_PAGE = "https://accounts.firefox.com/signin"
    }

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var mailBox: TextInputLayout
    private lateinit var secretBox: TextInputLayout
    private lateinit var confirmBox: TextInputLayout
    private lateinit var mail: TextInputEditText
    private lateinit var secret: TextInputEditText
    private lateinit var confirm: TextInputEditText
    private lateinit var primary: MaterialButton
    private lateinit var switchLine: TextView
    private lateinit var social: LinearLayout
    private lateinit var divider: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var notice: TextView

    private var create = false
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        title = findViewById(R.id.loginTitle)
        subtitle = findViewById(R.id.loginSubtitle)
        mailBox = findViewById(R.id.loginMailBox)
        secretBox = findViewById(R.id.loginSecretBox)
        confirmBox = findViewById(R.id.loginConfirmBox)
        mail = findViewById(R.id.loginMail)
        secret = findViewById(R.id.loginSecret)
        confirm = findViewById(R.id.loginConfirm)
        primary = findViewById(R.id.loginPrimary)
        switchLine = findViewById(R.id.loginSwitch)
        social = findViewById(R.id.loginSocial)
        divider = findViewById(R.id.loginDivider)
        progress = findViewById(R.id.loginProgress)
        notice = findViewById(R.id.loginNotice)

        primary.setOnClickListener { act() }
        switchLine.setOnClickListener {
            create = !create
            render()
        }
        findViewById<View>(R.id.loginGoogle).setOnClickListener { web(SIGN_IN_PAGE) }
        findViewById<View>(R.id.loginApple).setOnClickListener { web(SIGN_IN_PAGE) }
        secret.setOnEditorActionListener { _, action, _ ->
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
            notice.text = getString(R.string.login_no_browser)
            notice.visibility = View.VISIBLE
        }
    }

    private fun act() {
        if (Fox.busy) return
        notice.visibility = View.GONE
        if (Fox.needsStep()) {
            if (Fox.isLinkStep()) Fox.confirmLink(this) else Fox.submitCode(this)
            return
        }
        val address = mail.text?.toString()?.trim().orEmpty()
        val password = secret.text?.toString().orEmpty()
        if (address.isEmpty() || password.isEmpty()) {
            notice.text = getString(R.string.login_fill)
            notice.visibility = View.VISIBLE
            return
        }
        if (create && confirm.text?.toString().orEmpty() != password) {
            notice.text = getString(R.string.login_mismatch)
            notice.visibility = View.VISIBLE
            return
        }
        secret.setText("")
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
        social.visibility = if (step) View.GONE else View.VISIBLE
        divider.visibility = if (step) View.GONE else View.VISIBLE
        switchLine.visibility = if (step) View.GONE else View.VISIBLE
        progress.visibility = if (Fox.busy) View.VISIBLE else View.GONE

        title.text = when {
            step -> getString(R.string.login_check_title)
            create -> getString(R.string.login_create_title)
            else -> getString(R.string.login_welcome_title)
        }
        subtitle.text = when {
            step && link -> getString(R.string.login_check_link)
            step -> getString(R.string.login_check_code)
            create -> getString(R.string.login_create_sub)
            else -> getString(R.string.login_welcome_sub)
        }
        primary.text = when {
            step && link -> getString(R.string.login_confirm_action)
            step -> getString(R.string.login_continue)
            create -> getString(R.string.login_create_action)
            else -> getString(R.string.login_action)
        }
        primary.isEnabled = !Fox.busy
        switchLine.text = if (create) getString(R.string.login_have_account) else getString(R.string.login_no_account)

        val trouble = Fox.message.isNotEmpty() && Fox.message != "code" && Fox.message != "link"
        notice.text = when {
            trouble -> Fox.message
            Fox.busy -> Fox.stage.ifEmpty { getString(R.string.login_working) }
            else -> ""
        }
        notice.visibility = if (notice.text.isEmpty()) View.GONE else View.VISIBLE
    }
}
