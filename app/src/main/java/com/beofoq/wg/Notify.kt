package com.beofoq.wg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Notify {
    private const val CHANNEL = "status"
    private const val ID = 1

    private fun channel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            mgr.createNotificationChannel(ch)
        }
    }

    fun update(c: Context, name: String, down: Double, up: Double) {
        try {
            channel(c)
            val intent = Intent(c, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            val flags = if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getActivity(c, 0, intent, flags)
            val text = "↓ " + fmtSpeed(down) + "   ↑ " + fmtSpeed(up)
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(c, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(c)
            }
            builder.setSmallIcon(R.drawable.ic_power)
                .setContentTitle(name)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pending)
            val mgr = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(ID, builder.build())
        } catch (e: Exception) {
        }
    }

    fun cancel(c: Context) {
        try {
            val mgr = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(ID)
        } catch (e: Exception) {
        }
    }
}
