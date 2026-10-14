package com.beofoq.wg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor

class BlockService : VpnService() {
    private var fd: ParcelFileDescriptor? = null

    companion object {
        @Volatile var running = false

        fun start(c: Context) {
            try {
                c.startService(Intent(c, BlockService::class.java))
            } catch (e: Exception) {
            }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, BlockService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (fd == null) {
            try {
                val builder = Builder()
                builder.setSession(getString(R.string.app_name))
                builder.addAddress("10.255.255.2", 32)
                builder.addAddress("fd00:255::2", 128)
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                builder.addDnsServer("1.1.1.1")
                builder.setMtu(1280)
                if (Build.VERSION.SDK_INT >= 29) builder.setBlocking(true)
                fd = builder.establish()
                if (fd == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                running = true
            } catch (e: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        closeFd()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeFd()
        super.onDestroy()
    }

    private fun closeFd() {
        running = false
        try {
            fd?.close()
        } catch (e: Exception) {
        }
        fd = null
    }
}
