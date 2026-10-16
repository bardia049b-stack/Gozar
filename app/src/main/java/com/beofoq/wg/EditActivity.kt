package com.beofoq.wg

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import kotlin.concurrent.thread

class EditActivity : BaseActivity() {
    private var profileId: String? = null
    private var rawMode = false

    private lateinit var etName: EditText
    private lateinit var etRaw: EditText
    private lateinit var formBox: View
    private lateinit var segForm: TextView
    private lateinit var segRaw: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        profileId = intent.getStringExtra("id")

        etName = findViewById(R.id.etName)
        etRaw = findViewById(R.id.etRaw)
        formBox = findViewById(R.id.formBox)
        segForm = findViewById(R.id.segForm)
        segRaw = findViewById(R.id.segRaw)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<View>(R.id.btnSaveTop).setOnClickListener { save() }
        segForm.setOnClickListener { setMode(false) }
        segRaw.setOnClickListener { setMode(true) }

        findViewById<View>(R.id.btnGenPriv).setOnClickListener {
            val pair = Qr.newKeyPair()
            field(R.id.etPriv).setText(pair.first)
            findViewById<TextView>(R.id.tvPub).text = pair.second
        }
        findViewById<View>(R.id.btnGenPsk).setOnClickListener {
            field(R.id.etPsk).setText(Qr.randomKey())
        }
        findViewById<View>(R.id.btnRandomAwg).setOnClickListener { randomAwg() }

