package com.beofoq.wg

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : BaseActivity() {
    private lateinit var powerRing: View
    private lateinit var pulse: View
    private lateinit var powerBtn: View
    private lateinit var powerIcon: ImageView
    private lateinit var busy: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvDuration: TextView
    private lateinit var statsBox: View
    private lateinit var flagImg: ImageView
    private lateinit var tvFlag: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvUp: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvHandshake: TextView
    private lateinit var llProfiles: LinearLayout
    private lateinit var tvEmpty: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pendingProfile: Profile? = null
    private var pendingExport: Profile? = null
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastTime = 0L
    private var ipFor = 0L
    private var pulseAnim: ObjectAnimator? = null

    companion object {
        const val REQ_VPN = 7
        const val REQ_FILE = 11
        const val REQ_GALLERY = 12
        const val REQ_SCAN = 13
        const val REQ_EXPORT = 14
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        powerRing = findViewById(R.id.powerRing)
        pulse = findViewById(R.id.pulse)
        powerBtn = findViewById(R.id.powerBtn)
        powerIcon = findViewById(R.id.powerIcon)
        busy = findViewById(R.id.busy)
        tvStatus = findViewById(R.id.tvStatus)
        tvDuration = findViewById(R.id.tvDuration)
        statsBox = findViewById(R.id.statsBox)
        flagImg = findViewById(R.id.flagImg)
        tvFlag = findViewById(R.id.tvFlag)
        tvLocation = findViewById(R.id.tvLocation)
        tvIp = findViewById(R.id.tvIp)
        tvDown = findViewById(R.id.tvDown)
        tvUp = findViewById(R.id.tvUp)
        tvTotal = findViewById(R.id.tvTotal)
        tvHandshake = findViewById(R.id.tvHandshake)
        llProfiles = findViewById(R.id.llProfiles)
        tvEmpty = findViewById(R.id.tvEmpty)

        powerBtn.setOnClickListener { onPower() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showAddSheet() }
        findViewById<View>(R.id.btnHome).setOnClickListener { }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageView>(R.id.tabHomeIcon).setColorFilter(getColor(R.color.accent))
        Vpn.onError = { msg -> toast(getString(R.string.connection_failed) + ": " + msg.take(80)) }
        renderProfiles()
        updateStatus()
        handleIntent(intent)
        tick()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Vpn.listener = { updateStatus() }
        renderProfiles()
        updateStatus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tick() {
        handler.postDelayed({
            if (Vpn.up) {
                tvDuration.text = fmtTime((System.currentTimeMillis() - Vpn.since) / 1000)
                refreshStats()
                maybeFetchIp()
            }
            tick()
        }, 1000)
    }

    private fun onPower() {
        if (Vpn.connecting) return
        if (Vpn.up || Vpn.profile != null) {
            Vpn.disconnect()
            return
        }
        val p = selectedProfile()
        if (p == null) {
            toast(R.string.select_first)
            return
        }
        if (Store.proxy(this)) {
            Vpn.connect(applicationContext, p)
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingProfile = p
            startActivityForResult(intent, REQ_VPN)
        } else {
            Vpn.connect(applicationContext, p)
        }
    }

    private fun selectedProfile(): Profile? {
        val list = Profiles.all(this)
        if (list.isEmpty()) return null
        val sel = Store.selected(this)
        return list.firstOrNull { it.id == sel } ?: list.first()
    }

    private fun updateStatus() {
        val up = Vpn.up
        val connecting = Vpn.connecting
        busy.visibility = if (connecting) View.VISIBLE else View.GONE
        tvStatus.text = when {
            connecting && !up -> getString(R.string.status_connecting)
            connecting && up -> getString(R.string.status_disconnecting)
            up -> getString(R.string.status_on)
            BlockService.running -> getString(R.string.kill_switch)
            else -> getString(R.string.status_off)
        }
        tvStatus.setTextColor(getColor(if (up) R.color.green else R.color.text))
        powerRing.setBackgroundResource(if (up) R.drawable.ring_on else R.drawable.ring_off)
        powerIcon.setColorFilter(getColor(if (up) R.color.green else R.color.text2))
        statsBox.visibility = if (up) View.VISIBLE else View.GONE
        tvDuration.visibility = if (up) View.VISIBLE else View.GONE
        if (up) {
            startPulse()
        } else {
            stopPulse()
            lastRx = -1
            ipFor = 0
        }
        renderProfiles()
    }

    private fun startPulse() {
        if (pulseAnim != null) return
        pulse.visibility = View.VISIBLE
        val a = ObjectAnimator.ofFloat(pulse, "alpha", 0.9f, 0.15f)
        a.duration = 1400
        a.repeatCount = ObjectAnimator.INFINITE
        a.repeatMode = ObjectAnimator.REVERSE
        a.interpolator = LinearInterpolator()
        a.start()
        pulseAnim = a
    }

    private fun stopPulse() {
        pulseAnim?.cancel()
        pulseAnim = null
        pulse.visibility = View.GONE
    }

    private fun refreshStats() {
        thread {
            val st = Vpn.stats() ?: return@thread
            val rx = st.totalRx()
            val tx = st.totalTx()
            val now = SystemClock.elapsedRealtime()
            var down = 0.0
            var upSpeed = 0.0
            if (lastRx >= 0 && now > lastTime) {
                val dt = (now - lastTime) / 1000.0
                down = (rx - lastRx) / dt
                upSpeed = (tx - lastTx) / dt
            }
            lastRx = rx
            lastTx = tx
            lastTime = now
            val age = Vpn.handshakeAgeSec()
            runOnUiThread {
                if (!Vpn.up) return@runOnUiThread
                tvDown.text = fmtSpeed(down)
                tvUp.text = fmtSpeed(upSpeed)
                tvTotal.text = getString(R.string.total) + ": " + fmtBytes(rx + tx) + "  ↓ " + fmtBytes(rx) + "  ↑ " + fmtBytes(tx)
                tvHandshake.text = getString(R.string.handshake) + ": " + when {
                    age < 0 -> "--"
                    age < 5 -> getString(R.string.now)
                    age < 60 -> String.format(getString(R.string.sec_ago), age.toInt())
                    else -> String.format(getString(R.string.min_ago), (age / 60).toInt())
                }
            }
        }
    }

    private fun maybeFetchIp() {
        if (Store.proxy(this)) {
            tvLocation.text = "SOCKS5"
            tvIp.text = "127.0.0.1:" + Store.proxyPort(this)
            tvFlag.text = ""
            flagImg.visibility = View.GONE
            return
        }
        if (ipFor == Vpn.since || Vpn.since == 0L) return
        ipFor = Vpn.since
        thread {
            Thread.sleep(1200)
            val info = Ip.fetch() ?: return@thread
            runOnUiThread {
                if (!Vpn.up) return@runOnUiThread
                tvIp.text = getString(R.string.ip) + ": " + info.ip
                tvLocation.text = listOf(info.city, info.country).filter { it.isNotBlank() }.joinToString(", ")
                if (info.code == "IR") {
                    flagImg.setImageResource(R.drawable.flag_ir)
                    flagImg.visibility = View.VISIBLE
                    tvFlag.text = ""
                } else {
                    flagImg.visibility = View.GONE
                    tvFlag.text = Ip.flag(info.code)
                }
            }
        }
    }

    private fun renderProfiles() {
        if (!::llProfiles.isInitialized) return
        llProfiles.removeAllViews()
        val list = Profiles.all(this)
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        llProfiles.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        var sel = Store.selected(this)
        if (list.none { it.id == sel } && list.isNotEmpty()) {
            sel = list.first().id
            Store.setSelected(this, sel)
        }
        list.forEachIndexed { index, p ->
            val active = Vpn.profile?.id == p.id && (Vpn.up || Vpn.connecting)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(16), dp(14), dp(10), dp(14))
            row.isClickable = true
            row.setOnClickListener {
                Store.setSelected(this, p.id)
                renderProfiles()
            }

            val dot = View(this)
            dot.layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
            dot.setBackgroundResource(if (active && Vpn.up) R.drawable.dot_green else R.drawable.dot_grey)
            row.addView(dot)

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val colParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            colParams.marginStart = dp(12)
            col.layoutParams = colParams

            val name = TextView(this)
            name.text = p.name
            name.textSize = 16f
            name.setTypeface(name.typeface, Typeface.BOLD)
            name.setTextColor(getColor(R.color.text))
            col.addView(name)

            val sub = TextView(this)
            sub.text = if (p.endpoint.isBlank()) getString(R.string.endpoint_unknown) else p.endpoint
            sub.textSize = 12f
            sub.setTextColor(getColor(R.color.text2))
            col.addView(sub)
            row.addView(col)

            val badge = TextView(this)
            badge.text = if (p.awg) "AmneziaWG" else "WireGuard"
            badge.textSize = 10f
            badge.setTypeface(badge.typeface, Typeface.BOLD)
            badge.setTextColor(getColor(R.color.accent))
            badge.setBackgroundResource(R.drawable.chip)
            badge.setPadding(dp(9), dp(4), dp(9), dp(4))
            row.addView(badge)

            if (p.id == sel) {
                val check = ImageView(this)
                check.setImageResource(R.drawable.ic_check)
                check.setColorFilter(getColor(R.color.accent))
                val cp = LinearLayout.LayoutParams(dp(20), dp(20))
                cp.marginStart = dp(10)
                check.layoutParams = cp
                row.addView(check)
            }

            val menu = TextView(this)
            menu.text = "⋯"
            menu.textSize = 20f
            menu.gravity = Gravity.CENTER
            menu.setTextColor(getColor(R.color.text2))
            menu.layoutParams = LinearLayout.LayoutParams(dp(38), dp(40))
            menu.setOnClickListener { showProfileMenu(it, p) }
            row.addView(menu)

            llProfiles.addView(row)
            if (index < list.size - 1) {
                val divider = View(this)
                val dpParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                dpParams.marginStart = dp(52)
                divider.layoutParams = dpParams
                divider.setBackgroundColor(getColor(R.color.separator))
                llProfiles.addView(divider)
            }
        }
    }

    private fun showProfileMenu(anchor: View, p: Profile) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.menu_edit)
        menu.menu.add(0, 2, 0, R.string.menu_qr)
        menu.menu.add(0, 3, 0, R.string.menu_share)
        menu.menu.add(0, 4, 0, R.string.menu_export)
        menu.menu.add(0, 5, 0, R.string.menu_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openEditor(p.id)
                2 -> showQr(p)
                3 -> shareProfile(p)
                4 -> exportProfile(p)
                5 -> confirmDelete(p)
            }
            true
        }
        menu.show()
    }

    private fun openEditor(id: String?) {
        val intent = Intent(this, EditActivity::class.java)
        if (id != null) intent.putExtra("id", id)
        startActivity(intent)
    }

    private fun shareProfile(p: Profile) {
        toast(R.string.share_warn)
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, p.text)
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun exportProfile(p: Profile) {
        pendingExport = p
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        intent.putExtra(Intent.EXTRA_TITLE, p.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".conf")
        startActivityForResult(intent, REQ_EXPORT)
    }

    private fun confirmDelete(p: Profile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.del_title)
            .setMessage(String.format(getString(R.string.del_msg), p.name))
            .setPositiveButton(R.string.yes) { _, _ ->
                if (Vpn.profile?.id == p.id) Vpn.disconnect()
                Profiles.delete(this, p.id)
                renderProfiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showQr(p: Profile) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_qr)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.findViewById<TextView>(R.id.tvQrName).text = p.name
        val iv = dialog.findViewById<ImageView>(R.id.ivQr)
        var bmp: Bitmap? = null
        thread {
            val made = Qr.make(p.text)
            bmp = made
            runOnUiThread { iv.setImageBitmap(made) }
        }
        dialog.findViewById<View>(R.id.btnQrSave).setOnClickListener {
            val b = bmp
            if (b != null && saveToGallery(p.name, b)) toast(R.string.qr_saved)
        }
        dialog.findViewById<View>(R.id.btnQrShare).setOnClickListener { shareProfile(p) }
        dialog.show()
    }

    private fun saveToGallery(name: String, bmp: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BeOfoq")
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun showAddSheet() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.sheet_add)
        val window = dialog.window
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setGravity(Gravity.BOTTOM)
        window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.findViewById<View>(R.id.optFile).setOnClickListener {
            dialog.dismiss()
            pickFile()
        }
        dialog.findViewById<View>(R.id.optQr).setOnClickListener {
            dialog.dismiss()
            startActivityForResult(Intent(this, ScanActivity::class.java), REQ_SCAN)
        }
        dialog.findViewById<View>(R.id.optGallery).setOnClickListener {
            dialog.dismiss()
            pickGallery()
        }
        dialog.findViewById<View>(R.id.optPaste).setOnClickListener {
            dialog.dismiss()
            pasteFromClipboard()
        }
        dialog.findViewById<View>(R.id.optLink).setOnClickListener {
            dialog.dismiss()
            showLinkDialog()
        }
        dialog.findViewById<View>(R.id.optCreate).setOnClickListener {
            dialog.dismiss()
            openEditor(null)
        }
        dialog.show()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, REQ_FILE)
    }

    private fun pickGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        startActivityForResult(intent, REQ_GALLERY)
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = cm.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast(R.string.clipboard_empty)
            return
        }
        importText(text, null)
    }

    private fun showLinkDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.link_hint)
        input.setSingleLine(false)
        AlertDialog.Builder(this)
            .setTitle(R.string.link_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val raw = input.text.toString()
                if (raw.isNotBlank()) importText(raw, Link.nameOf(raw))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun importText(raw: String, suggestedName: String?) {
        val cfgText = Link.parse(raw)
        if (cfgText == null) {
            toast(R.string.import_failed)
            return
        }
        val host = Regex("(?m)^\\s*Endpoint\\s*=\\s*([^:\\s]+)").find(cfgText)?.groupValues?.get(1)
        val guess = suggestedName ?: host ?: getString(R.string.unnamed)
        AlertDialog.Builder(this)
            .setTitle(R.string.import_title)
            .setMessage(String.format(getString(R.string.import_msg), guess))
            .setPositiveButton(R.string.yes) { _, _ ->
                val p = Profiles.add(this, cfgText, suggestedName)
                if (p == null) {
                    toast(R.string.import_failed)
                } else {
                    Store.setSelected(this, p.id)
                    toast(R.string.imported)
                    renderProfiles()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "content" || data.scheme == "file") {
                    thread {
                        val text = readUriText(data)
                        runOnUiThread { if (text != null) importText(text, fileNameOf(data)) }
                    }
                } else {
                    val raw = data.toString()
                    importText(raw, Link.nameOf(raw))
                }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) importText(text, null)
            }
        }
    }

    private fun readUriText(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun fileNameOf(uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
            }
        } catch (e: Exception) {
        }
        if (name == null) name = uri.lastPathSegment
        return name?.substringBeforeLast(".")?.replace("_", " ")?.replace("-", " ")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            val p = pendingProfile
            pendingProfile = null
            if (resultCode == RESULT_OK && p != null) {
                Vpn.connect(applicationContext, p)
            } else {
                toast(R.string.vpn_perm)
            }
            return
        }
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_FILE -> {
                val uri = data?.data ?: return
                thread {
                    val text = readUriText(uri)
                    runOnUiThread {
                        if (text == null) toast(R.string.import_failed) else importText(text, fileNameOf(uri))
                    }
                }
            }
            REQ_GALLERY -> {
                val uri = data?.data ?: return
                thread {
                    var decoded: String? = null
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            val bmp = BitmapFactory.decodeStream(input)
                            if (bmp != null) decoded = Qr.decode(bmp)
                        }
                    } catch (e: Exception) {
                    }
                    runOnUiThread {
                        if (decoded == null) toast(R.string.gallery_failed) else importText(decoded!!, null)
                    }
                }
            }
            REQ_SCAN -> {
                val text = data?.getStringExtra("text")
                if (!text.isNullOrBlank()) importText(text, null)
            }
            REQ_EXPORT -> {
                val uri = data?.data ?: return
                val p = pendingExport ?: return
                pendingExport = null
                thread {
                    try {
                        contentResolver.openOutputStream(uri)?.use { out -> out.write(p.text.toByteArray(Charsets.UTF_8)) }
                        runOnUiThread { toast(R.string.export_done) }
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }
}
