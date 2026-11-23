from pathlib import Path
import sys

ROOT_CANDIDATES = [Path("."), Path("BtBattery")]


def find_project_root() -> Path:
    for root in ROOT_CANDIDATES:
        if (root / "app/src/main/AndroidManifest.xml").exists():
            return root
    print("未找到项目目录，请在 BtBattery 或其上一级目录运行。")
    sys.exit(1)


ACCESSIBILITY_CONFIG_XML = """<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowsChanged|typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_desc"
    android:notificationTimeout="30" />
"""

ACCESSIBILITY_SERVICE_KT = """package com.example.btbattery

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class StatusBarAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var lastStatusBarVisible: Boolean = true

        @Volatile
        var lastLeftEdgePx: Int = -1
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        syncNow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != "com.android.systemui") {
                return
            }
        }
        syncNow()
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        isRunning = false
        lastStatusBarVisible = true
        lastLeftEdgePx = -1
        super.onDestroy()
    }

    private fun syncNow() {
        val statusBarWin = findStatusBarWindow()
        val visible = statusBarWin != null

        if (statusBarWin != null) {
            val barBounds = Rect()
            statusBarWin.getBoundsInScreen(barBounds)
            val rootNode = try {
                statusBarWin.root
            } catch (_: Exception) {
                null
            }
            if (rootNode != null) {
                val maxRight = findLeftGroupMaxRight(rootNode, barBounds.bottom)
                safeRecycle(rootNode)
                if (maxRight > 0 && lastLeftEdgePx != maxRight) {
                    lastLeftEdgePx = maxRight
                    BatteryMonitorService.onAccessibilityLeftEdgeChanged(maxRight)
                }
            }
        }

        if (lastStatusBarVisible != visible) {
            lastStatusBarVisible = visible
            BatteryMonitorService.onAccessibilityStatusBarChanged(visible)
        }
    }

    private fun findStatusBarWindow(): AccessibilityWindowInfo? {
        val winList = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return null

        if (winList.isEmpty()) return null

        val minBarWidth = resources.displayMetrics.widthPixels / 2
        val rect = Rect()
        for (w in winList) {
            if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                w.getBoundsInScreen(rect)
                if (rect.top <= 12 && rect.bottom > 0 && rect.width() >= minBarWidth) {
                    return w
                }
            }
        }
        return null
    }

    private fun findLeftGroupMaxRight(root: AccessibilityNodeInfo, barBottom: Int): Int {
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val density = dm.density
        // 限制在居中前摄挖孔左侧区域内，避免误扫到居中灵动胶囊或右侧网速/信号图标
        val maxLeftZonePx = (screenWidth * 0.43f).toInt()
        val maxItemWidthPx = (96f * density).toInt()
        val minItemWidthPx = (4f * density).toInt()
        val minItemHeightPx = (8f * density).toInt()
        val maxBarBottom = if (barBottom > 0) barBottom + (8f * density).toInt() else (56f * density).toInt()

        var maxRight = -1
        var visited = 0
        val rect = Rect()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty() && visited < 160) {
            val (node, depth) = stack.removeLast()
            visited++

            val isRoot = (node === root)
            try {
                if (node.isVisibleToUser && node.packageName?.toString() != packageName) {
                    node.getBoundsInScreen(rect)
                    val childCount = node.childCount
                    val cls = node.className?.toString() ?: ""
                    val isLeafWidget = childCount == 0 && (
                        cls.contains("TextView") ||
                            cls.contains("ImageView") ||
                            (rect.width() in minItemWidthPx..maxItemWidthPx && rect.height() >= minItemHeightPx)
                        )

                    if (isLeafWidget &&
                        rect.top >= 0 &&
                        rect.bottom <= maxBarBottom &&
                        rect.left >= 0 &&
                        rect.right <= maxLeftZonePx &&
                        rect.width() in minItemWidthPx..maxItemWidthPx
                    ) {
                        if (rect.right > maxRight) {
                            maxRight = rect.right
                        }
                    }

                    if (depth < 14 && childCount > 0) {
                        for (i in 0 until childCount) {
                            val child = node.getChild(i)
                            if (child != null) {
                                stack.addLast(child to (depth + 1))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (!isRoot) {
                    safeRecycle(node)
                }
            }
        }
        return maxRight
    }

    private fun safeRecycle(node: AccessibilityNodeInfo) {
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Exception) {
        }
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
        val autoFollow = prefs.getBoolean(KEY_AUTO_FOLLOW_LEFT, true)
        val gapDp = prefs.getInt(KEY_FOLLOW_GAP_DP, 4)

        val yPx = dpToPx(yDp)
        val heightPx = dpToPx(sizeDp)
        val widthPx = if (iconStyle == STYLE_BATTERY) (heightPx * 1.38f).toInt() else heightPx
        val containerHeight = yPx + heightPx
        overlaySlideDistance = containerHeight.toFloat()
        val bitmap = createOverlayGraphic(displayLevel, iconStyle)

        val screenWidth = resources.displayMetrics.widthPixels
        val maxSafeLeftPx = ((screenWidth * 0.43f).toInt() - widthPx).coerceAtLeast(dpToPx(8))
        val targetX = if (autoFollow && StatusBarAccessibilityService.isRunning && StatusBarAccessibilityService.lastLeftEdgePx > 0) {
            (StatusBarAccessibilityService.lastLeftEdgePx + dpToPx(gapDp)).coerceIn(dpToPx(8), maxSafeLeftPx)
        } else {
            dpToPx(xDp)
        }

        if (overlayView == null || currentOverlayX < 0) {
            currentOverlayX = targetX
        }

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
            x = currentOverlayX
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
                if (currentOverlayX != targetX) {
                    animateOverlayXTo(targetX)
                }
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

    fun handleExternalLeftEdgeChanged(rightPx: Int) {
        mainHandler.post {
            if (overlayView != null) {
                updateOverlayView()
            }
        }
    }

    private fun animateOverlayXTo(targetX: Int) {
        val startX = currentOverlayX
        if (startX == targetX) return
        xSlideAnimator?.cancel()
        xSlideAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 140L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { anim ->
                val view = overlayView ?: return@addUpdateListener
                val v = anim.animatedValue as Int
                currentOverlayX = v
                try {
                    val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@addUpdateListener
                    lp.x = v
                    getSystemService(WindowManager::class.java)?.updateViewLayout(view, lp)
                } catch (_: Exception) {
                }
            }
            start()
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
        xSlideAnimator?.cancel()
        xSlideAnimator = null
        currentOverlayX = -1
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


def patch_layout(root: Path):
    p = root / "app/src/main/res/layout/activity_main.xml"
    text = p.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "switchAutoFollow" in text:
        print("[跳过] activity_main.xml（已包含左侧紧随开关）")
        return

    anchor = """                <com.google.android.material.switchmaterial.SwitchMaterial
                    android:id="@+id/switchPreview"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="未连耳机时显示测试图标（方便对位）"
                    android:textColor="@color/text_secondary"
                    android:textSize="13sp" />"""

    addition = anchor + """

                <com.google.android.material.switchmaterial.SwitchMaterial
                    android:id="@+id/switchAutoFollow"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="左侧智能紧随（自动避让微信/充电图标）"
                    android:textColor="@color/text_primary"
                    android:textSize="14sp" />"""

    if anchor not in text:
        print("[失败] activity_main.xml 未找到 switchPreview")
        sys.exit(1)

    p.write_text(text.replace(anchor, addition, 1), encoding="utf-8", newline="\n")
    print("[完成] activity_main.xml")


def patch_main_activity(root: Path):
    p = root / "app/src/main/java/com/example/btbattery/MainActivity.kt"
    text = p.read_text(encoding="utf-8").replace("\r\n", "\n")
    if "switchAutoFollow" in text:
        print("[跳过] MainActivity.kt（已包含左侧紧随控制逻辑）")
        return

    text = text.replace(
        "    private lateinit var switchPreview: SwitchMaterial",
        "    private lateinit var switchPreview: SwitchMaterial\n    private lateinit var switchAutoFollow: SwitchMaterial",
        1,
    )
    text = text.replace(
        "        switchPreview = findViewById(R.id.switchPreview)",
        "        switchPreview = findViewById(R.id.switchPreview)\n        switchAutoFollow = findViewById(R.id.switchAutoFollow)",
        1,
    )

    old_block = """        switchOverlay.isChecked = prefs.getBoolean(BatteryMonitorService.KEY_OVERLAY_ENABLED, true)
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
                    }"""

    new_block = """        switchOverlay.isChecked = prefs.getBoolean(BatteryMonitorService.KEY_OVERLAY_ENABLED, true)
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
                    }"""

    if old_block not in text:
        print("[失败] MainActivity.kt 未匹配到 setupOverlayControls 片段")
        sys.exit(1)

    text = text.replace(old_block, new_block, 1)

    helper_fn = """
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
"""
    last_brace = text.rfind("}")
    text = text[:last_brace] + helper_fn + "}\n"

    p.write_text(text, encoding="utf-8", newline="\n")
    print("[完成] MainActivity.kt")


def patch_monitor_service(root: Path):
    p = root / "app/src/main/java/com/example/btbattery/BatteryMonitorService.kt"
    text = p.read_text(encoding="utf-8").replace("\r\n", "\n")

    text = ensure_import(text, "import android.view.WindowManager", "import android.animation.ValueAnimator")

    if "KEY_AUTO_FOLLOW_LEFT" not in text:
        text = text.replace(
            '        const val KEY_OVERLAY_SIZE = "overlay_size_dp"',
            '        const val KEY_OVERLAY_SIZE = "overlay_size_dp"\n'
            '        const val KEY_AUTO_FOLLOW_LEFT = "overlay_auto_follow_left"\n'
            '        const val KEY_FOLLOW_GAP_DP = "overlay_follow_gap_dp"',
            1,
        )

    if "onAccessibilityLeftEdgeChanged" not in text:
        text = text.replace(
            """        fun onAccessibilityStatusBarChanged(visible: Boolean) {
            instance?.handleExternalStatusBarVisibility(visible)
        }""",
            """        fun onAccessibilityStatusBarChanged(visible: Boolean) {
            instance?.handleExternalStatusBarVisibility(visible)
        }

        fun onAccessibilityLeftEdgeChanged(rightPx: Int) {
            instance?.handleExternalLeftEdgeChanged(rightPx)
        }""",
            1,
        )

    if "currentOverlayX" not in text:
        text = text.replace(
            "    private var isStatusBarVisible: Boolean = true",
            "    private var isStatusBarVisible: Boolean = true\n"
            "    private var currentOverlayX: Int = -1\n"
            "    private var xSlideAnimator: ValueAnimator? = null",
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

    (root / "app/src/main/res/xml/accessibility_service_config.xml").write_text(
        ACCESSIBILITY_CONFIG_XML, encoding="utf-8", newline="\n"
    )
    (root / "app/src/main/java/com/example/btbattery/StatusBarAccessibilityService.kt").write_text(
        ACCESSIBILITY_SERVICE_KT, encoding="utf-8", newline="\n"
    )
    print("[完成] 更新无障碍坐标追踪服务")

    patch_layout(root)
    patch_main_activity(root)
    patch_monitor_service(root)
    print("\n全部修改完成，可以推送编译。")


if __name__ == "__main__":
    main()