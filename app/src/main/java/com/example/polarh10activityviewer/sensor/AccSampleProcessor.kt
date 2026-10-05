package com.example.polarh10activityviewer.sensor

import com.example.polarh10activityviewer.ble.SubscriptionStatus

import androidx.annotation.MainThread
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarAccelerometerData

// Raw mG values and the sensor timestamp in nanoseconds. A gap begins a new segment.
data class AccSample(
    val timeStamp: Long,
    val x: Int,
    val y: Int,
    val z: Int,
    val gapBeforeNs: Long? = null
)

@MainThread
internal class AccSampleProcessor(private val onSample: (AccSample) -> Unit = {}) {
    private var previousTimeStamp: Long? = null

    fun onSubscriptionState(type: PolarDeviceDataType, status: SubscriptionStatus) {
        if (type == PolarDeviceDataType.ACC && status == SubscriptionStatus.STARTING) {
            clear()
        }
    }

    fun clear() {
        previousTimeStamp = null
    }

    fun receive(batch: PolarAccelerometerData) {
        batch.samples.forEach { sample ->
            val gap = previousTimeStamp?.let { sample.timeStamp - it }?.takeIf { it > 30_000_000L }
            val raw = AccSample(sample.timeStamp, sample.x, sample.y, sample.z, gap)
            onSample(raw)
            previousTimeStamp = sample.timeStamp
        }
    }
}
