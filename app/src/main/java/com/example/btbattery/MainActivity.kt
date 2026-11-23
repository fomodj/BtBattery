package com.example.btbattery

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var batteryRing: BatteryRingView
    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceSub: TextView
    private lateinit var tvDeviceLog: TextView
    private lateinit var tvServiceBadge: TextView
    private lateinit var btnToggleService: MaterialButton
    private lateinit var toggleIconStyle: MaterialButtonToggleGroup
    private lateinit var chipGroupDevices: ChipGroup

    private lateinit var btnOverlayPerm: MaterialButton
    private lateinit var btnAccessibilityPerm: MaterialButton
    private lateinit var switchOverlay: SwitchMaterial
    private lateinit var switchPreview: SwitchMaterial
    private lateinit var switchAutoFollow: SwitchMaterial
    private lateinit var seekOverlayX: SeekBar
    private lateinit var seekOverlayY: SeekBar
    private lateinit var seekOverlaySize: SeekBar
    private lateinit var tvOverlayX: TextView
    private lateinit var tvOverlayY: TextView
    private lateinit var tvOverlaySize: TextView

    private lateinit var switchHideRecents: SwitchMaterial
    private lateinit var switchKeepAlive: SwitchMaterial
    private lateinit var btnBatteryOpt: MaterialButton

    private var lastChipSignature: String = ""

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startMonitorService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        batteryRing = findViewById(R.id.batteryRing)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvDeviceSub = findViewById(R.id.tvDeviceSub)
        tvDeviceLog = findViewById(R.id.tvDeviceLog)
        tvServiceBadge = findViewById(R.id.tvServiceBadge)
        btnToggleService = findViewById(R.id.btnToggleService)
        toggleIconStyle = findViewById(R.id.toggleIconStyle)
        chipGroupDevices = findViewById(R.id.chipGroupDevices)

        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        btnAccessibilityPerm = findViewById(R.id.btnAccessibilityPerm)
        switchOverlay = findViewById(R.id.switchOverlay)
        switchPreview = findViewById(R.id.switchPreview)
        switchAutoFollow = findViewById(R.id.switchAutoFollow)
        seekOverlayX = findViewById(R.id.seekOverlayX)
        seekOverlayY = findViewById(R.id.seekOverlayY)
        seekOverlaySize = findViewById(R.id.seekOverlaySize)
        tvOverlayX = findViewById(R.id.tvOverlayX)
        tvOverlayY = findViewById(R.id.tvOverlayY)
        tvOverlaySize = findViewById(R.id.tvOverlaySize)

        switchHideRecents = findViewById(R.id.switchHideRecents)
        switchKeepAlive = findViewById(R.id.switchKeepAlive)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)

        setupStyleToggle()
        setupOverlayControls()
        setupBackgroundControls()

        findViewById<MaterialButton>(R.id.btnRefresh).setOnClickListener {
            setUserStopped(false)
            ensurePermissionsAndStart()
        }

        btnToggleService.setOnClickListener {
            if (BatteryStateHolder.currentState.serviceRunning) {
                setUserStopped(true)
                stopService(Intent(this, BatteryMonitorService::class.java))
            } else {
                setUserStopped(false)
                ensurePermissionsAndStart()
            }
        }

        val prefs = getSharedPreferences(BatteryMonitorService.PREFS_NAME, MODE_PRIVATE)
        applyExcludeFromRecents(prefs.getBoolean(BatteryMonitorService.KEY_HIDE_RECENTS, false))
        if (!prefs.getBoolean(BatteryMonitorService.KEY_USER_STOPPED, false)) {
            ensurePermissionsAndStart()
        }
    }

    @SuppressLint("BatteryLife")
    private fun setupBackgroundControls() {
        val prefs = getSharedPreferences(BatteryMonitorService.PREFS_NAME, MODE_PRIVATE)

        switchHideRecents.isChecked = prefs.getBoolean(BatteryMonitorService.KEY_HIDE_RECENTS, false)
        switchKeepAlive.isChecked = prefs.getBoolean(BatteryMonitorService.KEY_KEEP_ALIVE, true)

        switchHideRecents.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BatteryMonitorService.KEY_HIDE_RECENTS, checked).commit()
            applyExcludeFromRecents(checked)
        }

        switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BatteryMonitorService.KEY_KEEP_ALIVE, checked).commit()
            if (BatteryStateHolder.currentState.serviceRunning) {
                startMonitorService()
            }
        }

        btnBatteryOpt.setOnClickListener {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    openAppDetails()
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnMiuiAutostart).setOnClickListener {
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
                startActivity(intent)
            } catch (_: Exception) {
                openAppDetails()
            }
        }

        findViewById<MaterialButton>(R.id.btnMiuiPower).setOnClickListener {
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )
                    putExtra("package_name", packageName)
                    putExtra("package_label", "耳机电量")
                }
                startActivity(intent)
            } catch (_: Exception) {
                openAppDetails()
            }
        }

        findViewById<MaterialButton>(R.id.btnNotifySetting).setOnClickListener {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        }

        findViewById<MaterialButton>(R.id.btnAppInfo).setOnClickListener {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun applyExcludeFromRecents(hide: Boolean) {
        try {
            val am = getSystemService(ActivityManager::class.java)
            am?.appTasks?.forEach { task ->
                task.setExcludeFromRecents(hide)
            }
        } catch (_: Exception) {
        }
    }

    private fun setUserStopped(stopped: Boolean) {
        getSharedPreferences(BatteryMonitorService.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(BatteryMonitorService.KEY_USER_STOPPED, stopped)
            .commit()
    }

    private fun refreshSystemPermButtons() {
        val overlayGranted = Settings.canDrawOverlays(this)
        btnOverlayPerm.text = if (overlayGranted) "✓ 已获得悬浮窗权限" else "授予悬浮窗权限（顶部显示必开）"

        val accEnabled = StatusBarAccessibilityService.isRunning || isAccessibilityServiceEnabled()
        btnAccessibilityPerm.text = if (accEnabled) "✓ 已开启无障碍状态栏同步" else "开启无障碍同步（毫秒级跟随状态栏）"

        val pm = getSystemService(PowerManager::class.java)
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(packageName) == true
        btnBatteryOpt.text = if (ignoringBattery) "✓ 已加入系统「忽略电池优化」白名单" else "一键申请「忽略电池优化」（防杀后台）"
    }

    private fun setupOverlayControls() {
        val prefs = getSharedPreferences(BatteryMonitorService.PREFS_NAME, MODE_PRIVATE)

        btnOverlayPerm.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnAccessibilityPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        switchOverlay.isChecked = prefs.getBoolean(BatteryMonitorService.KEY_OVERLAY_ENABLED, true)
        switchPreview.isChecked = prefs.getBoolean(BatteryMonitorService.KEY_OVERLAY_PREVIEW, false)
        val initialAutoFollow = prefs.getBoolean(BatteryMonitorService.KEY_AUTO_FOLLOW_LEFT, true)
        switchAutoFollow.isChecked = initialAutoFollow

        val savedY = prefs.getInt(BatteryMonitorService.KEY_OVERLAY_Y, 12)
        val savedSize = prefs.getInt(BatteryMonitorService.KEY_OVERLAY_SIZE, 20)

        seekOverlayY.progress = savedY.coerceIn(0, 56)
        seekOverlaySize.progress = (savedSize - 14).coerceIn(0, 18)
        tvOverlayY.text = "垂直位置 Y：$savedY dp"
        tvOverlaySize.text = "图标大小：$savedSize dp"
        updateXSliderMode(initialAutoFollow)

        switchOverlay.setOnCheckedChangeListener { _, checked ->
            if (checked && !Settings.canDrawOverlays(this)) {
                btnOverlayPerm.performClick()
            }
            prefs.edit().putBoolean(BatteryMonitorService.KEY_OVERLAY_ENABLED, checked).commit()
            notifyOverlayUpdate()
        }

        switchPreview.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BatteryMonitorService.KEY_OVERLAY_PREVIEW, checked).commit()
            notifyOverlayUpdate()
        }

        switchAutoFollow.setOnCheckedChangeListener { _, checked ->
            if (checked && !StatusBarAccessibilityService.isRunning && !isAccessibilityServiceEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            prefs.edit().putBoolean(BatteryMonitorService.KEY_AUTO_FOLLOW_LEFT, checked).commit()
            updateXSliderMode(checked)
            notifyOverlayUpdate()
        }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (seekBar.id) {
                    R.id.seekOverlayX -> {
                        if (switchAutoFollow.isChecked) {
                            tvOverlayX.text = "紧随右侧间距：$progress dp"
                            prefs.edit().putInt(BatteryMonitorService.KEY_FOLLOW_GAP_DP, progress).commit()
                        } else {
                            tvOverlayX.text = "水平位置 X：$progress dp"
                            prefs.edit().putInt(BatteryMonitorService.KEY_OVERLAY_X, progress).commit()
                        }
                    }
                    R.id.seekOverlayY -> {
                        tvOverlayY.text = "垂直位置 Y：$progress dp"
                        prefs.edit().putInt(BatteryMonitorService.KEY_OVERLAY_Y, progress).commit()
                    }
                    R.id.seekOverlaySize -> {
                        val dp = progress + 14
                        tvOverlaySize.text = "图标大小：$dp dp"
                        prefs.edit().putInt(BatteryMonitorService.KEY_OVERLAY_SIZE, dp).commit()
                    }
                }
                notifyOverlayUpdate()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        }

        seekOverlayX.setOnSeekBarChangeListener(seekListener)
        seekOverlayY.setOnSeekBarChangeListener(seekListener)
        seekOverlaySize.setOnSeekBarChangeListener(seekListener)
    }

    private fun notifyOverlayUpdate() {
        if (!BatteryStateHolder.currentState.serviceRunning) {
            setUserStopped(false)
            ensurePermissionsAndStart()
            return
        }
        val intent = Intent(this, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_UPDATE_OVERLAY
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun setupStyleToggle() {
        val prefs = getSharedPreferences(BatteryMonitorService.PREFS_NAME, MODE_PRIVATE)
        val savedStyle = prefs.getInt(BatteryMonitorService.KEY_ICON_STYLE, BatteryMonitorService.STYLE_BADGE)
        val checkedId = when (savedStyle) {
            BatteryMonitorService.STYLE_RING -> R.id.btnStyleRing
            BatteryMonitorService.STYLE_BATTERY -> R.id.btnStyleBattery
            else -> R.id.btnStyleBadge
        }
        toggleIconStyle.check(checkedId)

        toggleIconStyle.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selected = when (id) {
                R.id.btnStyleRing -> BatteryMonitorService.STYLE_RING
                R.id.btnStyleBattery -> BatteryMonitorService.STYLE_BATTERY
                else -> BatteryMonitorService.STYLE_BADGE
            }
            prefs.edit().putInt(BatteryMonitorService.KEY_ICON_STYLE, selected).commit()
            if (BatteryStateHolder.currentState.serviceRunning) {
                startMonitorService()
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            requestPerms.launch(needed.toTypedArray())
        } else {
            startMonitorService()
        }
    }

    private fun startMonitorService() {
        val intent = Intent(this, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_REFRESH
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onResume() {
        super.onResume()
        refreshSystemPermButtons()
        val prefs = getSharedPreferences(BatteryMonitorService.PREFS_NAME, MODE_PRIVATE)
        applyExcludeFromRecents(prefs.getBoolean(BatteryMonitorService.KEY_HIDE_RECENTS, false))
        BatteryStateHolder.setListener { state ->
            runOnUiThread { renderState(state) }
        }
        if (BatteryStateHolder.currentState.serviceRunning) {
            notifyOverlayUpdate()
        }
    }

    override fun onPause() {
        BatteryStateHolder.setListener(null)
        super.onPause()
    }

    private fun renderState(state: BatteryUiState) {
        batteryRing.setBatteryLevel(state.batteryLevel)
        tvDeviceName.text = state.deviceName
        tvDeviceSub.text = state.deviceSubtitle
        tvDeviceLog.text = state.diagnosticLog

        if (state.serviceRunning) {
            tvServiceBadge.text = "● 监听中"
            btnToggleService.text = "停止监听"
        } else {
            tvServiceBadge.text = "○ 已停止"
            btnToggleService.text = "启动监听"
        }

        renderDeviceChips(state.pairedDevices)
    }

    private fun renderDeviceChips(devices: List<PairedDeviceInfo>) {
        val prefs = getSharedPreferences(BatteryMonitorService.PREFS_NAME, MODE_PRIVATE)
        val pinnedAddr = prefs.getString(BatteryMonitorService.KEY_PINNED_ADDRESS, "") ?: ""
        val sig = pinnedAddr + "|" + devices.joinToString(",") { "${it.address}:${it.connected}:${it.battery}" }
        if (sig == lastChipSignature) return
        lastChipSignature = sig

        chipGroupDevices.removeAllViews()

        val autoChip = Chip(this).apply {
            id = android.view.View.generateViewId()
            text = "自动识别"
            isCheckable = true
            isChecked = pinnedAddr.isEmpty()
            setOnClickListener {
                prefs.edit().putString(BatteryMonitorService.KEY_PINNED_ADDRESS, "").commit()
                lastChipSignature = ""
                startMonitorService()
            }
        }
        chipGroupDevices.addView(autoChip)

        devices.forEach { dev ->
            val label = buildString {
                if (dev.connected) append("● ")
                append(dev.name)
                if (dev.battery in 0..100) append(" (${dev.battery}%)")
            }
            val chip = Chip(this).apply {
                id = android.view.View.generateViewId()
                text = label
                isCheckable = true
                isChecked = pinnedAddr.equals(dev.address, ignoreCase = true)
                setOnClickListener {
                    prefs.edit().putString(BatteryMonitorService.KEY_PINNED_ADDRESS, dev.address).commit()
                    lastChipSignature = ""
                    startMonitorService()
                }
            }
            chipGroupDevices.addView(chip)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${StatusBarAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun updateXSliderMode(isAuto: Boolean) {
        val prefs = getSharedPreferences(BatteryMonitorService.PREFS_NAME, MODE_PRIVATE)
        if (isAuto) {
            seekOverlayX.max = 24
            val gap = prefs.getInt(BatteryMonitorService.KEY_FOLLOW_GAP_DP, 4).coerceIn(0, 24)
            seekOverlayX.progress = gap
            tvOverlayX.text = "紧随右侧间距：$gap dp"
        } else {
            seekOverlayX.max = 320
            val x = prefs.getInt(BatteryMonitorService.KEY_OVERLAY_X, 64).coerceIn(0, 320)
            seekOverlayX.progress = x
            tvOverlayX.text = "水平位置 X：$x dp"
        }
    }
}
