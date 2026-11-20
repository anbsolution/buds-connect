package com.budscontrol.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class LowBatteryNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "buds_low_battery"
    private val lastAlert = mutableMapOf<String, Int>()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Earbud battery alerts", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    @Synchronized
    fun show(part: String, level: Int) {
        if (level > 20) {
            lastAlert.remove(part)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val previous = lastAlert[part]
        if (previous != null && previous <= 20) return
        lastAlert[part] = level
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(appContext, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(appContext)
        }
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Buds Control · Low battery")
            .setContentText("$part battery is $level%. Please charge it.")
            .setAutoCancel(true)
            .build()
        manager.notify(part.hashCode(), notification)
    }
}
