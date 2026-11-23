package com.example.btbattery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val allowed = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == BatteryMonitorService.ACTION_KEEP_ALIVE

        if (!allowed) return

        val prefs = context.getSharedPreferences(BatteryMonitorService.PREFS_NAME, Context.MODE_PRIVATE)
        val keepAlive = prefs.getBoolean(BatteryMonitorService.KEY_KEEP_ALIVE, true)
        val userStopped = prefs.getBoolean(BatteryMonitorService.KEY_USER_STOPPED, false)
        if (!keepAlive || userStopped) return

        val serviceIntent = Intent(context, BatteryMonitorService::class.java).apply {
            this.action = BatteryMonitorService.ACTION_REFRESH
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {
            // 若尚未授予悬浮窗权限导致 Android 14+ 后台拉起受限，静默跳过避免崩溃
        }
    }
}