        field(R.id.etPriv).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pub = Qr.publicOf(s?.toString() ?: "")
                findViewById<TextView>(R.id.tvPub).text = pub ?: ""
            }
        })

        val id = profileId
        if (id != null) {
            findViewById<MaterialToolbar>(R.id.toolbar).setTitle(R.string.ed_edit)
            findViewById<View>(R.id.actionRow).visibility = View.VISIBLE
            val p = Profiles.get(this, id)
            if (p != null) {
                etName.setText(p.name)
                etRaw.setText(p.text)
                fillForm(p.text)
            }
            findViewById<View>(R.id.btnQr).setOnClickListener { currentProfile()?.let { showQr(it) } }
            findViewById<View>(R.id.btnShare).setOnClickListener { currentProfile()?.let { shareProfile(it) } }
            findViewById<View>(R.id.btnExport).setOnClickListener { currentProfile()?.let { exportProfile(it) } }
            findViewById<View>(R.id.btnDelete).setOnClickListener { currentProfile()?.let { confirmDelete(it) } }
        } else {
            field(R.id.etDns).setText("1.1.1.1")
            field(R.id.etMtu).setText("1280")
            field(R.id.etAllowed).setText("0.0.0.0/0, ::/0")
            field(R.id.etKeepalive).setText("25")
            val pair = Qr.newKeyPair()
            field(R.id.etPriv).setText(pair.first)
            findViewById<TextView>(R.id.tvPub).text = pair.second
        }
    }

    private fun field(id: Int): EditText = findViewById(id)

    private fun value(id: Int): String = field(id).text.toString().trim()

    private fun currentProfile(): Profile? {
        val id = profileId ?: return null
        return Profiles.get(this, id)
    }

    private fun setMode(raw: Boolean) {
        if (raw == rawMode) return
        if (raw) {
            etRaw.setText(buildText())
        } else {
            fillForm(etRaw.text.toString())
        }
        rawMode = raw
        applyMode()
    }

    private fun applyMode() {
        val raw = rawMode
        formBox.visibility = if (raw) View.GONE else View.VISIBLE
        findViewById<View>(R.id.rawBox).visibility = if (raw) View.VISIBLE else View.GONE
        segForm.setBackgroundResource(if (raw) 0 else R.drawable.seg_on)
        segRaw.setBackgroundResource(if (raw) R.drawable.seg_on else 0)
        segForm.setTextColor(getColor(if (raw) R.color.m3_on_surface_variant else R.color.m3_on_primary_container))
        segRaw.setTextColor(getColor(if (raw) R.color.m3_on_primary_container else R.color.m3_on_surface_variant))
    }

    private fun fillForm(text: String) {
        val sections = parseSections(text)
        val iface = sections.first
        val peer = sections.second
        setField(R.id.etPriv, iface["PrivateKey"])
        setField(R.id.etAddr, iface["Address"])
        setField(R.id.etDns, iface["DNS"])
        setField(R.id.etMtu, iface["MTU"])
        setField(R.id.etPort, iface["ListenPort"])
        setField(R.id.etJc, iface["Jc"])
        setField(R.id.etJmin, iface["Jmin"])
        setField(R.id.etJmax, iface["Jmax"])
        setField(R.id.etS1, iface["S1"])
        setField(R.id.etS2, iface["S2"])
        setField(R.id.etS3, iface["S3"])
        setField(R.id.etS4, iface["S4"])
        setField(R.id.etH1, iface["H1"])
        setField(R.id.etH2, iface["H2"])
        setField(R.id.etH3, iface["H3"])
        setField(R.id.etH4, iface["H4"])
        setField(R.id.etPeerPub, peer["PublicKey"])
        setField(R.id.etPsk, peer["PresharedKey"])
        setField(R.id.etEndpoint, peer["Endpoint"])
        setField(R.id.etAllowed, peer["AllowedIPs"])
        setField(R.id.etKeepalive, peer["PersistentKeepalive"])
        val priv = iface["PrivateKey"]
        if (priv != null) findViewById<TextView>(R.id.tvPub).text = Qr.publicOf(priv) ?: ""
    }

    private fun setField(id: Int, v: String?) {
        field(id).setText(v ?: "")
    }

    private fun parseSections(text: String): Pair<Map<String, String>, Map<String, String>> {
        val iface = mutableMapOf<String, String>()
        val peer = mutableMapOf<String, String>()
        var current: MutableMap<String, String>? = null
        var peerSeen = false
        for (line in text.lines()) {
            val t = line.trim()
            when {
                t.equals("[Interface]", true) -> current = iface
                t.equals("[Peer]", true) -> {
                    if (!peerSeen) {
                        current = peer
                        peerSeen = true
                    } else {
                        current = null
                    }
                }
                t.startsWith("[") -> current = null
                t.contains("=") && current != null -> {
                    val key = t.substringBefore("=").trim()
                    val v = t.substringAfter("=").trim()
                    current[key.lowercase().replaceFirstChar { it.uppercase() }] = v
                }
            }
        }
        fun norm(m: Map<String, String>): Map<String, String> {
            val out = mutableMapOf<String, String>()
            for ((k, v) in m) {
                val key = when (k.lowercase()) {
                    "privatekey" -> "PrivateKey"
                    "address" -> "Address"
                    "dns" -> "DNS"
                    "mtu" -> "MTU"
                    "listenport" -> "ListenPort"
                    "publickey" -> "PublicKey"
                    "presharedkey" -> "PresharedKey"
                    "endpoint" -> "Endpoint"
                    "allowedips" -> "AllowedIPs"
                    "persistentkeepalive" -> "PersistentKeepalive"
                    else -> k
                }
                out[key] = v
            }
            return out
        }
        return norm(iface) to norm(peer)
    }

    private fun buildText(): String {
        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        addLine(sb, "PrivateKey", value(R.id.etPriv))
        addLine(sb, "Address", value(R.id.etAddr))
        addLine(sb, "DNS", value(R.id.etDns))
        addLine(sb, "MTU", value(R.id.etMtu))
        addLine(sb, "ListenPort", value(R.id.etPort))
        addLine(sb, "Jc", value(R.id.etJc))
        addLine(sb, "Jmin", value(R.id.etJmin))
        addLine(sb, "Jmax", value(R.id.etJmax))
        addLine(sb, "S1", value(R.id.etS1))
        addLine(sb, "S2", value(R.id.etS2))
        addLine(sb, "S3", value(R.id.etS3))
        addLine(sb, "S4", value(R.id.etS4))
        addLine(sb, "H1", value(R.id.etH1))
        addLine(sb, "H2", value(R.id.etH2))
        addLine(sb, "H3", value(R.id.etH3))
        addLine(sb, "H4", value(R.id.etH4))
        addLine(sb, "I1", value(R.id.etI1))
        addLine(sb, "I2", value(R.id.etI2))
        addLine(sb, "I3", value(R.id.etI3))
        addLine(sb, "I4", value(R.id.etI4))
        addLine(sb, "I5", value(R.id.etI5))
        sb.appendLine()
        sb.appendLine("[Peer]")
        addLine(sb, "PublicKey", value(R.id.etPeerPub))
        addLine(sb, "PresharedKey", value(R.id.etPsk))
        addLine(sb, "Endpoint", value(R.id.etEndpoint))
        addLine(sb, "AllowedIPs", value(R.id.etAllowed))
        addLine(sb, "PersistentKeepalive", value(R.id.etKeepalive))
        return sb.toString().trim()
    }

    private fun addLine(sb: StringBuilder, key: String, v: String) {
        if (v.isNotBlank()) sb.appendLine("$key = $v")
    }

    private fun randomAwg() {
        field(R.id.etJc).setText("4")
        field(R.id.etJmin).setText("40")
        field(R.id.etJmax).setText("70")
        field(R.id.etS1).setText((15..120).random().toString())
        field(R.id.etS2).setText((10..90).random().toString())
        field(R.id.etS3).setText((8..60).random().toString())
        field(R.id.etS4).setText((5..30).random().toString())
        val used = mutableSetOf<Long>()
        val ids = listOf(R.id.etH1, R.id.etH2, R.id.etH3, R.id.etH4)
        for (id in ids) {
            var v = (100000L..2000000000L).random()
            while (used.contains(v)) v = (100000L..2000000000L).random()
            used.add(v)
            field(id).setText(v.toString())
        }
    }

    private fun save() {
        val text = if (rawMode) etRaw.text.toString() else buildText()
        val error = Profiles.check(text)
        if (error != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ed_edit)
                .setMessage(String.format(getString(R.string.invalid_config), error))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        var name = etName.text.toString().trim()
        if (name.isBlank()) {
            val host = Regex("(?m)^\\s*Endpoint\\s*=\\s*([^:\\s]+)").find(text)?.groupValues?.get(1)
            name = host ?: getString(R.string.unnamed)
        }
        val id = profileId
        if (id == null) {
            val p = Profiles.add(this, text, name)
            if (p == null) {
                toast(R.string.import_failed)
                return
            }
            Store.setSelected(this, p.id)
            toast(R.string.saved)
            finish()
        } else {
            if (!Profiles.save(this, id, name, text)) {
                toast(R.string.import_failed)
                return
            }
            toast(R.string.saved)
            val active = Vpn.profile?.id == id && Vpn.up
            if (active) {
                val fresh = Profiles.get(this, id)
                Vpn.disconnect()
                if (fresh != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Vpn.connect(applicationContext, fresh)
                    }, 700)
                }
            }
            finish()
        }
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
        startActivityForResult(intent, 21)
    }

    private var pendingExport: Profile? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK) return
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

    private fun confirmDelete(p: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.del_title)
            .setMessage(String.format(getString(R.string.del_msg), p.name))
            .setPositiveButton(R.string.yes) { _, _ ->
                if (Vpn.profile?.id == p.id) Vpn.disconnect()
                Profiles.delete(this, p.id)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showQr(p: Profile) {
        val content = layoutInflater.inflate(R.layout.dialog_qr, null)
        content.findViewById<TextView>(R.id.tvQrName).text = p.name
        val image = content.findViewById<ImageView>(R.id.ivQr)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(content)
            .create()
        var bmp: Bitmap? = null
        thread {
            val made = Qr.make(p.text)
            bmp = made
            runOnUiThread { image.setImageBitmap(made) }
        }
        content.findViewById<View>(R.id.btnQrSave).setOnClickListener {
            val b = bmp
            if (b != null && saveToGallery(p.name, b)) toast(R.string.qr_saved)
        }
        content.findViewById<View>(R.id.btnQrShare).setOnClickListener {
            dialog.dismiss()
            shareProfile(p)
        }
        dialog.show()
    }

    private fun saveToGallery(name: String, bmp: Bitmap): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return try {
            val values = android.content.ContentValues()
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GozarWG")
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
