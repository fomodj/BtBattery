package com.example.btbattery

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "bt_battery_channel_v3"
        const val NOTIFY_ID = 1001
        const val ACTION_BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        const val ACTION_REFRESH = "com.example.btbattery.ACTION_REFRESH"
        const val PREFS_NAME = "bt_battery_prefs"
        const val KEY_ICON_STYLE = "icon_style_v3"
        const val KEY_PINNED_ADDRESS = "pinned_device_address"

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
    private var isForegroundStarted = false
    private var lastGattAttemptMs = 0L

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
            when (intent.action) {
                ACTION_BATTERY_CHANGED -> {
                    val device = getDeviceExtra(intent)
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (device != null && level in 0..100) {
                        cachedLevels[device.address] = level
                    }
                }
                BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                    parseVendorBatteryEvent(intent)
                }
            }
            refreshAudioDeviceBattery()
            mainHandler.postDelayed({ refreshAudioDeviceBattery() }, 1200)
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

        val mainFilter = IntentFilter().apply {
            addAction(ACTION_BATTERY_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, btReceiver, mainFilter, ContextCompat.RECEIVER_EXPORTED)

        // 单独注册 HFP 厂商扩展指令广播（解析 +IPHONEACCEV，适配奢音 X5 等第三方骨传导耳机）
        val vendorFilter = IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + ".76")
        }
        ContextCompat.registerReceiver(this, btReceiver, vendorFilter, ContextCompat.RECEIVER_EXPORTED)

        showIdleNotification("等待耳机连接")
        refreshAudioDeviceBattery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshAudioDeviceBattery()
        return START_STICKY
    }

    private fun parseVendorBatteryEvent(intent: Intent) {
        try {
            val cmd = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD) ?: return
            val device = getDeviceExtra(intent) ?: return
            val rawArgs = intent.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*> ?: return
            if (cmd.contains("IPHONEACCEV", ignoreCase = true) && rawArgs.size >= 3) {
                var i = 1
                while (i + 1 < rawArgs.size) {
                    val key = rawArgs[i]?.toString()?.toIntOrNull()
                    val value = rawArgs[i + 1]?.toString()?.toIntOrNull()
                    if (key == 1 && value != null && value in 0..9) {
                        cachedLevels[device.address] = (value + 1) * 10
                        break
                    }
                    i += 2
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun isLikelyWatchOrBand(device: BluetoothDevice): Boolean {
        val major = device.bluetoothClass?.majorDeviceClass
        if (major == BluetoothClass.Device.Major.WEARABLE) return true
        val upper = (device.name ?: "").uppercase(Locale.ROOT)
        return upper.contains("WATCH") || upper.contains("BAND") || upper.contains("手环") || upper.contains("手表")
    }

    private fun isPriorityHeadphone(name: String): Boolean {
        val upper = name.uppercase(Locale.ROOT)
        return upper.contains("SHEYIN") || upper == "X5" || upper.contains("X5") || upper.contains("奢音")
    }

    @SuppressLint("MissingPermission")
    private fun refreshAudioDeviceBattery() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            updateUiAndIdle("蓝牙未开启", "请先打开手机蓝牙", "蓝牙适配器未启用", emptyList())
            return
        }

        val bonded = try {
            adapter.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            updateUiAndIdle("缺少蓝牙权限", "请授予「附近设备」权限", "无 BLUETOOTH_CONNECT 权限", emptyList())
            return
        }

        // 1. 汇总来自 HFP / A2DP / AudioManager / 反射 的所有已连接蓝牙设备
        val connectedMap = LinkedHashMap<String, BluetoothDevice>()
        try {
            headsetProxy?.connectedDevices?.forEach { connectedMap[it.address] = it }
            a2dpProxy?.connectedDevices?.forEach { connectedMap[it.address] = it }
        } catch (_: SecurityException) {}

        // 2. 通过官方 AudioManager 捕获当前活动的蓝牙音频输出（兼容非标准 DeviceClass 的骨传导耳机）
        val activeAudioAddresses = HashSet<String>()
        val activeAudioNames = HashSet<String>()
        try {
            val audioManager = getSystemService(AudioManager::class.java)
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach { info ->
                val isBtAudio = info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                if (isBtAudio) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.address.isNotBlank()) {
                        activeAudioAddresses.add(info.address.uppercase(Locale.ROOT))
                    }
                    info.productName?.toString()?.let { activeAudioNames.add(it.uppercase(Locale.ROOT)) }
                }
            }
        } catch (_: Exception) {}

        bonded.forEach { dev ->
            val addrUpper = dev.address.uppercase(Locale.ROOT)
            val nameUpper = (dev.name ?: "").uppercase(Locale.ROOT)
            val connectedByAudio = activeAudioAddresses.contains(addrUpper) || activeAudioNames.contains(nameUpper)
            val connectedByReflect = isDeviceConnectedReflection(dev)
            if (connectedByAudio || connectedByReflect) {
                connectedMap[dev.address] = dev
            }
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pinnedAddr = prefs.getString(KEY_PINNED_ADDRESS, "") ?: ""

        // 构建 UI 设备列表（过滤掉手表手环，除非已被用户手动锁定）
        val pairedUiList = bonded
            .filter { dev -> !isLikelyWatchOrBand(dev) || dev.address.equals(pinnedAddr, ignoreCase = true) }
            .map { dev ->
                val isConn = connectedMap.containsKey(dev.address)
                val lvl = resolveBatteryLevel(dev)
                PairedDeviceInfo(
                    name = dev.name ?: dev.address,
                    address = dev.address,
                    connected = isConn,
                    battery = if (isConn) lvl else -1
                )
            }
            .sortedWith(compareByDescending<PairedDeviceInfo> { it.connected }.thenByDescending { isPriorityHeadphone(it.name) })

        // 筛选候选耳机
        val candidates = connectedMap.values.filter { dev ->
            if (pinnedAddr.isNotEmpty()) {
                dev.address.equals(pinnedAddr, ignoreCase = true)
            } else {
                !isLikelyWatchOrBand(dev)
            }
        }.sortedWith(
            compareByDescending<BluetoothDevice> { isPriorityHeadphone(it.name ?: "") }
                .thenByDescending { resolveBatteryLevel(it) in 0..100 }
        )

        val logBuilder = StringBuilder()
        if (pinnedAddr.isNotEmpty()) {
            logBuilder.append("当前模式：已锁定设备 ($pinnedAddr)\n")
        } else {
            logBuilder.append("当前模式：自动识别（已自动过滤手表/手环）\n")
        }

        if (candidates.isEmpty()) {
            logBuilder.append("当前无已连接的目标耳机\n")
            if (connectedMap.isNotEmpty()) {
                logBuilder.append("其他已连接蓝牙：")
                logBuilder.append(connectedMap.values.joinToString("、") { it.name ?: it.address })
                logBuilder.append("\n")
            }
            logBuilder.append("若奢音 X5 处于本地 MP3 游泳模式，蓝牙会暂时断开。")
            updateUiAndIdle("未检测到已连接耳机", "连上耳机后通知栏自动显示", logBuilder.toString().trim(), pairedUiList)
            return
        }

        val target = candidates.first()
        val name = target.name ?: "蓝牙耳机"
        val reflectLevel = getBatteryLevelReflection(target)
        val cacheLevel = cachedLevels[target.address] ?: -1
        val finalLevel = if (reflectLevel in 0..100) reflectLevel else cacheLevel

        candidates.forEach { dev ->
            val rLvl = getBatteryLevelReflection(dev)
            val cLvl = cachedLevels[dev.address] ?: -1
            val devName = dev.name ?: dev.address
            logBuilder.append("• [已连接] $devName | 系统:$rLvl% 扩展:$cLvl%\n")
        }

        if (finalLevel in 0..100) {
            updateConnectedNotification(name, finalLevel)
            BatteryStateHolder.update(
                BatteryUiState(
                    serviceRunning = true,
                    deviceName = name,
                    deviceSubtitle = "已连接 · 通知栏实时同步",
                    batteryLevel = finalLevel,
                    diagnosticLog = logBuilder.toString().trim(),
                    pairedDevices = pairedUiList
                )
            )
        } else {
            val now = System.currentTimeMillis()
            if (now - lastGattAttemptMs > 8000L) {
                lastGattAttemptMs = now
                tryReadGattBattery(target)
            }
            showIdleNotification("$name 已连接（获取电量中）")
            BatteryStateHolder.update(
                BatteryUiState(
                    serviceRunning = true,
                    deviceName = name,
                    deviceSubtitle = "已连接，尝试读取 HFP/BLE 电量...",
                    batteryLevel = -1,
                    diagnosticLog = (logBuilder.toString() + "正在通过 BLE 电量服务(0x180F)兜底读取...").trim(),
                    pairedDevices = pairedUiList
                )
            )
        }
    }

    private fun resolveBatteryLevel(device: BluetoothDevice): Int {
        val reflectLevel = getBatteryLevelReflection(device)
        if (reflectLevel in 0..100) return reflectLevel
        return cachedLevels[device.address] ?: -1
    }

    private fun updateUiAndIdle(title: String, sub: String, log: String, list: List<PairedDeviceInfo>) {
        showIdleNotification("等待耳机连接")
        BatteryStateHolder.update(
            BatteryUiState(
                serviceRunning = true,
                deviceName = title,
                deviceSubtitle = sub,
                batteryLevel = -1,
                diagnosticLog = log,
                pairedDevices = list
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

    // 1. 通知栏自定义卡片左侧：全彩高清动态图标
    private fun createCardGraphic(level: Int, iconStyle: Int): Bitmap {
        val size = 144
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val accent = getLevelColor(level)
        val text = level.toString()

        when (iconStyle) {
            STYLE_RING -> {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent))
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, bgPaint)

                val pad = 16f
                val oval = RectF(pad, pad, size - pad, size - pad)
                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent))
                    style = Paint.Style.STROKE
                    strokeWidth = 13f
                }
                canvas.drawOval(oval, trackPaint)

                val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    style = Paint.Style.STROKE
                    strokeWidth = 13f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawArc(oval, -90f, 360f * (level / 100f), false, arcPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 40f else 52f
                }
                val fm = textPaint.fontMetrics
                canvas.drawText(text, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
            }

            STYLE_BATTERY -> {
                val bodyRect = RectF(10f, 32f, 122f, 112f)
                val fillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent))
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(bodyRect, 20f, 20f, fillBg)

                val innerPad = 10f
                val maxW = (bodyRect.width() - innerPad * 2) * (level / 100f)
                if (maxW > 4f) {
                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(85, Color.red(accent), Color.green(accent), Color.blue(accent))
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(
                        RectF(bodyRect.left + innerPad, bodyRect.top + innerPad, bodyRect.left + innerPad + maxW, bodyRect.bottom - innerPad),
                        12f, 12f, fillPaint
                    )
                }

                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    style = Paint.Style.STROKE
                    strokeWidth = 9f
                }
                canvas.drawRoundRect(bodyRect, 20f, 20f, strokePaint)

                val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(RectF(126f, 56f, 136f, 88f), 5f, 5f, tipPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 40f else 52f
                }
                val fm = textPaint.fontMetrics
                canvas.drawText(text, bodyRect.centerX(), bodyRect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
            }

            else -> {
                // 圆角徽章：渐变高级底 + 纯白粗体数字
                val badgeRect = RectF(6f, 6f, size - 6f, size - 6f)
                val endColor = when {
                    level <= 20 -> Color.parseColor("#DC2626")
                    level <= 45 -> Color.parseColor("#D97706")
                    else -> Color.parseColor("#059669")
                }
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), accent, endColor, Shader.TileMode.CLAMP)
                }
                canvas.drawRoundRect(badgeRect, 34f, 34f, bgPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 52f else 66f
                }
                val fm = textPaint.fontMetrics
                canvas.drawText(text, badgeRect.centerX(), badgeRect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
            }
        }
        return bitmap
    }

    // 2. 待机态高清彩色圆角耳机图标（彻底消灭纯白方块）
    private fun createIdleCardGraphic(): Bitmap {
        val size = 144
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(6f, 6f, size - 6f, size - 6f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                Color.parseColor("#3B82F6"), Color.parseColor("#2563EB"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, 34f, 34f, bgPaint)

        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(RectF(36f, 34f, 108f, 106f), 180f, 180f, false, arcPaint)

        val earPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(30f, 68f, 48f, 106f), 9f, 9f, earPaint)
        canvas.drawRoundRect(RectF(96f, 68f, 114f, 106f), 9f, 9f, earPaint)
        return bitmap
    }

    // 3. 通知栏自定义圆角彩色进度条
    private fun createProgressBarBitmap(level: Int, accentColor: Int): Bitmap {
        val w = 480
        val h = 16
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = h / 2f

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 128, 128, 128)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, trackPaint)

        if (level in 1..100) {
            val fillW = (w * (level / 100f)).coerceAtLeast(h.toFloat())
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(RectF(0f, 0f, fillW, h.toFloat()), radius, radius, fillPaint)
        }
        return bitmap
    }

    // 4. 顶部状态栏纯白单色小图标（符合 Android Alpha 蒙版规范）
    private fun createStatusBarIcon(level: Int, iconStyle: Int): IconCompat {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val text = level.toString()

        when (iconStyle) {
            STYLE_RING -> {
                val oval = RectF(10f, 10f, 86f, 86f)
                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(100, 255, 255, 255)
                    style = Paint.Style.STROKE
                    strokeWidth = 9f
                }
                canvas.drawOval(oval, trackPaint)

                val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 9f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawArc(oval, -90f, 360f * (level / 100f), false, arcPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 28f else 38f
                }
                val fm = textPaint.fontMetrics
                canvas.drawText(text, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
            }

            STYLE_BATTERY -> {
                val bodyRect = RectF(6f, 18f, 82f, 78f)
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 7f
                }
                canvas.drawRoundRect(bodyRect, 14f, 14f, strokePaint)

                val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(RectF(85f, 36f, 92f, 60f), 4f, 4f, tipPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 30f else 42f
                }
                val fm = textPaint.fontMetrics
                canvas.drawText(text, bodyRect.centerX(), bodyRect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
            }

            else -> {
                val badgeRect = RectF(6f, 6f, 90f, 90f)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(badgeRect, 22f, 22f, bgPaint)

                val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 36f else 50f
                }
                val fm = cutoutPaint.fontMetrics
                canvas.drawText(text, badgeRect.centerX(), badgeRect.centerY() - (fm.ascent + fm.descent) / 2f, cutoutPaint)
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
        val iconStyle = getIconStyle()
        val accent = getLevelColor(level)
        val cardBitmap = createCardGraphic(level, iconStyle)
        val barBitmap = createProgressBarBitmap(level, accent)

        val views = RemoteViews(packageName, R.layout.notification_battery).apply {
            setImageViewBitmap(R.id.ivNotifIcon, cardBitmap)
            setTextViewText(R.id.tvNotifName, deviceName)
            setTextViewText(R.id.tvNotifPercent, "$level%")
            setTextColor(R.id.tvNotifPercent, accent)
            setImageViewBitmap(R.id.ivNotifBar, barBitmap)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(createStatusBarIcon(level, iconStyle))
            .setLargeIcon(cardBitmap)
            .setColor(accent)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setContentTitle("$deviceName · $level%")
            .setShowWhen(false)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        postNotification(notification)
    }

    private fun showIdleNotification(title: String) {
        val idleBitmap = createIdleCardGraphic()
        val blue = Color.parseColor("#3B82F6")
        val emptyBar = createProgressBarBitmap(0, blue)

        val views = RemoteViews(packageName, R.layout.notification_battery).apply {
            setImageViewBitmap(R.id.ivNotifIcon, idleBitmap)
            setTextViewText(R.id.tvNotifName, title)
            setTextViewText(R.id.tvNotifPercent, "--")
            setTextColor(R.id.tvNotifPercent, Color.parseColor("#9CA3AF"))
            setImageViewBitmap(R.id.ivNotifBar, emptyBar)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_headset)
            .setLargeIcon(idleBitmap)
            .setColor(blue)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setContentTitle(title)
            .setShowWhen(false)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        postNotification(notification)
    }

    private fun postNotification(notification: Notification) {
        if (!isForegroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFY_ID, notification)
            }
            isForegroundStarted = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "耳机电量卡片", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            description = "通知栏直接展示已连接蓝牙耳机电量与进度条"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        activeGatt?.close()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        isForegroundStarted = false
        BatteryStateHolder.update(
            BatteryStateHolder.currentState.copy(serviceRunning = false)
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
