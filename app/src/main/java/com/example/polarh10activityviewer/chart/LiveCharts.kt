package com.example.polarh10activityviewer.chart

import com.example.polarh10activityviewer.ble.checkedDataTypes
import com.example.polarh10activityviewer.heartrate.HeartRateReading
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.motion.StepState

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.*
import com.polar.sdk.api.model.EcgSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ChartKind(val type: PolarDeviceDataType, val label: String, val unit: String) {
    HEART_RATE(HR, "Heart rate", "bpm"), CADENCE(ACC, "Cadence", "steps/min"),
    ELECTROCARDIOGRAM(ECG, "ECG", "µV")
}

internal data class ChartPoint(val elapsedMs: Double, val value: Double?, val breakBefore: Boolean)
internal data class ChartSnapshot(
    val points: List<ChartPoint>, val endMs: Double, val windowMs: Double, val status: SubscriptionStatus,
    val detectingSteps: Boolean = false
) {
    val startMs: Double get() = (endMs - windowMs).coerceAtLeast(0.0)
}

// Display records only. ECG reads the existing raw buffer without another retained copy.
internal class LiveCharts(private val ecgSamples: () -> List<EcgSample>) {
    private val hr = ArrayDeque<ChartPoint>()
    private val motion = ArrayDeque<ChartPoint>()
    private val statuses = checkedDataTypes.associateWith { SubscriptionStatus.IDLE }.toMutableMap()
    private val frozenEnds = checkedDataTypes.associateWith { 0L }.toMutableMap()
    private var previousHrTime: Long? = null
    private var hrBreak = true
    private var previousMotionSegment: Long? = null
    private var motionBreak = false
    private var continueHr = false
    private var continueMotion = false
    private var resumeMotionSegment: Long? = null
    private var detectingSteps = false
    private var ecgSensorAnchor: Long? = null
    private var ecgSessionAnchor = 0L
    private var ecgSampleRate = 0
    private val preserveOnStart = mutableSetOf<PolarDeviceDataType>()
    private val mutableSelection = MutableStateFlow(ChartKind.HEART_RATE)
    val selection = mutableSelection.asStateFlow()

    fun select(kind: ChartKind) {
        mutableSelection.value = kind
    }

    private fun active(type: PolarDeviceDataType) = statuses[type] in
        listOf(SubscriptionStatus.STARTING, SubscriptionStatus.RECEIVING)

    private fun clear(type: PolarDeviceDataType) {
        when (type) {
            HR -> { hr.clear(); previousHrTime = null; hrBreak = true; continueHr = false }
            ACC -> { motion.clear(); previousMotionSegment = null; motionBreak = false; continueMotion = false; resumeMotionSegment = null; detectingSteps = false }
            ECG -> { ecgSensorAnchor = null; ecgSessionAnchor = 0; ecgSampleRate = 0 }
            else -> Unit
        }
    }

    fun reset() {
        preserveOnStart.clear()
        checkedDataTypes.forEach {
            clear(it)
            statuses[it] = SubscriptionStatus.IDLE
            frozenEnds[it] = 0L
        }
    }

    fun onSubscriptionState(type: PolarDeviceDataType, status: SubscriptionStatus, elapsedMs: Long,
        motionSegment: Long? = null) {
        if (status == SubscriptionStatus.STARTING && !preserveOnStart.remove(type)) clear(type)
        if (type == ACC && status == SubscriptionStatus.STARTING && continueMotion) resumeMotionSegment = motionSegment
        if (active(type) || status == SubscriptionStatus.STARTING) frozenEnds[type] = elapsedMs
        if (type == HR && status != SubscriptionStatus.RECEIVING &&
            !(status == SubscriptionStatus.STARTING && continueHr)) {
            hrBreak = true
            continueHr = false
        }
        if (type == ACC && status !in listOf(SubscriptionStatus.STARTING, SubscriptionStatus.RECEIVING)) {
            continueMotion = false
        }
        statuses[type] = status
        trim()
    }

    fun stop(elapsedMs: Long) {
        checkedDataTypes.filter(::active).forEach {
            onSubscriptionState(it, SubscriptionStatus.STOPPED, elapsedMs)
        }
    }

    fun resume(connectHr: Boolean = true, connectMotion: Boolean = true) {
        preserveOnStart.addAll(listOf(HR, ACC))
        continueHr = connectHr && hr.lastOrNull()?.value != null
        hrBreak = !continueHr
        val last = motion.lastOrNull()
        continueMotion = connectMotion && !motionBreak && last?.value != null
        resumeMotionSegment = null
        if (!continueMotion) previousMotionSegment = null
    }

