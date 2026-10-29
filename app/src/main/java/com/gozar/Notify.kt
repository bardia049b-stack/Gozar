package com.gozar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat

object Notify {

    private const val CHANNEL_UPDATE = "updates"
    private const val ID_UPDATE = 4097

    private fun mgr(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun updateChannel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        mgr(c).createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, c.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

    fun updateAvailable(c: Context, version: String, url: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            updateChannel(c)
            val open = PendingIntent.getActivity(
                c,
                3,
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags()
            )
            val note = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL_UPDATE) else Notification.Builder(c)
            note.setSmallIcon(R.drawable.ic_refresh)
                .setContentTitle(c.getString(R.string.update_notification_title))
                .setContentText(c.getString(R.string.update_notification_text, version))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
            if (Build.VERSION.SDK_INT < 26) note.setPriority(Notification.PRIORITY_DEFAULT)
            mgr(c).notify(ID_UPDATE, note.build())
            true
        } catch (e: Exception) {
            false
        }
    }
}
