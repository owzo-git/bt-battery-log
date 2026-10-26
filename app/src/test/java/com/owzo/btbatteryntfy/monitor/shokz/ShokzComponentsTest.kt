package com.owzo.btbatteryntfy.monitor.shokz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShokzComponentsTest {

    private fun status(left: Int?, right: Int?, case: Int?, at: Long) =
        ShokzBatteryStatus(left, right, case, null, null, null, at)

    @Test
    fun mergeStoresEachPartWithItsTimestamp() {
        val merged = ShokzComponents().mergedWith(status(80, 70, 60, at = 1_000))
        assertEquals(ShokzComponentReading(80, 1_000), merged.left)
        assertEquals(ShokzComponentReading(70, 1_000), merged.right)
        assertEquals(ShokzComponentReading(60, 1_000), merged.case)
        assertEquals(1_000, merged.latestSampledAtMs)
    }

    @Test
    fun partialReadKeepsPreviouslyKnownParts() {
        val earlier = ShokzComponents().mergedWith(status(80, 70, 60, at = 1_000))
        val merged = earlier.mergedWith(status(90, 90, null, at = 2_000))
        assertEquals(ShokzComponentReading(90, 2_000), merged.left)
        assertEquals(ShokzComponentReading(90, 2_000), merged.right)
        assertEquals(ShokzComponentReading(60, 1_000), merged.case)
        assertEquals(2_000, merged.latestSampledAtMs)
    }

    @Test
    fun caseIsNeverDerivedFromBuds() {
        val merged = ShokzComponents().mergedWith(status(80, 60, null, at = 1_000))
        assertNull(merged.case)
        assertEquals("L 80% · R 60% · Case —", ShokzComponentFormatter.compact(merged))
    }

    @Test
    fun emptyComponentsReportNothingKnown() {
        val empty = ShokzComponents()
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.latestSampledAtMs)
        assertEquals("Left — · Right — · Case —", ShokzComponentFormatter.labeled(empty))
    }

    @Test
    fun sampledSinceDropsReadingsFromEarlierConnections() {
        val components = ShokzComponents()
            .mergedWith(status(80, 70, 60, at = 1_000))
            .mergedWith(status(90, null, null, at = 5_000))
        val fresh = components.sampledSince(sessionStartedAtMs = 4_000)
        assertEquals(ShokzComponentReading(90, 5_000), fresh.left)
        assertNull(fresh.right)
        assertNull(fresh.case)
        assertEquals("L 90% · R — · Case —", ShokzComponentFormatter.compact(fresh))
        assertTrue(components.sampledSince(sessionStartedAtMs = 6_000).isEmpty)
    }

    @Test
    fun formatterUsesPercentValues() {
        val components = ShokzComponents().mergedWith(status(100, 100, 60, at = 1))
        assertEquals("L 100% · R 100% · Case 60%", ShokzComponentFormatter.compact(components))
        assertEquals("Left 100% · Right 100% · Case 60%", ShokzComponentFormatter.labeled(components))
    }

    @Test
    fun notificationShowsLowerBudThenCase() {
        val components = ShokzComponents().mergedWith(status(90, 70, 60, at = 1))
        assertEquals("70% / 60%", ShokzComponentFormatter.notification(components, budsFallbackPercent = 100))
    }

    @Test
    fun notificationFallsBackForBudsButNeverForCase() {
        val leftOnly = ShokzComponents().mergedWith(status(80, null, null, at = 1))
        assertEquals("80% / —", ShokzComponentFormatter.notification(leftOnly, budsFallbackPercent = 100))
        val caseOnly = ShokzComponents().mergedWith(status(null, null, 40, at = 1))
        assertEquals("100% / 40%", ShokzComponentFormatter.notification(caseOnly, budsFallbackPercent = 100))
    }
}

class ShokzReadPolicyTest {

    private val sessionStart = 10_000_000L

    private fun state(
        lastAttempt: Long = 0,
        lastSuccess: Long = 0,
        blocked: Boolean = false,
    ) = ShokzSessionState(sessionStart, lastAttempt, lastSuccess, blocked)

    @Test
    fun connectReadsOncePerConnection() {
        assertTrue(ShokzReadPolicy.shouldRead(ShokzReadTrigger.CONNECTED, state(), sessionStart + 5_000))
        val afterSuccess = state(lastAttempt = sessionStart + 5_000, lastSuccess = sessionStart + 6_000)
        assertFalse(ShokzReadPolicy.shouldRead(ShokzReadTrigger.CONNECTED, afterSuccess, sessionStart + 600_000))
    }

    @Test
    fun connectEventsBurstTriggerOnlyOneAttempt() {
        val justTried = state(lastAttempt = sessionStart + 5_000)
        assertFalse(ShokzReadPolicy.shouldRead(ShokzReadTrigger.CONNECTED, justTried, sessionStart + 10_000))
        assertTrue(
            ShokzReadPolicy.shouldRead(
                ShokzReadTrigger.CONNECTED,
                justTried,
                sessionStart + 5_000 + ShokzReadPolicy.CONNECT_RETRY_MS,
            ),
        )
    }

    @Test
    fun successFromPreviousConnectionDoesNotSkipConnectRead() {
        val previous = state(lastAttempt = sessionStart - 60_000, lastSuccess = sessionStart - 60_000)
        assertTrue(ShokzReadPolicy.shouldRead(ShokzReadTrigger.CONNECTED, previous, sessionStart + 5_000))
    }

    @Test
    fun batteryChangeRefreshesAtMostEveryThirtyMinutes() {
        val lastAttempt = sessionStart + 5_000
        val recent = state(lastAttempt = lastAttempt, lastSuccess = lastAttempt)
        assertFalse(
            ShokzReadPolicy.shouldRead(
                ShokzReadTrigger.BATTERY_CHANGED,
                recent,
                lastAttempt + ShokzReadPolicy.REFRESH_INTERVAL_MS - 1,
            ),
        )
        assertTrue(
            ShokzReadPolicy.shouldRead(
                ShokzReadTrigger.BATTERY_CHANGED,
                recent,
                lastAttempt + ShokzReadPolicy.REFRESH_INTERVAL_MS,
            ),
        )
        assertTrue(ShokzReadPolicy.REFRESH_INTERVAL_MS >= 30 * 60_000L)
    }

    @Test
    fun refusedChannelStopsAutomaticReadsForTheSession() {
        val blocked = state(lastAttempt = sessionStart + 5_000, blocked = true)
        val muchLater = sessionStart + 24 * 60 * 60_000L
        assertFalse(ShokzReadPolicy.shouldRead(ShokzReadTrigger.CONNECTED, blocked, muchLater))
        assertFalse(ShokzReadPolicy.shouldRead(ShokzReadTrigger.BATTERY_CHANGED, blocked, muchLater))
    }

    @Test
    fun manualReadAlwaysAllowed() {
        val blocked = state(lastAttempt = sessionStart + 5_000, lastSuccess = sessionStart + 5_000, blocked = true)
        assertTrue(ShokzReadPolicy.shouldRead(ShokzReadTrigger.MANUAL, blocked, sessionStart + 5_001))
    }

    @Test
    fun onlyConnectFailureBlocksSession() {
        assertTrue(ShokzReadPolicy.blocksSession(ShokzReadFailure.CONNECT_FAILED))
        for (failure in ShokzReadFailure.entries - ShokzReadFailure.CONNECT_FAILED) {
            assertFalse(failure.name, ShokzReadPolicy.blocksSession(failure))
        }
    }
}
