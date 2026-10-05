package com.example.polarh10activityviewer.session

import com.example.polarh10activityviewer.ble.ConnectionDevice
import com.example.polarh10activityviewer.heartrate.HeartRateStatistics
import com.example.polarh10activityviewer.ble.checkedDataTypes
import com.example.polarh10activityviewer.heartrate.HeartRateZoneState
import com.example.polarh10activityviewer.motion.StepState
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType

internal data class StreamObservation(
    val received: Boolean = false,
    val missing: Boolean = false,
    val failed: Boolean = false
)

// Values are copied from the existing owners, never reconstructed from charts.
internal data class SessionSummary(
    val minimumHr: Int? = null,
    val maximumHr: Int? = null,
    val meanHr: Double? = null,
    val validHrCount: Long = 0,
    val zoneDurationsMs: List<Long> = List(5) { 0L },
    val unclassifiedMs: Long = 0,
    val totalSteps: Long? = null,
    val meanCadence: Double? = null,
    val maximumCadence: Double? = null,
    val activityMetrics: ActivityMetrics = ActivityMetrics()
) {
    val receivedValidHr: Boolean get() = validHrCount > 0

    companion object {
        fun from(hr: HeartRateStatistics, zones: HeartRateZoneState, motion: StepState) = SessionSummary(
            minimumHr = hr.min, maximumHr = hr.max, meanHr = hr.average, validHrCount = hr.count,
            zoneDurationsMs = zones.durationsMs.toList(), unclassifiedMs = zones.unclassifiedMs,
            totalSteps = motion.totalSteps, maximumCadence = motion.maximumCadence,
            meanCadence = motion.meanCadence
        )
    }
}

internal data class SessionRecord(
    val id: String,
    val startRequestedAt: Long,
    val device: ConnectionDevice?,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val durationMs: Long = 0,
    val endReason: String? = null,
    val interrupted: Boolean = false,
    val collectionIncomplete: Boolean = false,
    val summary: SessionSummary = SessionSummary(),
    val streams: Map<PolarDeviceDataType, StreamObservation> = checkedDataTypes.associateWith { StreamObservation() }
) {
    val eligibleForSaving: Boolean get() = startedAt != null && (summary.receivedValidHr ||
        streams.getValue(PolarDeviceDataType.ACC).received || streams.getValue(PolarDeviceDataType.ECG).received)
    val incomplete: Boolean get() = interrupted || !summary.receivedValidHr ||
        streams.values.any { !it.received || it.missing || it.failed }
}

// Finalized summary and copied histories passed to the application save owner.
internal data class SessionSnapshot(
    val record: SessionRecord,
    val hrPoints: List<HrHistoryPoint>,
    val motionPoints: List<MotionHistoryPoint>
)

internal data class HrHistoryPoint(
    val sessionId: String, val secondBucket: Long, val elapsedMs: Long,
    val bpm: Int?, val breakBefore: Boolean
)

internal data class MotionHistoryPoint(
    val sessionId: String, val secondBucket: Long, val elapsedMs: Long,
    val cadence: Double?, val breakBefore: Boolean
)
