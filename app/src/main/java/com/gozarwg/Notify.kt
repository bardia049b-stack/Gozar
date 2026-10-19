package com.gozarwg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

object Notify {
    const val ID = 101
    const val ID_BLOCK = 102
    const val ID_UPDATE = 103
    private const val CHANNEL = "tunnel"
    private const val CHANNEL_UPDATE = "updates"

    @Volatile private var lastText = ""
    @Volatile private var lastAt = 0L

    private fun mgr(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun channel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val m = mgr(c)
        if (m.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.enableVibration(false)
        ch.setSound(null, null)
        m.createNotificationChannel(ch)
    }

    private fun updateChannel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = mgr(c)
        if (manager.getNotificationChannel(CHANNEL_UPDATE) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATE,
                c.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun piFlags(): Int {
        return if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    @Suppress("DEPRECATION")
    private fun newBuilder(c: Context): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c)
    }

    private fun builder(c: Context, title: String, text: String): Notification.Builder {
        channel(c)
        val open = PendingIntent.getActivity(
            c,
            1,
            Intent(c, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags()
        )
        val off = PendingIntent.getBroadcast(
            c,
            2,
            Intent(c, ActionReceiver::class.java).setAction(Actions.DISCONNECT),
            piFlags()
        )
        val b = newBuilder(c)
        b.setSmallIcon(R.drawable.ic_power)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW)
        try {
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(c, R.drawable.ic_power),
                    c.getString(R.string.disconnect),
                    off
                ).build()
            )
        } catch (e: Exception) {
        }
        return b
    }

    fun build(c: Context): Notification {
        val name = Vpn.profile?.name ?: c.getString(R.string.app_name)
        return builder(c, name, c.getString(R.string.status_on)).build()
    }

    fun buildLockdown(c: Context): Notification {
        return builder(c, c.getString(R.string.kill_switch), c.getString(R.string.lockdown_on)).build()
    }

    fun cancelLockdown(c: Context) {
        try {
            mgr(c).cancel(ID_BLOCK)
        } catch (e: Exception) {
        }
    }

    fun update(c: Context, name: String, down: Double, up: Double) {
        val text = "↓ " + fmtSpeed(down) + "     ↑ " + fmtSpeed(up)
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < 4000) return
        lastText = text
        lastAt = now
        try {
            mgr(c).notify(ID, builder(c, name, text).build())
        } catch (e: Exception) {
        }
    }

    /** Posts only once per published version; Android notification permission is always respected. */
    fun updateAvailable(c: Context, version: String, url: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            updateChannel(c)
            val open = PendingIntent.getActivity(
                c,
                3,
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                piFlags()
            )
            val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL_UPDATE) else Notification.Builder(c)
            notification
                .setSmallIcon(R.drawable.ic_refresh)
                .setContentTitle(c.getString(R.string.update_notification_title))
                .setContentText(c.getString(R.string.update_notification_text, version))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
            if (Build.VERSION.SDK_INT < 26) notification.setPriority(Notification.PRIORITY_DEFAULT)
            mgr(c).notify(ID_UPDATE, notification.build())
            true
        } catch (_: Exception) {
            false
        }
    }

    fun cancel(c: Context) {
        lastText = ""
        lastAt = 0L
        try {
            mgr(c).cancel(ID)
        } catch (e: Exception) {
        }
    }
}
