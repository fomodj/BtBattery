package com.example.btbattery

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "bt_battery_channel_v2"
        const val NOTIFY_ID = 1001
        const val ACTION_BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        const val ACTION_REFRESH = "com.example.btbattery.ACTION_REFRESH"
        const val PREFS_NAME = "bt_battery_prefs"
        const val KEY_ICON_STYLE = "icon_style_v3"

        const val STYLE_BADGE = 0
        const val STYLE_RING = 1
        const val STYLE_BATTERY = 2

        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cachedLevels = ConcurrentHashMap<String, Int>()
    private var headsetProxy: BluetoothProfile? = null
    private var a2dpProxy: BluetoothProfile? = null
    private var activeGatt: BluetoothGatt? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProxy = proxy
                BluetoothProfile.A2DP -> a2dpProxy = proxy
            }
            refreshAudioDeviceBattery()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProxy = null
                BluetoothProfile.A2DP -> a2dpProxy = null
            }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_BATTERY_CHANGED) {
                val device = getDeviceExtra(intent)
                val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                if (device != null && level in 0..100) {
                    cachedLevels[device.address] = level
                }
            }
            refreshAudioDeviceBattery()
            mainHandler.postDelayed({ refreshAudioDeviceBattery() }, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val manager = getSystemService(BluetoothManager::class.java)
        manager?.adapter?.let { adapter ->
            adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
            adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_BATTERY_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        showIdleNotification()
        refreshAudioDeviceBattery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshAudioDeviceBattery()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun refreshAudioDeviceBattery() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            updateUiAndIdle("蓝牙未开启", "请先打开手机蓝牙", "蓝牙适配器未启用")
            return
        }

        val connectedAudio = LinkedHashMap<String, BluetoothDevice>()
        try {
            headsetProxy?.connectedDevices?.forEach { connectedAudio[it.address] = it }
            a2dpProxy?.connectedDevices?.forEach { connectedAudio[it.address] = it }
        } catch (_: SecurityException) {
            updateUiAndIdle("缺少蓝牙权限", "请授予「附近设备」权限", "无 BLUETOOTH_CONNECT 权限")
            return
        }

        val bonded = try { adapter.bondedDevices ?: emptySet() } catch (_: SecurityException) { emptySet() }
        if (connectedAudio.isEmpty()) {
            bonded.forEach { device ->
                val isAudio = device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
                if (isAudio && isDeviceConnectedReflection(device)) {
                    connectedAudio[device.address] = device
                }
            }
        }

        val logBuilder = StringBuilder()
        var bestDevice: BluetoothDevice? = null
        var bestLevel = -1

        if (connectedAudio.isEmpty()) {
            logBuilder.append("当前无已连接的音频设备 (HFP/A2DP)\n")
        } else {
            connectedAudio.values.forEach { dev ->
                val reflectLevel = getBatteryLevelReflection(dev)
                val broadcastLevel = cachedLevels[dev.address] ?: -1
                val finalLevel = if (reflectLevel in 0..100) reflectLevel else broadcastLevel
                val name = dev.name ?: dev.address
                logBuilder.append("• [已连接] $name：")
                logBuilder.append(if (finalLevel in 0..100) "$finalLevel%\n" else "读取中/未知\n")

                if (bestDevice == null || (bestLevel !in 0..100 && finalLevel in 0..100)) {
                    bestDevice = dev
                    bestLevel = finalLevel
                }
            }
        }

        if (bestDevice != null) {
            val target = bestDevice!!
            val name = target.name ?: "蓝牙耳机"
            if (bestLevel in 0..100) {
                updateConnectedNotification(name, bestLevel)
                BatteryStateHolder.update(
                    BatteryUiState(
                        serviceRunning = true,
                        deviceName = name,
                        deviceSubtitle = "音频已连接 · 实时同步中",
                        batteryLevel = bestLevel,
                        diagnosticLog = logBuilder.toString().trim()
                    )
                )
            } else {
                tryReadGattBattery(target)
                showIdleNotification("$name 已连接（读取电量中）")
                BatteryStateHolder.update(
                    BatteryUiState(
                        serviceRunning = true,
                        deviceName = name,
                        deviceSubtitle = "已连接，等待耳机上报电量",
                        batteryLevel = -1,
                        diagnosticLog = (logBuilder.toString() + "正在尝试 BLE 电量服务读取...").trim()
                    )
                )
            }
        } else {
            logBuilder.append("已配对蓝牙设备总数：${bonded.size}")
            updateUiAndIdle("未检测到已连接耳机", "连上耳机后自动显示电量", logBuilder.toString().trim())
        }
    }

    private fun updateUiAndIdle(title: String, sub: String, log: String) {
        showIdleNotification()
        BatteryStateHolder.update(
            BatteryUiState(
                serviceRunning = true,
                deviceName = title,
                deviceSubtitle = sub,
                batteryLevel = -1,
                diagnosticLog = log
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun tryReadGattBattery(device: BluetoothDevice) {
        try {
            activeGatt?.close()
            activeGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val charac = gatt.getService(BATTERY_SERVICE_UUID)
                        ?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
                    if (charac != null) {
                        gatt.readCharacteristic(charac)
                    } else {
                        gatt.close()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                        val level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                        if (level != null && level in 0..100) {
                            cachedLevels[device.address] = level
                            mainHandler.post { refreshAudioDeviceBattery() }
                        }
                    }
                    gatt.close()
                }
            })
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun getDeviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun isDeviceConnectedReflection(device: BluetoothDevice): Boolean = try {
        val method = device.javaClass.getMethod("isConnected")
        method.invoke(device) as Boolean
    } catch (_: Exception) {
        false
    }

    private fun getBatteryLevelReflection(device: BluetoothDevice): Int = try {
        val method = device.javaClass.getMethod("getBatteryLevel")
        method.invoke(device) as Int
    } catch (_: Exception) {
        -1
    }

    private fun getIconStyle(): Int {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_ICON_STYLE, STYLE_BADGE)
    }

    private fun getLevelColor(level: Int): Int = when {
        level <= 20 -> Color.parseColor("#EF4444") // 珊瑚红
        level <= 45 -> Color.parseColor("#F59E0B") // 琥珀黄
        else -> Color.parseColor("#10B981")        // 翠绿
    }

    private fun createBatteryIcon(level: Int): IconCompat {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val iconStyle = getIconStyle()
        val accent = getLevelColor(level)
        val text = level.toString()

        when (iconStyle) {
            STYLE_RING -> {
                // 环形仪表：浅底轨 + 彩色进度弧 + 居中数字
                val pad = 10f
                val oval = RectF(pad, pad, size - pad, size - pad)

                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(65, Color.red(accent), Color.green(accent), Color.blue(accent))
                    style = Paint.Style.STROKE
                    strokeWidth = 9f
                }
                canvas.drawOval(oval, trackPaint)

                val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    style = Paint.Style.STROKE
                    strokeWidth = 9f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawArc(oval, -90f, 360f * (level / 100f), false, arcPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 30f else 40f
                }
                val fm = textPaint.fontMetrics
                val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
                canvas.drawText(text, size / 2f, baseline, textPaint)
            }

            STYLE_BATTERY -> {
                // 彩色描边电池框 + 内部数字
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    style = Paint.Style.STROKE
                    strokeWidth = 7f
                }
                val bodyRect = RectF(6f, 18f, 82f, 78f)
                canvas.drawRoundRect(bodyRect, 14f, 14f, strokePaint)

                val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(RectF(85f, 36f, 92f, 60f), 4f, 4f, tipPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 30f else 42f
                }
                val fm = textPaint.fontMetrics
                val baseline = bodyRect.centerY() - (fm.ascent + fm.descent) / 2f
                canvas.drawText(text, bodyRect.centerX(), baseline, textPaint)
            }

            else -> {
                // 默认：澎湃 OS 风格彩色圆角徽章 + 镂空粗体数字
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    style = Paint.Style.FILL
                }
                val badgeRect = RectF(6f, 6f, 90f, 90f)
                canvas.drawRoundRect(badgeRect, 22f, 22f, bgPaint)

                val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 38f else 50f
                }
                val fm = cutoutPaint.fontMetrics
                val baseline = badgeRect.centerY() - (fm.ascent + fm.descent) / 2f
                canvas.drawText(text, badgeRect.centerX(), baseline, cutoutPaint)
            }
        }
        return IconCompat.createWithBitmap(bitmap)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateConnectedNotification(deviceName: String, level: Int) {
        val levelColor = getLevelColor(level)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(createBatteryIcon(level))
            .setColor(levelColor)
            .setContentTitle("耳机电量 $level%")
            .setContentText(deviceName)
            .setProgress(100, level, false)
            .setShowWhen(false)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startCompatForeground(notification)
    }

    private fun showIdleNotification(title: String = "等待耳机连接") {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_headset)
            .setColor(Color.parseColor("#3B82F6"))
            .setContentTitle(title)
            .setShowWhen(false)
            .setContentIntent(contentIntent())
            .setOngoing(true)
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
        val channel = NotificationChannel(CHANNEL_ID, "耳机电量常驻显示", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            description = "实时显示已连接蓝牙耳机的电量百分比"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        activeGatt?.close()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        BatteryStateHolder.update(
            BatteryStateHolder.currentState.copy(serviceRunning = false)
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
