package com.example.btbattery

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
