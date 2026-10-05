package com.example.polarh10activityviewer.motion

import kotlin.math.max

internal data class StepCandidate(val timeStamp: Long, val peak: Double)

// Candidates are not confirmed steps. All timing uses sensor nanoseconds.
internal class StepCandidateDetector {
    private data class Cycle(
        val startedAt: Long,
        val low: Double,
        var peak: StepCandidate
    )

    private var previousS: Double? = null
    private var cycle: Cycle? = null
    var latestCandidate: StepCandidate? = null
        private set

    fun clear() {
        previousS = null
        cycle = null
        latestCandidate = null
    }

    fun receive(sample: PreparedAcc): StepCandidate? {
        val current = sample.smoothed ?: return null
        val previous = previousS
        previousS = current
        val mean = sample.previousMean ?: return null
        val deviation = sample.previousStdDev ?: return null
        val active = cycle
        if (active == null) {
            val high = mean + max(deviation, 0.5)
            if (previous != null && previous <= high && current > high) {
                cycle = Cycle(sample.timeStamp, mean, StepCandidate(sample.timeStamp, current))
            }
            return null
        }
        // A late fall cannot confirm an expired cycle, and timeout does not extend on new peaks.
        if (sample.timeStamp - active.startedAt > 2_000_000_000L) {
            cycle = null
            return null
        }
        if (current > active.peak.peak) active.peak = StepCandidate(sample.timeStamp, current)
        if (current <= active.low) {
            cycle = null
            latestCandidate = active.peak
            return active.peak
        }
        return null
    }
}
