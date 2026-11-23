from pathlib import Path
import sys

ROOT_CANDIDATES = [Path("."), Path("BtBattery")]


def find_project_root() -> Path:
    for root in ROOT_CANDIDATES:
        if (root / "app/src/main/AndroidManifest.xml").exists():
            return root
    print("未找到项目目录，请在 BtBattery 或其上一级目录运行。")
    sys.exit(1)


STRINGS_XML = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="accessibility_service_desc">实时检测系统状态栏窗口显隐，使顶部耳机电量贴片与状态栏同步出现和隐藏（不读取任何屏幕文字内容）。</string>
</resources>
"""

ACCESSIBILITY_CONFIG_XML = """<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowsChanged|typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_desc"
    android:notificationTimeout="0" />
"""

ACCESSIBILITY_SERVICE_KT = """package com.example.btbattery

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class StatusBarAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var lastStatusBarVisible: Boolean = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        syncNow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        syncNow()
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        isRunning = false
        lastStatusBarVisible = true
        super.onDestroy()
    }

    private fun syncNow() {
        val visible = detectStatusBarVisible()
        if (lastStatusBarVisible != visible) {
            lastStatusBarVisible = visible
            BatteryMonitorService.onAccessibilityStatusBarChanged(visible)
        }
    }

    private fun detectStatusBarVisible(): Boolean {
        val winList = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return true

        if (winList.isEmpty()) return true

        val minBarWidth = resources.displayMetrics.widthPixels / 2
        val rect = Rect()
        for (w in winList) {
            if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                w.getBoundsInScreen(rect)
                if (rect.top <= 12 && rect.bottom > 0 && rect.width() >= minBarWidth) {
                    return true
                }
            }
        }
        return false
    }
}
"""

OVERLAY_METHODS_KT = r"""    private fun updateOverlayView() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        val preview = prefs.getBoolean(KEY_OVERLAY_PREVIEW, false)
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val displayLevel = if (currentConnectedLevel in 0..100) currentConnectedLevel else if (preview) 70 else -1

        if (!enabled || !Settings.canDrawOverlays(this) || !isPortrait || displayLevel !in 0..100) {
            removeOverlayView()
            return
        }

        if (StatusBarAccessibilityService.isRunning) {
            isStatusBarVisible = StatusBarAccessibilityService.lastStatusBarVisible
        }

        val iconStyle = getIconStyle()
        val xDp = prefs.getInt(KEY_OVERLAY_X, 64)
        val yDp = prefs.getInt(KEY_OVERLAY_Y, 12)
        val sizeDp = prefs.getInt(KEY_OVERLAY_SIZE, 20)

        val yPx = dpToPx(yDp)
        val heightPx = dpToPx(sizeDp)
        val widthPx = if (iconStyle == STYLE_BATTERY) (heightPx * 1.38f).toInt() else heightPx
        val containerHeight = yPx + heightPx
        overlaySlideDistance = containerHeight.toFloat()
        val bitmap = createOverlayGraphic(displayLevel, iconStyle)

        val params = WindowManager.LayoutParams(
            widthPx,
            containerHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(xDp)
            y = 0
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
                    translationY = if (isStatusBarVisible) 0f else -overlaySlideDistance
                    alpha = if (isStatusBarVisible) 1f else 0f
                }
                val container = FrameLayout(this).apply {
                    addView(
                        iv,
                        FrameLayout.LayoutParams(widthPx, heightPx).apply {
                            topMargin = yPx
                        }
                    )
                    setOnApplyWindowInsetsListener { _, insets ->
                        if (!StatusBarAccessibilityService.isRunning) {
                            val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                insets.isVisible(WindowInsets.Type.statusBars())
                            } else {
                                @Suppress("DEPRECATION")
                                insets.systemWindowInsetTop > 0
                            }
                            if (isStatusBarVisible != visible) {
                                isStatusBarVisible = visible
                                animateOverlayBarState(iv, visible)
                            }
                        }
                        insets
                    }
                }
                wm.addView(container, params)
                container.requestApplyInsets()
                overlayView = container
                overlayIconView = iv
            } else {
                overlayIconView?.let { iv ->
                    iv.setImageBitmap(bitmap)
                    iv.layoutParams = FrameLayout.LayoutParams(widthPx, heightPx).apply {
                        topMargin = yPx
                    }
                    iv.translationY = if (isStatusBarVisible) 0f else -overlaySlideDistance
                    iv.alpha = if (isStatusBarVisible) 1f else 0f
                }
                wm.updateViewLayout(overlayView, params)
            }
        } catch (_: Exception) {
            removeOverlayView()
        }
    }

    fun handleExternalStatusBarVisibility(visible: Boolean) {
        mainHandler.post {
            if (isStatusBarVisible != visible) {
                isStatusBarVisible = visible
                overlayIconView?.let { iv ->
                    animateOverlayBarState(iv, visible)
                }
            }
        }
    }

    private fun animateOverlayBarState(iv: ImageView, visible: Boolean) {
        iv.animate().cancel()
        val targetY = if (visible) 0f else -overlaySlideDistance
        val targetAlpha = if (visible) 1f else 0f
        val duration = if (StatusBarAccessibilityService.isRunning) {
            if (visible) 55L else 45L
        } else {
            if (visible) 75L else 60L
        }
        iv.animate()
            .translationY(targetY)
            .alpha(targetAlpha)
            .setDuration(duration)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    private fun removeOverlayView() {
        val view = overlayView ?: return
        overlayView = null
        overlayIconView = null
        isInsetsAnimating = false
        isStatusBarVisible = true
        try {
            getSystemService(WindowManager::class.java)?.removeView(view)
        } catch (_: Exception) {
        }
    }

"""


def ensure_import(text: str, anchor: str, imp: str) -> str:
    if imp in text:
        return text
    return text.replace(anchor, f"{anchor}\n{imp}", 1)


def patch_manifest(root: Path):
    p = root / "app/src/main/AndroidManifest.xml"
    text = p.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "StatusBarAccessibilityService" in text:
        print("[跳过] AndroidManifest.xml（已注册无障碍服务）")
        return

    anchor = """        <service
            android:name=".BatteryMonitorService"
            android:exported="false"
            android:stopWithTask="false"
            android:foregroundServiceType="connectedDevice" />"""

    addition = anchor + """

        <service
            android:name=".StatusBarAccessibilityService"
            android:exported="true"
            android:label="耳机电量状态栏同步"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>"""

    if anchor not in text:
        print("[失败] AndroidManifest.xml 未找到 BatteryMonitorService 声明")
        sys.exit(1)

    p.write_text(text.replace(anchor, addition, 1), encoding="utf-8", newline="\n")
    print("[完成] AndroidManifest.xml")


def patch_layout(root: Path):
    p = root / "app/src/main/res/layout/activity_main.xml"
    text = p.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "btnAccessibilityPerm" in text:
        print("[跳过] activity_main.xml（已包含无障碍按钮）")
        return

    anchor = """                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnOverlayPerm"
                    style="@style/Widget.Material3.Button.TonalButton"
                    android:layout_width="match_parent"
                    android:layout_height="42dp"
                    android:layout_marginTop="10dp"
                    android:text="授予悬浮窗权限"
                    app:cornerRadius="12dp" />"""

    addition = anchor + """

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnAccessibilityPerm"
                    style="@style/Widget.Material3.Button.OutlinedButton"
                    android:layout_width="match_parent"
                    android:layout_height="42dp"
                    android:layout_marginTop="8dp"
                    android:text="开启无障碍同步（毫秒级跟随状态栏）"
                    app:cornerRadius="12dp" />"""

    if anchor not in text:
        print("[失败] activity_main.xml 未找到 btnOverlayPerm")
        sys.exit(1)

    p.write_text(text.replace(anchor, addition, 1), encoding="utf-8", newline="\n")
    print("[完成] activity_main.xml")


def patch_main_activity(root: Path):
    p = root / "app/src/main/java/com/example/btbattery/MainActivity.kt"
    text = p.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "btnAccessibilityPerm" in text:
        print("[跳过] MainActivity.kt（已包含无障碍逻辑）")
        return

    text = text.replace(
        "    private lateinit var btnOverlayPerm: MaterialButton",
        "    private lateinit var btnOverlayPerm: MaterialButton\n    private lateinit var btnAccessibilityPerm: MaterialButton",
        1,
    )
    text = text.replace(
        "        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)",
        "        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)\n        btnAccessibilityPerm = findViewById(R.id.btnAccessibilityPerm)",
        1,
    )
    text = text.replace(
        """        val overlayGranted = Settings.canDrawOverlays(this)
        btnOverlayPerm.text = if (overlayGranted) "✓ 已获得悬浮窗权限" else "授予悬浮窗权限（顶部显示必开）\"""",
        """        val overlayGranted = Settings.canDrawOverlays(this)
        btnOverlayPerm.text = if (overlayGranted) "✓ 已获得悬浮窗权限" else "授予悬浮窗权限（顶部显示必开）"

        val accEnabled = StatusBarAccessibilityService.isRunning || isAccessibilityServiceEnabled()
        btnAccessibilityPerm.text = if (accEnabled) "✓ 已开启无障碍状态栏同步" else "开启无障碍同步（毫秒级跟随状态栏）\"""",
        1,
    )
    text = text.replace(
        """        btnOverlayPerm.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }""",
        """        btnOverlayPerm.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnAccessibilityPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }""",
        1,
    )

    helper_fn = """
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${StatusBarAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }
"""
    last_brace = text.rfind("}")
    text = text[:last_brace] + helper_fn + "}\n"

    p.write_text(text, encoding="utf-8", newline="\n")
    print("[完成] MainActivity.kt")


