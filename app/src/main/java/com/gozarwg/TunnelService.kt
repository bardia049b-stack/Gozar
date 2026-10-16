package com.gozarwg

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import org.amnezia.awg.backend.AbstractBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TunnelService : AbstractBackend.VpnService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        alive = true
        dead = CountDownLatch(1)
        promote()
        ready.countDown()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        if (intent?.action == Actions.DISCONNECT) {
            Vpn.disconnect()
            return START_NOT_STICKY
        }
        if (!Vpn.up && !Vpn.connecting && Store.wantUp(this)) Vpn.restore(applicationContext)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {}

    override fun onDestroy() {
        alive = false
        instance = null
        dead = CountDownLatch(1)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        super.onDestroy()
        dead.countDown()
    }

    private fun promote() {
        try {
            val n = Notify.build(this)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notify.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notify.ID, n)
            }
        } catch (e: Exception) {
        }
    }

    companion object {
        @Volatile private var alive = false
        @Volatile private var ready = CountDownLatch(1)
        @Volatile private var dead = CountDownLatch(1)
        @Volatile private var instance: TunnelService? = null

        fun isAlive(): Boolean = alive

        fun start(c: Context) {
            val i = Intent(c, TunnelService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, TunnelService::class.java))
            } catch (e: Exception) {
            }
        }

        fun ensure(c: Context, timeoutMs: Long = 8000): Boolean {
            if (instance != null) return true
            try {
                start(c.applicationContext)
            } catch (e: Exception) {
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (instance != null) return true
                try {
                    Thread.sleep(60)
                } catch (e: Exception) {
                }
            }
            return instance != null
        }

        fun release(c: Context) {
            if (!alive) return
            stop(c)
            try {
                dead.await(2000, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
            }
        }
    }
}
