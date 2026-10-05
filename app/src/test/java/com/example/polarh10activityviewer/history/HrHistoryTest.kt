package com.example.polarh10activityviewer.history

import com.example.polarh10activityviewer.heartrate.HeartRateReading
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import org.junit.Assert.*
import org.junit.Test

class HrHistoryTest {
    private fun HrHistory.receive(at: Long, bpm: Int = 120) = receive(at, HeartRateReading(bpm))
    private fun history() = HrHistory().apply { reset("session-a") }

    @Test fun eachSecondKeepsTheLastActualValueAndTimestampWithoutAveraging() {
        val history = history()
        history.receive(0, 100)
        history.receive(340, 140)
        history.receive(990, 140)
        assertEquals(1, history.snapshot().size)
        assertEquals(990L, history.snapshot().firstOrNull()?.elapsedMs)
        assertEquals(990L, history.snapshot().lastOrNull()?.elapsedMs)
        assertEquals(140, history.snapshot().single().bpm)
        history.receive(1000, 150)
        assertEquals(listOf(0L, 1L), history.snapshot().map { it.secondBucket })
        assertEquals(listOf(990L, 1000L), history.snapshot().map { it.elapsedMs })
        assertEquals(listOf(true, false), history.snapshot().map { it.breakBefore })
        val before = history.snapshot()
        repeat(10) { history.snapshot() }
        assertEquals(before, history.snapshot())
    }

    @Test fun onlyGapsStrictlyOverThreeSecondsBreakAndReplacementKeepsTheBreak() {
        val history = history()
        history.receive(0)
        history.receive(1000)
        history.receive(4000)
        assertFalse(history.snapshot().last().breakBefore)
        history.receive(7001)
        history.receive(7999)
        assertTrue(history.snapshot().last().breakBefore)
        assertEquals(7999L, history.snapshot().last().elapsedMs)
        history.receive(8000)
        assertFalse(history.snapshot().last().breakBefore)
    }

    @Test fun invalidValuesAreNullAndIntraBucketRecoveryCannotEraseDiscontinuity() {
        val history = history()
        history.receive(0)
        history.receive(1000)
        history.receive(1100, null)
        assertNull(history.snapshot().last().bpm)
        assertTrue(history.snapshot().last().breakBefore)
        history.receive(1200, 125)
        assertEquals(125, history.snapshot().last().bpm)
        assertTrue(history.snapshot().last().breakBefore)
        history.receive(2000, null)
        history.receive(3000, 130)
        assertTrue(history.snapshot().last().breakBefore)
        history.receive(4000)
        assertFalse(history.snapshot().last().breakBefore)
    }

    @Test fun subscriptionBreaksKeepAllPreviousBucketsAndSurviveSameBucketReplacement() {
        for (status in listOf(SubscriptionStatus.IDLE, SubscriptionStatus.STARTING,
            SubscriptionStatus.FAILED, SubscriptionStatus.STOPPED, SubscriptionStatus.STOPPING)) {
            val history = history()
            history.receive(0)
            history.receive(1100)
            history.onSubscriptionState(status)
            assertEquals(2, history.snapshot().size)
            history.onSubscriptionState(SubscriptionStatus.RECEIVING)
            history.receive(1200)
            history.receive(1300)
            assertEquals(2, history.snapshot().size)
            assertTrue(history.snapshot().last().breakBefore)
            history.receive(2000)
            assertFalse(history.snapshot().last().breakBefore)
        }
    }

    @Test fun fourHourBoundaryRetainsAll14401BucketsWithoutEvictionOrPadding() {
        val history = history()
        for (second in 0..14_400) history.receive(second * 1000L, 100 + second % 50)
        val full = history.snapshot()
        assertEquals(14_401, full.size)
        assertEquals(0L, full.first().elapsedMs)
        assertEquals(14_400_000L, full.last().elapsedMs)
        assertTrue(full.all { it.sessionId == "session-a" })
        history.receive(14_400_001L, 190)
        history.receive(20_000_000L, 200)
        assertEquals(full, history.snapshot())

        val sparse = history()
        sparse.receive(123)
        sparse.receive(14_399_999L)
        sparse.receive(14_400_000L)
        sparse.receive(14_400_001L)
        assertEquals(listOf(123L, 14_399_999L, 14_400_000L), sparse.snapshot().map { it.elapsedMs })
        val late = history()
        late.receive(14_400_001L)
        assertEquals(0, late.snapshot().size)
        assertNull(late.snapshot().firstOrNull()?.elapsedMs)
    }

    @Test fun stopFreezesPartialSecondAndNewStartCannotMutateAnExistingSnapshot() {
        val history = HrHistory()
        history.receive(0)
        assertTrue(history.snapshot().isEmpty())
        history.reset("old")
        history.receive(0)
        history.receive(1789)
        history.stop()
        val frozen = history.snapshot()
        history.receive(1999)
        history.onSubscriptionState(SubscriptionStatus.STARTING)
        history.stop()
        assertEquals(frozen, history.snapshot())
        history.reset("new")
        assertFalse(history.frozen)
        assertTrue(history.snapshot().isEmpty())
        history.receive(50)
        assertEquals("new", history.snapshot().single().sessionId)
        assertEquals(listOf(0L, 1789L), frozen.map { it.elapsedMs })
        assertTrue(frozen.all { it.sessionId == "old" })
    }
}
