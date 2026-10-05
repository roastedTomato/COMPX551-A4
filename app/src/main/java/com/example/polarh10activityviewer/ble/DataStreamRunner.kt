package com.example.polarh10activityviewer.ble

import androidx.annotation.MainThread
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import kotlinx.coroutines.flow.Flow

/** Connects one SDK stream to the subscription owner without knowing its business meaning. */
@MainThread
internal class DataStreamRunner(
    private val subscriptions: DataSubscriptions,
    private val nowElapsed: () -> Long,
    private val nowWall: () -> Long,
    private val isCurrent: (PolarBleApi, String) -> Boolean,
    private val sessionCanReceive: () -> Boolean,
    private val shouldAcceptAt: (Long) -> Boolean
) {
    fun <T> start(
        type: PolarDeviceDataType,
        source: PolarBleApi,
        identifier: String,
        stream: suspend (PolarBleApi, String) -> Flow<T>,
        onData: (T, Long, Long) -> Unit
    ): Boolean {
        return subscriptions.start(
            type,
            canStart = { sessionCanReceive() },
            isCurrent = { sessionCanReceive() && isCurrent(source, identifier) },
            stream = { stream(source, identifier) },
            onData = { data ->
                val receivedAt = nowElapsed()
                val receivedDate = nowWall()
                if (shouldAcceptAt(receivedAt) && sessionCanReceive()) {
                    onData(data, receivedAt, receivedDate)
                }
            }
        )
    }
}
