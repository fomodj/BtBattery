package com.example.btbattery

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat

class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "bt_battery_channel"
        const val NOTIFY_ID = 1001
        const val ACTION_BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_BATTERY_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    refreshAudioDeviceBattery()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(ACTION_BATTERY_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refreshAudioDeviceBattery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshAudioDeviceBattery()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun refreshAudioDeviceBattery() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: return

        val targetDevice = adapter.bondedDevices?.firstOrNull { device ->
            val isAudio = device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
            isAudio && isDeviceConnected(device)
        }

        if (targetDevice != null) {
            val level = getBatteryLevel(targetDevice)
            if (level in 0..100) {
                updateNotification(targetDevice.name ?: "蓝牙耳机", level)
                return
            }
        }
        showIdleNotification()
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = device.javaClass.getMethod("isConnected")
        method.invoke(device) as Boolean
    } catch (e: Exception) {
        false
    }

    private fun getBatteryLevel(device: BluetoothDevice): Int = try {
        val method = device.javaClass.getMethod("getBatteryLevel")
        method.invoke(device) as Int
    } catch (e: Exception) {
        -1
    }

    private fun createBatteryIcon(level: Int): IconCompat {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (level == 100) 54f else 72f
        }
        val text = level.toString()
        val fontMetrics = paint.fontMetrics
        val baseline = size / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(text, size / 2f, baseline, paint)
        return IconCompat.createWithBitmap(bitmap)
    }

    private fun updateNotification(deviceName: String, level: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(createBatteryIcon(level))
            .setContentTitle("$deviceName 电量：$level%")
            .setContentText("蓝牙耳机已连接")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startCompatForeground(notification)
    }

    private fun showIdleNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("等待耳机连接")
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startCompatForeground(notification)
    }

    private fun startCompatForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "耳机电量显示", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        unregisterReceiver(btReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
