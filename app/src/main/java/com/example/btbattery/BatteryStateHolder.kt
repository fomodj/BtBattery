package com.example.btbattery

data class PairedDeviceInfo(
    val name: String,
    val address: String,
    val connected: Boolean,
    val battery: Int
)

data class BatteryUiState(
    val serviceRunning: Boolean = false,
    val deviceName: String = "未检测到已连接耳机",
    val deviceSubtitle: String = "请确认蓝牙耳机已连接",
    val batteryLevel: Int = -1,
    val diagnosticLog: String = "等待刷新...",
    val pairedDevices: List<PairedDeviceInfo> = emptyList()
)

object BatteryStateHolder {
    @Volatile
    var currentState: BatteryUiState = BatteryUiState()
        private set

    private var listener: ((BatteryUiState) -> Unit)? = null

    fun update(state: BatteryUiState) {
        currentState = state
        listener?.invoke(state)
    }

    fun setListener(cb: ((BatteryUiState) -> Unit)?) {
        listener = cb
        cb?.invoke(currentState)
    }
}
