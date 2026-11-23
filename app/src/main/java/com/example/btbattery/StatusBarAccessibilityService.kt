package com.example.btbattery

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
