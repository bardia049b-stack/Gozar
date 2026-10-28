package com.gozarwg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat

class FoxVpnService : VpnService() {

    private var tunnel: android.os.ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        foreground()
        if (Vpn.up || Vpn.connecting) Vpn.disconnect()
        FoxLink.start(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val builder = Builder()
                    .setSession("GozarWG Firefox")
                    .addAddress("10.111.0.2", 32)
                    .setMtu(1400)
                    .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", FoxLink.WEB_PORT))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
                builder.addDisallowedApplication(packageName)
                tunnel = builder.establish()
            } catch (e: Exception) {
                tunnel = null
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        try {
            tunnel?.close()
        } catch (_: Exception) {
        }
        tunnel = null
        FoxLink.stop()
        super.onDestroy()
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
        private const val CHANNEL = "fox_vpn"
        private const val NOTIFY = 7412
        private const val STOP = "com.gozarwg.FOX_STOP"

        @Volatile private var alive = false

        fun isAlive(): Boolean = alive

        fun start(c: Context) {
            val intent = Intent(c, FoxVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(intent) else c.startService(intent)
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, FoxVpnService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
