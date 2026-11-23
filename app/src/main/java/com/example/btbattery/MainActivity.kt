package com.example.btbattery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private lateinit var switchOverlay: SwitchMaterial
    private lateinit var switchPreview: SwitchMaterial
    private lateinit var seekOverlayX: SeekBar
    private lateinit var seekOverlayY: SeekBar
    private lateinit var seekOverlaySize: SeekBar
    private lateinit var tvOverlayX: TextView
    private lateinit var tvOverlayY: TextView
    private lateinit var tvOverlaySize: TextView

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
        switchOverlay = findViewById(R.id.switchOverlay)
        switchPreview = findViewById(R.id.switchPreview)
        seekOverlayX = findViewById(R.id.seekOverlayX)
        seekOverlayY = findViewById(R.id.seekOverlayY)
        seekOverlaySize = findViewById(R.id.seekOverlaySize)
        tvOverlayX = findViewById(R.id.tvOverlayX)
        tvOverlayY = findViewById(R.id.tvOverlayY)
        tvOverlaySize = findViewById(R.id.tvOverlaySize)

        setupStyleToggle()
        setupOverlayControls()

        findViewById<MaterialButton>(R.id.btnRefresh).setOnClickListener {
            ensurePermissionsAndStart()
        }

        btnToggleService.setOnClickListener {
            if (BatteryStateHolder.currentState.serviceRunning) {
                stopService(Intent(this, BatteryMonitorService::class.java))
            } else {
                ensurePermissionsAndStart()
            }
        }

        findViewById<MaterialButton>(R.id.btnNotifySetting).setOnClickListener {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        }

        findViewById<MaterialButton>(R.id.btnAppInfo).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        ensurePermissionsAndStart()
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

        switchOverlay.isChecked = prefs.getBoolean(BatteryMonitorService.KEY_OVERLAY_ENABLED, true)
        switchPreview.isChecked = prefs.getBoolean(BatteryMonitorService.KEY_OVERLAY_PREVIEW, false)

        val savedX = prefs.getInt(BatteryMonitorService.KEY_OVERLAY_X, 64)
        val savedY = prefs.getInt(BatteryMonitorService.KEY_OVERLAY_Y, 12)
        val savedSize = prefs.getInt(BatteryMonitorService.KEY_OVERLAY_SIZE, 20)

        seekOverlayX.progress = savedX.coerceIn(0, 320)
        seekOverlayY.progress = savedY.coerceIn(0, 56)
        seekOverlaySize.progress = (savedSize - 14).coerceIn(0, 18)

        tvOverlayX.text = "水平位置 X：$savedX dp"
        tvOverlayY.text = "垂直位置 Y：$savedY dp"
        tvOverlaySize.text = "图标大小：$savedSize dp"

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

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (seekBar.id) {
                    R.id.seekOverlayX -> {
                        tvOverlayX.text = "水平位置 X：$progress dp"
                        prefs.edit().putInt(BatteryMonitorService.KEY_OVERLAY_X, progress).commit()
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

    private fun refreshOverlayPermButton() {
        val granted = Settings.canDrawOverlays(this)
        btnOverlayPerm.text = if (granted) "✓ 已获得悬浮窗权限" else "授予悬浮窗权限（顶部显示必开）"
    }

    private fun notifyOverlayUpdate() {
        if (!BatteryStateHolder.currentState.serviceRunning) {
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
        refreshOverlayPermButton()
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
}
