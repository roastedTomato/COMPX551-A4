package com.example.polarh10activityviewer.heartrate


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class HeartRateZone(val label: String, val range: String, val minimumBpm: Int) {
    VERY_LIGHT("Very light", "<110", Int.MIN_VALUE), LIGHT("Light", "110–124", 110),
    MODERATE("Moderate", "125–139", 125), HIGH("High", "140–154", 140), VERY_HIGH("Very high", "≥155", 155);

    companion object {
        // The caller supplies the existing HR owner's validated final reading.
        fun from(bpm: Int): HeartRateZone = entries.last { bpm >= it.minimumBpm }
    }
}

internal data class HeartRateZoneState(
    val durationsMs: List<Long> = List(5) { 0L },
    val current: HeartRateZone? = null,
    val unclassifiedMs: Long = 0,
    val receivedValidHr: Boolean = false
)

// All times are session elapsed milliseconds derived from the monotonic clock.
internal class HeartRateZones {
    private val totals = LongArray(5)
    private var current: HeartRateZone? = null
    private var anchor: Long? = null
    private val mutableState = MutableStateFlow(HeartRateZoneState())
    val state = mutableState.asStateFlow()

    fun reset() {
        totals.fill(0)
        current = null
        anchor = null
        mutableState.value = HeartRateZoneState()
    }

    fun receive(reading: HeartRateReading?, receivedValid: Boolean, elapsedMs: Long) {
        settle(elapsedMs)
        current = reading?.let { HeartRateZone.from(it.bpm) }
        anchor = elapsedMs.takeIf { current != null }
        mutableState.value = state.value.copy(receivedValidHr = state.value.receivedValidHr || receivedValid)
        refresh(elapsedMs)
    }

    fun clearCurrent(elapsedMs: Long) {
        settle(elapsedMs)
        current = null
        anchor = null
        refresh(elapsedMs)
    }

    private fun settle(elapsedMs: Long) {
        current?.let { totals[it.ordinal] += elapsedMs - anchor!! }
    }

    fun refresh(elapsedMs: Long) {
        val displayed = totals.toMutableList()
        current?.let { displayed[it.ordinal] += elapsedMs - anchor!! }
        // A display snapshot never commits its unsettled interval to totals.
        mutableState.value = state.value.copy(
            durationsMs = displayed, current = current,
            unclassifiedMs = elapsedMs - displayed.sum()
        )
    }
}
