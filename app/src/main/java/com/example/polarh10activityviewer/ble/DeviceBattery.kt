package com.example.polarh10activityviewer.ble

import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// SDK instances are compared only by identity; this state needs no Android calls.
@MainThread
internal class DeviceBattery {
    private val mutableLevel = MutableStateFlow<Int?>(null)
    val level = mutableLevel.asStateFlow()

    fun receive(source: Any, currentSdk: Any?, identifier: String, connection: ConnectionState, value: Int) {
        if (source === currentSdk && connection.status == ConnectionStatus.CONNECTED &&
            connection.device?.deviceId == identifier && value in 0..100) {
            mutableLevel.value = value
        }
    }

    fun clear() {
        mutableLevel.value = null
    }
}
