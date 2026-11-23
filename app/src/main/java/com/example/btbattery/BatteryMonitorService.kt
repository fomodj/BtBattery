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
        const val KEY_ICON_STYLE = "icon_style"

        const val STYLE_PLAIN = 0
        const val STYLE_BATTERY = 1
        const val STYLE_PILL = 2

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
            // 蓝牙连接瞬间系统电量可能延迟几百毫秒上报，顺带延迟补查一次
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

        // 兜底：若 ProfileProxy 还没回调或设备走特殊协议，扫描已配对列表
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
                        deviceSubtitle = "音频已连接 · 状态栏同步中",
                        batteryLevel = bestLevel,
                        diagnosticLog = logBuilder.toString().trim()
                    )
                )
            } else {
                // 尝试通过 BLE 标准电量服务兜底读取
                tryReadGattBattery(target)
                showIdleNotification("$name 已连接（正在获取电量）")
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
            val bondedCount = bonded.size
            logBuilder.append("已配对蓝牙设备总数：$bondedCount")
            updateUiAndIdle("未检测到已连接耳机", "连上耳机后状态栏会自动显示电量", logBuilder.toString().trim())
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
            .getInt(KEY_ICON_STYLE, STYLE_BATTERY)
    }

    private fun createBatteryIcon(level: Int): IconCompat {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val style = getIconStyle()
        val text = level.toString()

        when (style) {
            STYLE_BATTERY -> {
                // 空心电池框 + 内部数字
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }
                val bodyRect = RectF(6f, 18f, 82f, 78f)
                canvas.drawRoundRect(bodyRect, 12f, 12f, strokePaint)

                val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(RectF(84f, 36f, 92f, 60f), 4f, 4f, tipPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 32f else 44f
                }
                val fm = textPaint.fontMetrics
                val baseline = bodyRect.centerY() - (fm.ascent + fm.descent) / 2f
                canvas.drawText(text, bodyRect.centerX(), baseline, textPaint)
            }

            STYLE_PILL -> {
                // 实心反白胶囊（数字透明镂空，状态栏上最清晰）
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                val pillRect = RectF(4f, 16f, 92f, 80f)
                canvas.drawRoundRect(pillRect, 20f, 20f, bgPaint)

                val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 38f else 50f
                }
                val fm = clearPaint.fontMetrics
                val baseline = pillRect.centerY() - (fm.ascent + fm.descent) / 2f
                canvas.drawText(text, pillRect.centerX(), baseline, clearPaint)
            }

            else -> {
                // 纯数字
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 54f else 74f
                }
                val fm = textPaint.fontMetrics
                val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
                canvas.drawText(text, size / 2f, baseline, textPaint)
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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(createBatteryIcon(level))
            .setContentTitle("$deviceName · 电量 $level%")
            .setContentText("点击打开耳机电量面板")
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
            .setContentTitle(title)
            .setContentText("连接蓝牙耳机后自动显示电量")
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
            description = "在状态栏实时显示已连接蓝牙耳机的电量百分比"
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
