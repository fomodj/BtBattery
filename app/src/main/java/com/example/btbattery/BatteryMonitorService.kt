package com.example.btbattery

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.ImageView
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
        const val ACTION_UPDATE_OVERLAY = "com.example.btbattery.ACTION_UPDATE_OVERLAY"
        const val ACTION_KEEP_ALIVE = "com.example.btbattery.ACTION_KEEP_ALIVE"

        const val PREFS_NAME = "bt_battery_prefs"
        const val KEY_ICON_STYLE = "icon_style_v3"
        const val KEY_PINNED_ADDRESS = "pinned_device_address"

        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_OVERLAY_PREVIEW = "overlay_preview"
        const val KEY_OVERLAY_X = "overlay_x_dp"
        const val KEY_OVERLAY_Y = "overlay_y_dp"
        const val KEY_OVERLAY_SIZE = "overlay_size_dp"

        const val KEY_HIDE_RECENTS = "hide_from_recents"
        const val KEY_KEEP_ALIVE = "keep_alive_enabled"
        const val KEY_USER_STOPPED = "user_manually_stopped"

        const val STYLE_BADGE = 0
        const val STYLE_RING = 1
        const val STYLE_BATTERY = 2

        private const val WATCHDOG_REQ_CODE = 2001
        private const val RESTART_REQ_CODE = 2002

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

    private var overlayView: ImageView? = null
    private var currentConnectedLevel: Int = -1
    private var isStatusBarVisible: Boolean = true

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

        val vendorFilter = IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + ".76")
        }
        ContextCompat.registerReceiver(this, btReceiver, vendorFilter, ContextCompat.RECEIVER_EXPORTED)

        showIdleNotification("等待耳机连接")
        syncWatchdogSchedule()
        refreshAudioDeviceBattery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncWatchdogSchedule()
        if (intent?.action == ACTION_UPDATE_OVERLAY) {
            updateOverlayView()
            return START_STICKY
        }
        refreshAudioDeviceBattery()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val keepAlive = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        val userStopped = prefs.getBoolean(KEY_USER_STOPPED, false)
        if (keepAlive && !userStopped) {
            scheduleQuickRestart()
        }
    }

    private fun buildKeepAlivePendingIntent(reqCode: Int): PendingIntent {
        val intent = Intent(this, BootReceiver::class.java).apply {
            action = ACTION_KEEP_ALIVE
        }
        return PendingIntent.getBroadcast(
            this,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun syncWatchdogSchedule() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val keepAlive = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        val userStopped = prefs.getBoolean(KEY_USER_STOPPED, false)
        val am = getSystemService(AlarmManager::class.java) ?: return
        val pi = buildKeepAlivePendingIntent(WATCHDOG_REQ_CODE)

        if (keepAlive && !userStopped) {
            // 非唤醒式低耗守护：不打断深度休眠，仅在手机亮屏活动间隙自动检查并复活进程
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pi
            )
        } else {
            am.cancel(pi)
        }
    }

    private fun scheduleQuickRestart() {
        try {
            val am = getSystemService(AlarmManager::class.java) ?: return
            val pi = buildKeepAlivePendingIntent(RESTART_REQ_CODE)
            am.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1500L,
                pi
            )
        } catch (_: Exception) {
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOverlayView()
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

        val connectedMap = LinkedHashMap<String, BluetoothDevice>()
        try {
            headsetProxy?.connectedDevices?.forEach { connectedMap[it.address] = it }
            a2dpProxy?.connectedDevices?.forEach { connectedMap[it.address] = it }
        } catch (_: SecurityException) {}

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
            logBuilder.append("若奢音 X5 处于本地 MP3 模式，蓝牙会暂时断开。")
            updateUiAndIdle("未检测到已连接耳机", "连上耳机后顶部自动显示电量", logBuilder.toString().trim(), pairedUiList)
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
            currentConnectedLevel = finalLevel
            updateConnectedNotification(name, finalLevel)
            updateOverlayView()
            BatteryStateHolder.update(
                BatteryUiState(
                    serviceRunning = true,
                    deviceName = name,
                    deviceSubtitle = "已连接 · 顶部与通知栏同步中",
                    batteryLevel = finalLevel,
                    diagnosticLog = logBuilder.toString().trim(),
                    pairedDevices = pairedUiList
                )
            )
        } else {
            currentConnectedLevel = -1
            val now = System.currentTimeMillis()
            if (now - lastGattAttemptMs > 8000L) {
                lastGattAttemptMs = now
                tryReadGattBattery(target)
            }
            showIdleNotification("$name 已连接（获取电量中）")
            updateOverlayView()
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
        currentConnectedLevel = -1
        showIdleNotification("等待耳机连接")
        updateOverlayView()
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

    private fun updateOverlayView() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        val preview = prefs.getBoolean(KEY_OVERLAY_PREVIEW, false)
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val displayLevel = if (currentConnectedLevel in 0..100) currentConnectedLevel else if (preview) 70 else -1

        if (!enabled || !Settings.canDrawOverlays(this) || !isPortrait || displayLevel !in 0..100) {
            removeOverlayView()
            return
        }

        val iconStyle = getIconStyle()
        val xDp = prefs.getInt(KEY_OVERLAY_X, 64)
        val yDp = prefs.getInt(KEY_OVERLAY_Y, 12)
        val sizeDp = prefs.getInt(KEY_OVERLAY_SIZE, 20)

        val heightPx = dpToPx(sizeDp)
        val widthPx = if (iconStyle == STYLE_BATTERY) (heightPx * 1.38f).toInt() else heightPx
        val bitmap = createOverlayGraphic(displayLevel, iconStyle)

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(xDp)
            y = dpToPx(yDp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val wm = getSystemService(WindowManager::class.java) ?: return
        try {
            if (overlayView == null) {
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bitmap)
                    alpha = if (isStatusBarVisible) 1f else 0f
                    setOnApplyWindowInsetsListener { view, insets ->
                        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            insets.isVisible(WindowInsets.Type.statusBars())
                        } else {
                            @Suppress("DEPRECATION")
                            insets.systemWindowInsetTop > 0
                        }
                        if (isStatusBarVisible != visible) {
                            isStatusBarVisible = visible
                            view.animate()
                                .alpha(if (visible) 1f else 0f)
                                .setDuration(120L)
                                .start()
                        }
                        insets
                    }
                }
                wm.addView(iv, params)
                iv.requestApplyInsets()
                overlayView = iv
            } else {
                overlayView?.setImageBitmap(bitmap)
                overlayView?.alpha = if (isStatusBarVisible) 1f else 0f
                wm.updateViewLayout(overlayView, params)
            }
        } catch (_: Exception) {
            removeOverlayView()
        }
    }

    private fun removeOverlayView() {
        val view = overlayView ?: return
        overlayView = null
        isStatusBarVisible = true
        try {
            getSystemService(WindowManager::class.java)?.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun createOverlayGraphic(level: Int, iconStyle: Int): Bitmap {
        val accent = getLevelColor(level)
        val endColor = when {
            level <= 20 -> Color.parseColor("#DC2626")
            level <= 45 -> Color.parseColor("#D97706")
            else -> Color.parseColor("#059669")
        }
        val text = level.toString()

        return when (iconStyle) {
            STYLE_BATTERY -> {
                val w = 192
                val h = 140
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val bodyRect = RectF(4f, 12f, 170f, 128f)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), accent, endColor, Shader.TileMode.CLAMP)
                }
                canvas.drawRoundRect(bodyRect, 28f, 28f, bgPaint)

                val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = endColor
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(RectF(174f, 44f, 188f, 96f), 8f, 8f, tipPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 64f else 80f
                }
                val fm = textPaint.fontMetrics
                canvas.drawText(text, bodyRect.centerX(), bodyRect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
                bitmap
            }

            STYLE_RING -> {
                val size = 144
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(185, 18, 22, 30)
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, bgPaint)

                val pad = 14f
                val oval = RectF(pad, pad, size - pad, size - pad)
                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(75, 255, 255, 255)
                    style = Paint.Style.STROKE
                    strokeWidth = 14f
                }
                canvas.drawOval(oval, trackPaint)

                val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    style = Paint.Style.STROKE
                    strokeWidth = 14f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawArc(oval, -90f, 360f * (level / 100f), false, arcPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 44f else 58f
                }
                val fm = textPaint.fontMetrics
                canvas.drawText(text, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
                bitmap
            }

            else -> {
                val size = 144
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val badgeRect = RectF(4f, 4f, size - 4f, size - 4f)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), accent, endColor, Shader.TileMode.CLAMP)
                }
                canvas.drawRoundRect(badgeRect, 36f, 36f, bgPaint)

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textSize = if (level == 100) 58f else 76f
                }
                val fm = textPaint.fontMetrics
                canvas.drawText(text, badgeRect.centerX(), badgeRect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
                bitmap
            }
        }
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
        level <= 20 -> Color.parseColor("#EF4444")
        level <= 45 -> Color.parseColor("#F59E0B")
        else -> Color.parseColor("#10B981")
    }

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
            .setSmallIcon(IconCompat.createWithBitmap(cardBitmap))
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
        removeOverlayView()
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        activeGatt?.close()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        isForegroundStarted = false

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val userStopped = prefs.getBoolean(KEY_USER_STOPPED, false)
        val keepAlive = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        if (userStopped || !keepAlive) {
            syncWatchdogSchedule()
        } else {
            scheduleQuickRestart()
        }

        BatteryStateHolder.update(
            BatteryStateHolder.currentState.copy(serviceRunning = false)
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
