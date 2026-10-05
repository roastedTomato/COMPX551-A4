package com.example.polarh10activityviewer.history

import com.example.polarh10activityviewer.heartrate.HeartRateReading
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.session.HrHistoryPoint

// Owned by the retained manager; only accepted nonempty HR events add records.
internal class HrHistory {
    private val points = mutableListOf<HrHistoryPoint>()
    var sessionId: String? = null
        private set
    var frozen = false
        private set
    private var previousElapsedMs: Long? = null
    private var breakBefore = true
    private var continuingAfterPause = false

    fun reset(sessionId: String? = null) {
        points.clear()
        previousElapsedMs = null
        breakBefore = true
        continuingAfterPause = false
        this.sessionId = sessionId
        frozen = false
    }

    fun onSubscriptionState(status: SubscriptionStatus) {
        if (frozen || status == SubscriptionStatus.RECEIVING) return
        if (status == SubscriptionStatus.STARTING && continuingAfterPause) return
        continuingAfterPause = false
        breakBefore = true
    }

    fun receive(elapsedMs: Long, reading: HeartRateReading?) {
        val sessionId = sessionId ?: return
        if (frozen || elapsedMs < 0) return
        if (elapsedMs > MAX_ELAPSED_MS) return
        val bucket = elapsedMs / 1000
        val replaced = points.lastOrNull()?.takeIf { it.secondBucket == bucket }
        if (replaced == null && points.size == MAX_POINTS) return
        val gap = previousElapsedMs?.let { elapsedMs - it > 3000 } ?: false
        val point = HrHistoryPoint(sessionId, bucket, elapsedMs, reading?.bpm,
            breakBefore || gap || reading == null || replaced?.breakBefore == true)
        if (replaced == null) points.add(point) else points[points.lastIndex] = point
        previousElapsedMs = elapsedMs
        continuingAfterPause = false
        breakBefore = reading == null
    }

    fun stop() {
        if (sessionId != null) frozen = true
    }

    fun resume(connectPrevious: Boolean = true) {
        continuingAfterPause = connectPrevious && !breakBefore && points.lastOrNull()?.bpm != null
        breakBefore = !continuingAfterPause
        frozen = false
    }

    fun since(bucket: Long): List<HrHistoryPoint> = points.takeLastWhile { it.secondBucket >= bucket }

    fun snapshot(): List<HrHistoryPoint> = points.toList()

    companion object {
        const val MAX_ELAPSED_MS = 14_400_000L
        const val MAX_POINTS = 14_401
    }
}