    fun receiveHr(elapsedMs: Long, reading: HeartRateReading?) {
        if (!active(HR)) return
        val sameBucket = hr.lastOrNull()?.takeIf { it.elapsedMs.toLong() / 1000 == elapsedMs / 1000 }
        if (sameBucket != null) hr.removeLast()
        val gap = previousHrTime?.let { elapsedMs - it > 3000 } ?: false
        val broken = hrBreak || gap || reading == null || sameBucket?.breakBefore == true ||
            (sameBucket != null && sameBucket.value == null)
        hr.addLast(ChartPoint(elapsedMs.toDouble(), reading?.bpm?.toDouble(), broken))
        previousHrTime = elapsedMs
        continueHr = false
        hrBreak = reading == null
        frozenEnds[HR] = elapsedMs
        trim()
    }

    // Called only by the existing 250 ms calculation/refresh entry point.
    fun recordMotion(elapsedMs: Long, state: StepState, warmingUp: Boolean, segment: Long) {
        detectingSteps = state.cadencePending
        if (!active(ACC)) return
        if (continueMotion) {
            if (resumeMotionSegment == null) resumeMotionSegment = segment
            if (resumeMotionSegment != segment) {
                continueMotion = false
            } else if (warmingUp || state.cadencePending) {
                return
            }
        }
        val cadence = state.cadence.takeUnless { warmingUp }
        val previous = motion.lastOrNull()
        // Retain gaps observed between the 250 ms display samples.
        motionBreak = motionBreak || (!continueMotion && previousMotionSegment != segment) ||
            cadence == null || previous?.value == null
        if (previous != null && elapsedMs - previous.elapsedMs.toLong() < 250) return
        motion.addLast(ChartPoint(elapsedMs.toDouble(), cadence, motionBreak))
        motionBreak = false
        previousMotionSegment = segment
        continueMotion = false
        frozenEnds[ACC] = elapsedMs
        trim()
    }

    fun receiveEcg(samples: List<EcgSample>, elapsedMs: Long, sampleRate: Int) {
        if (!active(ECG) || samples.isEmpty()) return
        if (ecgSensorAnchor == null) {
            ecgSensorAnchor = samples.last().timeStamp
            ecgSessionAnchor = elapsedMs
            ecgSampleRate = sampleRate
        }
        frozenEnds[ECG] = elapsedMs
    }

    fun advance(elapsedMs: Long) {
        checkedDataTypes.filter(::active).forEach { frozenEnds[it] = elapsedMs }
        trim()
    }

    private fun trim() {
        val hrCutoff = frozenEnds.getValue(HR) - 300_000
        while (hr.isNotEmpty() && (hr.first().elapsedMs <= hrCutoff || hr.size > 301)) hr.removeFirst()
        val motionCutoff = frozenEnds.getValue(ACC) - 300_000
        while (motion.isNotEmpty() && (motion.first().elapsedMs <= motionCutoff || motion.size > 1201)) motion.removeFirst()
    }

    fun snapshot(kind: ChartKind, elapsedMs: Long): ChartSnapshot {
        val end = (if (active(kind.type)) elapsedMs else frozenEnds.getValue(kind.type)).toDouble()
        val window = if (kind.type == ECG) 5000.0 else 300_000.0
        fun visible(time: Double) = time >= 0 && time > end - window && time <= end
        val points = when (kind) {
            ChartKind.HEART_RATE -> hr.filter { visible(it.elapsedMs) }
            ChartKind.CADENCE -> motion.filter { visible(it.elapsedMs) }
            ChartKind.ELECTROCARDIOGRAM -> {
                val anchor = ecgSensorAnchor
                if (anchor == null) emptyList() else {
                    var previous: Long? = null
                    ecgSamples().mapNotNull { sample ->
                        val time = ecgSessionAnchor + (sample.timeStamp - anchor) / 1_000_000.0
                        val broken = previous?.let { (sample.timeStamp - it).toDouble() * ecgSampleRate > 3_000_000_000.0 } ?: true
                        previous = sample.timeStamp
                        if (visible(time)) ChartPoint(time, sample.voltage.toDouble(), broken) else null
                    }
                }
            }
        }
        return ChartSnapshot(points, end, window, statuses.getValue(kind.type),
            kind.type == ACC && active(ACC) && detectingSteps)
    }
}