def patch_monitor_service(root: Path):
    p = root / "app/src/main/java/com/example/btbattery/BatteryMonitorService.kt"
    text = p.read_text(encoding="utf-8").replace("\r\n", "\n")

    text = ensure_import(text, "import android.view.WindowManager", "import android.view.WindowInsets")
    text = ensure_import(text, "import android.view.WindowInsets", "import android.view.animation.PathInterpolator")
    text = ensure_import(text, "import android.widget.ImageView", "import android.widget.FrameLayout")

    if "fun onAccessibilityStatusBarChanged" not in text:
        text = text.replace(
            "        const val STYLE_BATTERY = 2",
            """        const val STYLE_BATTERY = 2

        @Volatile
        var instance: BatteryMonitorService? = null

        fun onAccessibilityStatusBarChanged(visible: Boolean) {
            instance?.handleExternalStatusBarVisibility(visible)
        }""",
            1,
        )

    if "instance = this" not in text:
        text = text.replace(
            "    override fun onCreate() {\n        super.onCreate()",
            "    override fun onCreate() {\n        super.onCreate()\n        instance = this",
            1,
        )

    if "if (instance === this) instance = null" not in text:
        text = text.replace(
            "    override fun onDestroy() {\n        removeOverlayView()",
            "    override fun onDestroy() {\n        if (instance === this) instance = null\n        removeOverlayView()",
            1,
        )

    if "private var overlayView: ImageView? = null" in text:
        text = text.replace(
            "    private var overlayView: ImageView? = null",
            "    private var overlayView: FrameLayout? = null\n    private var overlayIconView: ImageView? = null\n    private var overlaySlideDistance: Float = 0f\n    private var isInsetsAnimating: Boolean = false",
            1,
        )

    if "isStatusBarVisible: Boolean" not in text:
        text = text.replace(
            "    private var currentConnectedLevel: Int = -1",
            "    private var currentConnectedLevel: Int = -1\n    private var isStatusBarVisible: Boolean = true",
            1,
        )

    start_idx = text.find("    private fun updateOverlayView() {")
    end_idx = text.find("    private fun dpToPx(dp: Int): Int")
    if start_idx == -1 or end_idx == -1 or end_idx <= start_idx:
        print("[失败] BatteryMonitorService.kt 未找到 updateOverlayView / dpToPx 边界")
        sys.exit(1)

    text = text[:start_idx] + OVERLAY_METHODS_KT + text[end_idx:]
    p.write_text(text, encoding="utf-8", newline="\n")
    print("[完成] BatteryMonitorService.kt")


def main():
    root = find_project_root()

    (root / "app/src/main/res/values/strings.xml").write_text(STRINGS_XML, encoding="utf-8", newline="\n")
    xml_dir = root / "app/src/main/res/xml"
    xml_dir.mkdir(parents=True, exist_ok=True)
    (xml_dir / "accessibility_service_config.xml").write_text(
        ACCESSIBILITY_CONFIG_XML, encoding="utf-8", newline="\n"
    )
    (root / "app/src/main/java/com/example/btbattery/StatusBarAccessibilityService.kt").write_text(
        ACCESSIBILITY_SERVICE_KT, encoding="utf-8", newline="\n"
    )
    print("[完成] 新增无障碍服务与配置文件")

    patch_manifest(root)
    patch_layout(root)
    patch_main_activity(root)
    patch_monitor_service(root)
    print("\n全部改造完成，可以直接推送编译。")


if __name__ == "__main__":
    main()