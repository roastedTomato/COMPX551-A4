package com.example.polarh10activityviewer.ble

import androidx.annotation.MainThread
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SubscriptionStatus { IDLE, STARTING, RECEIVING, STOPPING, STOPPED, FAILED }

internal data class SubscriptionState(
    val status: SubscriptionStatus = SubscriptionStatus.IDLE,
    val error: String? = null
)

// Confined to the main thread by the manager; tests use a single coroutine test scheduler.
@MainThread
internal class DataSubscriptions(
    private val scope: CoroutineScope,
    private val onStateChanged: (PolarDeviceDataType, SubscriptionStatus) -> Unit = { _, _ -> }
) {
    private class Task {
        lateinit var job: Job
        var stopping = false
        var error: String? = null
    }

    private val tasks = mutableMapOf<PolarDeviceDataType, Task>()
    private val mutableStates = MutableStateFlow(checkedDataTypes.associateWith { SubscriptionState() })
    val states = mutableStates.asStateFlow()

    fun isActive(type: PolarDeviceDataType) = type in tasks

    fun reset() {
        check(tasks.isEmpty())
        mutableStates.value = checkedDataTypes.associateWith { SubscriptionState() }
    }

    fun unavailable(type: PolarDeviceDataType, reason: String) {
        if (type in checkedDataTypes && !isActive(type)) setState(type, SubscriptionStatus.IDLE, reason)
    }

    private fun setState(type: PolarDeviceDataType, status: SubscriptionStatus, error: String? = null) {
        mutableStates.value = mutableStates.value + (type to SubscriptionState(status, error))
        onStateChanged(type, status)
    }

    fun <T> start(
        type: PolarDeviceDataType,
        canStart: () -> Boolean,
        isCurrent: () -> Boolean,
        stream: suspend () -> Flow<T>,
        onData: (T) -> Unit
    ): Boolean {
        if (type !in checkedDataTypes || type in tasks || !isCurrent() || !canStart()) return false
        val task = Task()
        fun acceptsEvents() = tasks[type] === task && !task.stopping && isCurrent()
        task.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!isCurrent() || !canStart()) return@launch
                stream().collect { data ->
                    ensureActive()
                    if (acceptsEvents()) {
                        setState(type, SubscriptionStatus.RECEIVING)
                        onData(data)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (acceptsEvents()) {
                    task.error = "$type stream failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
        tasks[type] = task
        setState(type, SubscriptionStatus.STARTING)
        task.job.invokeOnCompletion {
            // Completion includes child jobs and cleanup, even if cancelled before launch.
            scope.launch {
                if (tasks[type] === task) {
                    tasks.remove(type)
                    val error = task.error.takeIf { !task.stopping && isCurrent() }
                    setState(type, if (error == null) SubscriptionStatus.STOPPED else SubscriptionStatus.FAILED, error)
                }
            }
        }
        task.job.start()
        return true
    }

    fun stop(type: PolarDeviceDataType) {
        val task = tasks[type] ?: return
        if (task.stopping) return
        task.stopping = true
        setState(type, SubscriptionStatus.STOPPING)
        // Keep ownership until completion so another start cannot overlap cleanup.
        task.job.cancel()
    }

    fun stopAll() {
        tasks.keys.toList().forEach(::stop)
    }
}
