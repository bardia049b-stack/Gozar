package com.beofoq.wg

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BlockService : VpnService() {
    private var fd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        latch = CountDownLatch(1)
        promote()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        if (fd == null && !open()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onRevoke() {
        close()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        running = false
        close()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        latch.countDown()
        super.onDestroy()
    }

    private fun open(): Boolean {
        return try {
            val b = Builder()
            b.setSession(getString(R.string.kill_switch))
            b.addAddress("10.255.255.2", 32)
            b.addAddress("fd00:255::2", 128)
            b.addRoute("0.0.0.0", 0)
            b.addRoute("::", 0)
            b.setMtu(1280)
            try {
                b.addDisallowedApplication(packageName)
            } catch (e: Exception) {
            }
            if (Build.VERSION.SDK_INT >= 29) b.setBlocking(true)
            fd = b.establish()
            fd != null
        } catch (e: Exception) {
            false
        }
    }

    private fun promote() {
        try {
            val n = Notify.buildLockdown(this)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notify.ID_BLOCK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notify.ID_BLOCK, n)
            }
        } catch (e: Exception) {
        }
    }

    private fun close() {
        running = false
        try {
            fd?.close()
        } catch (e: Exception) {
        }
        fd = null
    }

    companion object {
        @Volatile var running = false
            private set
        @Volatile private var latch = CountDownLatch(1)

        fun start(c: Context) {
            try {
                val i = Intent(c, BlockService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {
            }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, BlockService::class.java))
            } catch (e: Exception) {
            }
        }

        fun stopAndWait(c: Context, timeoutMs: Long): Boolean {
            if (!running) return true
            stop(c)
            return try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                false
            }
        }
    }
}
