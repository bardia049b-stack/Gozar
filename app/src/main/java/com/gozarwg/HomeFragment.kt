package com.gozarwg

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.concurrent.thread

class HomeFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingProfile: Profile? = null
    private var pendingExport: Profile? = null
    @Volatile private var fetchingIp = false
    @Volatile private var fetchingOrigin = false
    private var visualState = -1

    private val vpnPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val profile = pendingProfile
        pendingProfile = null
        if (result.resultCode == Activity.RESULT_OK && profile != null) {
            Vpn.connect(requireContext().applicationContext, profile)
            paintStatus()
        } else if (result.resultCode != Activity.RESULT_OK) {
            toast(R.string.vpn_perm)
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return@registerForActivityResult
        thread {
            val found = mutableListOf<Pair<String, String>>()
            for (uri in uris) {
                val text = readText(uri) ?: continue
                found.add((nameOf(uri) ?: getString(R.string.unnamed)) to text)
            }
            activity?.runOnUiThread {
                when {
                    found.isEmpty() -> toast(R.string.import_failed)
                    found.size == 1 -> importText(found[0].second, found[0].first)
                    else -> importMany(found)
                }
            }
        }
    }

    private val galleryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        thread {
            var decoded: String? = null
            try {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                    if (bitmap != null) decoded = Qr.decode(bitmap)
                }
            } catch (e: Exception) {
            }
            activity?.runOnUiThread {
                val text = decoded
                if (text == null) toast(R.string.gallery_failed) else importText(text, null)
            }
        }
    }

    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringExtra("text")
        if (!text.isNullOrBlank()) importText(text, null)
    }

    private val exporter = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val profile = pendingExport
        pendingExport = null
        if (result.resultCode != Activity.RESULT_OK || profile == null) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        thread {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(profile.text.toByteArray(Charsets.UTF_8))
                }
                activity?.runOnUiThread { toast(R.string.export_done) }
            } catch (e: Exception) {
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (isVisible) paintStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private fun <T : View> find(id: Int): T = view!!.findViewById(id)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.activity_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        find<View>(R.id.connectButton).setOnClickListener { toggle() }
        find<View>(R.id.btnSettingsTop).setOnClickListener { (activity as? MainActivity)?.showSettings() }
        find<View>(R.id.btnMap).setOnClickListener {
            startActivity(Intent(requireContext(), RouteMapActivity::class.java))
        }
        find<View>(R.id.profileCard).setOnClickListener { showProfiles() }
        applyReadableTextColors()
        fetchOrigin()
        paintStatus()
        paintProfiles()
    }

    override fun onStart() {
        super.onStart()
        Vpn.listener = { paintStatus() }
        Vpn.onError = { message ->
            paintStatus()
            toast(getString(R.string.connection_failed) + ": " + message.take(80))
        }
        if (!Vpn.up && !Vpn.connecting) fetchOrigin()
        paintStatus()
        paintProfiles()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        Vpn.listener = null
        Vpn.onError = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(ticker)
    }

    override fun onResume() {
        super.onResume()
        paintProfiles()
    }

    private fun toast(message: String) {
        activity?.runOnUiThread { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun toast(res: Int) {
        activity?.runOnUiThread { Toast.makeText(context, res, Toast.LENGTH_SHORT).show() }
    }

    private fun color(res: Int) = ContextCompat.getColor(requireContext(), res)

    private fun state(): Int {
        if (Vpn.stopping) return 4
        if (Vpn.up) return if (Vpn.handshakeOk) 2 else 1
        if (Vpn.connecting) return 1
        if (Vpn.errorAt > 0 && System.currentTimeMillis() - Vpn.errorAt < 6000) return 3
        return 0
    }

    private fun paintStatus() {
        if (!isAdded || view == null) return
        val state = state()
        val control = find<ConnectControl>(R.id.connectButton)
        val status = when (state) {
            2 -> R.string.status_on
            1 -> if (Vpn.attempt > 1) R.string.reconnecting else R.string.status_connecting
            4 -> R.string.status_disconnecting
            3 -> R.string.status_failed
            else -> R.string.status_off
        }
        val action = if (state == 2) R.string.disconnect else R.string.connect
        control.state = when (state) {
            2 -> ConnectControl.ON
            1, 4 -> ConnectControl.BUSY
            3 -> ConnectControl.ERROR
            else -> ConnectControl.OFF
        }
        control.elapsedSeconds = if (state == 2 && Vpn.since > 0L) {
            ((System.currentTimeMillis() - Vpn.since) / 1000L).toInt().coerceAtLeast(0)
        } else {
            0
        }
        find<ConnectionPulse>(R.id.connectionPulse).state = control.state
        val showSessionDetails = state != 0
        listOf(R.id.tvStatus, R.id.connectionPulse, R.id.tvAction).forEach { id ->
            val target = find<View>(id)
            if (showSessionDetails) reveal(target) else target.visibility = View.GONE
        }
        find<View>(R.id.btnMap).visibility = if (state == 2) View.VISIBLE else View.GONE
        applyReadableTextColors()
        if (visualState != state) {
            visualState = state
            control.animate().cancel()
            control.scaleX = if (state == 1 || state == 4) 0.94f else 0.98f
            control.scaleY = if (state == 1 || state == 4) 0.94f else 0.98f
            control.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        val statusTone = when (state) {
            1, 4 -> R.color.m3_primary
            3 -> R.color.m3_error
            else -> R.color.m3_on_surface
        }
        val actionTone = when (state) {
            1, 4 -> R.color.m3_primary
            3 -> R.color.m3_error
            else -> R.color.m3_on_surface_variant
        }
        control.contentDescription = getString(action)
        find<TextView>(R.id.tvStatus).apply {
            setText(status)
            setTextColor(color(statusTone))
        }
        find<TextView>(R.id.tvAction).apply {
            setText(when (state) {
                2 -> R.string.tap_disconnect
                1 -> R.string.tap_cancel
                4 -> R.string.status_disconnecting
                else -> R.string.tap_connect
            })
            setTextColor(color(actionTone))
        }
        if (state == 2) {
            find<RollingValueView>(R.id.tvDown).setValue(fmtSpeed(Vpn.downSpeed))
            find<RollingValueView>(R.id.tvUp).setValue(fmtSpeed(Vpn.upSpeed))
            find<RollingValueView>(R.id.tvTotal).setValue(fmtBytes(Vpn.totalRx + Vpn.totalTx))
            find<RollingValueView>(R.id.tvPing).setValue(when {
                Vpn.latencyChecking -> "…"
                Vpn.latencyMs > 0 -> "${Vpn.latencyMs} ms"
                else -> "—"
            })
            val age = Vpn.handshakeAgeSec()
            find<RollingValueView>(R.id.tvHandshake).setValue(when {
                age < 0 -> getString(R.string.now)
                age < 60 -> if (age <= 1) getString(R.string.now) else String.format(getString(R.string.sec_ago), age)
                else -> String.format(getString(R.string.min_ago), age / 60)
            })
            reveal(find<View>(R.id.statsRow))
            if (Vpn.ipInfo == null) fetchIp() else paintIp()
        } else {
            find<View>(R.id.statsRow).visibility = View.GONE
            find<View>(R.id.connectionInfo).visibility = View.GONE
        }
    }

    private fun reveal(target: View) {
        if (target.visibility == View.VISIBLE) return
        target.alpha = 0f
        target.translationY = 8f * resources.displayMetrics.density
        target.visibility = View.VISIBLE
        target.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun paintIp() {
        if (!isAdded || view == null) return
        val info = Vpn.ipInfo ?: return
        val iran = info.code.equals("IR", true)
        find<ImageView>(R.id.ivFlag).visibility = if (iran) View.VISIBLE else View.GONE
        find<TextView>(R.id.tvFlag).apply {
            visibility = if (iran) View.GONE else View.VISIBLE
            text = if (iran) "" else Ip.flag(info.code)
        }
        find<TextView>(R.id.tvIp).text = info.ip
        find<TextView>(R.id.tvProvider).text = listOf(info.city, info.provider)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        reveal(find<View>(R.id.connectionInfo))
    }

    /** Samples the direct network only while disconnected, so the full-screen map can show a real
     * origin country as well as the tunnel exit. A locale fallback is used when this is unavailable. */
    private fun fetchOrigin() {
        if (fetchingOrigin || Vpn.originInfo != null || Vpn.up || Vpn.connecting) return
        fetchingOrigin = true
        thread {
            val info = Ip.fetch()
            activity?.runOnUiThread {
                fetchingOrigin = false
                if (info != null && !Vpn.up && !Vpn.connecting) Vpn.originInfo = info
            }
        }
    }

    /** Explicit colours prevent custom animated TextViews from inheriting a stale light palette. */
    private fun applyReadableTextColors() {
        if (!isAdded || view == null) return
        val primary = color(R.color.m3_on_surface)
        val secondary = color(R.color.m3_on_surface_variant)
        val metricNumber = color(R.color.metric_number)
        val metricLabel = color(R.color.metric_label)
        intArrayOf(R.id.tvIp, R.id.tvCurrentName).forEach { id -> find<TextView>(id).setTextColor(primary) }
        intArrayOf(R.id.tvDown, R.id.tvUp, R.id.tvTotal, R.id.tvHandshake).forEach { id ->
            find<TextView>(id).setTextColor(metricNumber)
        }
        intArrayOf(R.id.tvPing, R.id.tvFlag, R.id.tvProvider, R.id.tvCurrentMeta).forEach { id ->
            find<TextView>(id).setTextColor(secondary)
        }
        intArrayOf(R.id.tvDownLabel, R.id.tvUpLabel, R.id.tvTotalLabel, R.id.tvHandshakeLabel).forEach { id ->
            find<TextView>(id).setTextColor(metricLabel)
        }
    }

    private fun fetchIp() {
        if (fetchingIp || Vpn.ipInfo != null) return
        fetchingIp = true
        thread {
            val info = Ip.fetch()
            activity?.runOnUiThread {
                fetchingIp = false
                if (info != null && state() == 2) {
                    Vpn.ipInfo = info
                    Vpn.ipSession = System.currentTimeMillis()
                    paintIp()
                }
            }
        }
    }

    private fun paintProfiles() {
        if (!isAdded || view == null) return
        val profile = selected()
        val name = find<TextView>(R.id.tvCurrentName)
        val meta = find<TextView>(R.id.tvCurrentMeta)
        if (profile == null) {
            name.setText(R.string.empty_title)
            meta.setText(R.string.empty_desc)
        } else {
            name.text = profile.name
            val endpoint = profile.endpoint
            val bits = mutableListOf<String>()
            if (endpoint.isNotBlank()) bits.add(endpoint)
            if (profile.awg) bits.add("AmneziaWG")
            meta.text = bits.joinToString(" · ")
        }
    }

    private fun selected(): Profile? {
        val list = Profiles.all(requireContext())
        if (list.isEmpty()) return null
        val id = Store.selected(requireContext())
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    private fun toggle() {
        if (Vpn.connecting || Vpn.up || Vpn.stopping) {
            Vpn.disconnect()
            paintStatus()
            return
        }
        val profile = selected()
        if (profile == null) {
            toast(R.string.select_first)
            showAdd()
            return
        }
        askNotifications()
        val intent = if (Store.proxy(requireContext())) null else VpnService.prepare(requireContext())
        if (intent != null) {
            pendingProfile = profile
            vpnPerm.launch(intent)
        } else {
            Vpn.connect(requireContext().applicationContext, profile)
            paintStatus()
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
    }

    private fun showProfiles() {
        val profile = selected()
        val list = Profiles.all(requireContext())
        if (list.isEmpty()) {
            showAdd()
            return
        }
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.sheet_profiles, null)
        dialog.setContentView(content)
        val adapter = ProfileAdapter(
            onPick = { picked ->
                Store.setSelected(requireContext(), picked.id)
                Store.setLastProfile(requireContext(), picked.id)
                dialog.dismiss()
                paintProfiles()
                if (Vpn.up || Vpn.connecting) {
                    Vpn.disconnect()
                    handler.postDelayed({
                        Vpn.connect(requireContext().applicationContext, picked)
                    }, 900)
                }
            },
            onMenu = { item, _ -> profileMenu(item) }
        )
        val rv = content.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvProfiles)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rv.adapter = adapter
        adapter.submit(list, profile?.id ?: "")
        content.findViewById<View>(R.id.btnSheetAdd).setOnClickListener {
            dialog.dismiss()
            showAdd()
        }
        dialog.show()
    }

    private fun profileMenu(profile: Profile) {
        Sheets.actions(
            requireContext(),
            R.string.menu_title,
            listOf(
                SheetAction(R.drawable.ic_edit, R.string.menu_edit) { openEditor(profile) },
                SheetAction(R.drawable.ic_qr, R.string.menu_qr) { showQr(profile) },
                SheetAction(R.drawable.ic_share, R.string.menu_share) { share(profile) },
                SheetAction(R.drawable.ic_save, R.string.menu_export) { export(profile) },
                SheetAction(R.drawable.ic_delete, R.string.menu_delete, R.color.m3_error) { confirmDelete(profile) }
            )
        )
    }

    private fun showAdd() {
        Sheets.add(requireContext()) { key ->
            when (key) {
                "file" -> filePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    type = "*/*"
                })
                "qr" -> scanner.launch(Intent(requireContext(), ScanActivity::class.java))
                "gallery" -> galleryPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                })
                "paste" -> paste()
                "link" -> linkDialog()
                "create" -> openEditor(null)
            }
        }
    }

    private fun paste() {
        val manager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = manager.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (text.isNullOrBlank()) {
            toast(R.string.clipboard_empty)
            return
        }
        importText(text, null)
    }

    private fun linkDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_link, null)
        val input = content.findViewById<TextInputEditText>(R.id.etLink)
        val box = content.findViewById<TextInputLayout>(R.id.linkBox)
        MaterialAlertDialogBuilder(requireContext())
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val raw = input.text.toString()
                if (raw.isBlank()) {
                    box.error = getString(R.string.import_failed)
                } else {
                    importText(raw, Link.nameOf(raw))
                }
            }
            .show()
    }

    private fun openEditor(profile: Profile?) {
        val intent = Intent(requireContext(), EditActivity::class.java)
        if (profile != null) intent.putExtra("id", profile.id)
        startActivity(intent)
    }

    private fun share(profile: Profile) {
        toast(R.string.share_warn)
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, profile.text)
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun export(profile: Profile) {
        pendingExport = profile
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        intent.putExtra(Intent.EXTRA_TITLE, profile.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".conf")
        exporter.launch(intent)
    }

    private fun confirmDelete(profile: Profile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.del_title)
            .setMessage(String.format(getString(R.string.del_msg), profile.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                if (Vpn.profile?.id == profile.id) Vpn.disconnect()
                Profiles.delete(requireContext(), profile.id)
                paintProfiles()
            }
            .show()
    }

    private fun showQr(profile: Profile) {
        val content = layoutInflater.inflate(R.layout.dialog_qr, null)
        content.findViewById<TextView>(R.id.tvQrName).text = profile.name
        val image = content.findViewById<ImageView>(R.id.ivQr)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(content)
            .create()
        var bitmap: Bitmap? = null
        thread {
            val made = Qr.make(profile.text)
            bitmap = made
            activity?.runOnUiThread { image.setImageBitmap(made) }
        }
        content.findViewById<View>(R.id.btnQrSave).setOnClickListener {
            val current = bitmap
            if (current != null && saveQr(profile.name, current)) toast(R.string.qr_saved)
        }
        content.findViewById<View>(R.id.btnQrShare).setOnClickListener {
            dialog.dismiss()
            share(profile)
        }
        dialog.show()
    }

    private fun saveQr(name: String, bitmap: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GozarWG")
            val uri = requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            requireContext().contentResolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun importText(raw: String, suggested: String?) {
        val text = Link.parse(raw)
        if (text == null) {
            toast(R.string.import_failed)
            return
        }
        val host = Regex("(?m)^\\s*Endpoint\\s*=\\s*([^:\\s]+)").find(text)?.groupValues?.get(1)
        val guess = suggested?.takeIf { it.isNotBlank() } ?: host ?: getString(R.string.unnamed)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_title)
            .setMessage(String.format(getString(R.string.import_msg), guess))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yes) { _, _ ->
                val profile = Profiles.add(requireContext(), text, suggested)
                if (profile == null) {
                    toast(R.string.import_failed)
                } else {
                    Store.setSelected(requireContext(), profile.id)
                    Store.setLastProfile(requireContext(), profile.id)
                    toast(R.string.imported)
                    paintProfiles()
                }
            }
            .show()
    }

    fun restore(intent: Intent?) {
        handleIntent(intent)
    }

    private fun importMany(items: List<Pair<String, String>>) {
        var added = 0
        var last: Profile? = null
        for (item in items) {
            val text = Link.parse(item.second) ?: continue
            val profile = Profiles.add(requireContext(), text, item.first) ?: continue
            added++
            last = profile
        }
        if (added == 0) {
            toast(R.string.import_failed)
            return
        }
        val newest = last
        if (newest != null) {
            Store.setSelected(requireContext(), newest.id)
            Store.setLastProfile(requireContext(), newest.id)
        }
        toast(String.format(getString(R.string.imported_count), added))
        paintProfiles()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "content" || data.scheme == "file") {
                    thread {
                        val text = readText(data)
                        activity?.runOnUiThread { if (text != null) importText(text, nameOf(data)) }
                    }
                } else {
                    importText(data.toString(), Link.nameOf(data.toString()))
                }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) importText(text, null)
            }
        }
    }

    private fun readText(uri: Uri): String? {
        return try {
            requireContext().contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun nameOf(uri: Uri): String? {
        var name: String? = null
        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
            }
        } catch (e: Exception) {
        }
        if (name == null) name = uri.lastPathSegment
        return name?.substringBeforeLast(".")?.replace("_", " ")?.replace("-", " ")
    }

}
