package com.example.polarh10activityviewer.ble

import androidx.annotation.MainThread
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarHrData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HeartRateReading(val bpm: Int)

data class HeartRateStatistics(
    val count: Long = 0,
    val sum: Long = 0,
    val min: Int? = null,
    val max: Int? = null
) {
    val average: Double? get() = if (count == 0L) null else sum.toDouble() / count
}

@MainThread
internal class LatestHeartRate {
    private val mutableReading = MutableStateFlow<HeartRateReading?>(null)
    val reading = mutableReading.asStateFlow()
    private val mutableStatistics = MutableStateFlow(HeartRateStatistics())
    val statistics = mutableStatistics.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()

    // Report valid reception separately from the final sample's display state.
    fun receive(batch: PolarHrData): Boolean {
        var receivedValid = false
        var totals = statistics.value
        batch.samples.forEach { sample ->
            val noContact = sample.contactStatusSupported && !sample.contactStatus
            if (sample.hr > 0 && !noContact) {
                receivedValid = true
                totals = HeartRateStatistics(
                    totals.count + 1, totals.sum + sample.hr,
                    minOf(totals.min ?: sample.hr, sample.hr),
                    maxOf(totals.max ?: sample.hr, sample.hr)
                )
                mutableReading.value = HeartRateReading(sample.hr)
                mutableMessage.value = null
            } else {
                mutableReading.value = null
                mutableMessage.value = if (noContact) "No sensor contact" else "Invalid HR sample"
            }
        }
        mutableStatistics.value = totals
        return receivedValid
    }

    fun onSubscriptionState(type: PolarDeviceDataType, status: SubscriptionStatus) {
        if (type == PolarDeviceDataType.HR && status != SubscriptionStatus.RECEIVING) {
            clear()
        }
    }

    fun clear() {
        mutableReading.value = null
        mutableMessage.value = null
    }

    fun reset() {
        clear()
        mutableStatistics.value = HeartRateStatistics()
    }
}
