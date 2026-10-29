package com.gozar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class FoxVpnService : VpnService() {

    private var descriptor: ParcelFileDescriptor? = null

    private var stack: FoxStack? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            close()
            return START_NOT_STICKY
        }
        foreground()
        open()
        return START_STICKY
    }

    private fun open() {
        if (descriptor != null) return
        val builder = Builder()
            .setSession(getString(R.string.fox_title))
            .addAddress(LOCAL_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addRoute("0.0.0.0", 0)
            .setMtu(FoxStack.MTU)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", FoxLink.WEB_PORT))
        }
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
        }
        val only = Store.apps(this)
        if (only.isNotEmpty()) {
            for (target in only) {
                try {
                    builder.addAllowedApplication(target)
                } catch (e: Exception) {
                }
            }
        }
        if (Store.bypassLan(this) && Build.VERSION.SDK_INT >= 33) {
            for (range in LAN_RANGES) {
                try {
                    builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(range.first), range.second))
                } catch (e: Exception) {
                }
            }
        }
        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            FoxLog.add("vpn", "cannot open the tunnel device: " + (e.message ?: "unknown"))
            null
        }
        if (fd == null) {
            FoxLink.fault(getString(R.string.fox_no_device))
            return
        }
        descriptor = fd
        val app = applicationContext
        val engine = FoxStack(
            FileInputStream(fd.fileDescriptor),
            FileOutputStream(fd.fileDescriptor),
            addressOf(LOCAL_ADDRESS),
            FoxResolver(FoxLink.WEB_PORT),
            { host, port ->
                val location = FoxLink.location ?: throw IOException("not connected yet")
                val token = FoxStore.pass(app)
                if (token.isEmpty()) throw IOException("no pass")
                FoxTunnel.open(location, host, port, token)
            },
            { inbound, size -> FoxLink.count(inbound, size) }
        )
        engine.start()
        stack = engine
        FoxLog.add("vpn", "device open, routes and dns are live")
        FoxLink.start(this)
    }

    override fun onRevoke() {
        FoxLog.add("vpn", "another vpn took over")
        close()
        super.onRevoke()
    }

    override fun onDestroy() {
        alive = false
        if (instance === this) instance = null
        release()
        super.onDestroy()
    }

    private fun close() {
        FoxLog.add("vpn", "closing the device")
        release()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    private fun release() {
        try {
            stack?.stop()
        } catch (e: Exception) {
        }
        stack = null
        try {
            descriptor?.close()
        } catch (e: Exception) {
        }
        descriptor = null
        FoxLink.stop()
    }

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.fox_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, FoxVpnService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fox_note)
            .setContentTitle(getString(R.string.fox_title))
            .setContentText(getString(R.string.fox_notify))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.disconnect), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY, note)
        }
    }

    companion object {
        const val LOCAL_ADDRESS = "10.111.0.2"
        val LAN_RANGES = arrayOf(
            "10.0.0.0" to 8,
            "172.16.0.0" to 12,
            "192.168.0.0" to 16,
            "169.254.0.0" to 16
        )
        const val DNS_ADDRESS = "10.111.0.53"
        private const val CHANNEL = "fox_vpn"
        private const val NOTIFY = 7412
        private const val STOP = "com.gozar.FOX_STOP"

        @Volatile private var alive = false
        @Volatile private var instance: FoxVpnService? = null

        fun isAlive(): Boolean = alive

        fun addressOf(text: String): Int {
            val parts = text.split(".")
            if (parts.size != 4) return 0
            var value = 0
            for (part in parts) value = (value shl 8) or (part.toIntOrNull()?.and(0xFF) ?: 0)
            return value
        }

        fun start(c: Context) {
            val intent = Intent(c, FoxVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(intent) else c.startService(intent)
        }

        fun stop(c: Context) {
            val running = instance
            try {
                running?.close()
            } catch (e: Exception) {
            }
            try {
                c.stopService(Intent(c, FoxVpnService::class.java))
            } catch (e: Exception) {
            }
            FoxLink.stop()
        }
    }
}
