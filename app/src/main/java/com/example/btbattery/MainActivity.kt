package com.example.btbattery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

class MainActivity : AppCompatActivity() {

    private lateinit var batteryRing: BatteryRingView
    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceSub: TextView
    private lateinit var tvDeviceLog: TextView
    private lateinit var tvServiceBadge: TextView
    private lateinit var btnToggleService: MaterialButton
    private lateinit var toggleIconStyle: MaterialButtonToggleGroup

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

        setupStyleToggle()

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

    private fun setupStyleToggle() {
        val prefs = getSharedPreferences(BatteryMonitorService.PREFS_NAME, MODE_PRIVATE)
        val savedStyle = prefs.getInt(BatteryMonitorService.KEY_ICON_STYLE, BatteryMonitorService.STYLE_BATTERY)
        val checkedId = when (savedStyle) {
            BatteryMonitorService.STYLE_PLAIN -> R.id.btnStylePlain
            BatteryMonitorService.STYLE_PILL -> R.id.btnStylePill
            else -> R.id.btnStyleBattery
        }
        toggleIconStyle.check(checkedId)

        toggleIconStyle.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val style = when (id) {
                R.id.btnStylePlain -> BatteryMonitorService.STYLE_PLAIN
                R.id.btnStylePill -> BatteryMonitorService.STYLE_PILL
                else -> BatteryMonitorService.STYLE_BATTERY
            }
            prefs.edit().putInt(BatteryMonitorService.KEY_ICON_STYLE, style).apply()
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
        BatteryStateHolder.setListener { state ->
            runOnUiThread { renderState(state) }
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
    }
}
