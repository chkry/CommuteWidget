package com.crpakala.commutewidget.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTakeoverTest {
    private val now = 1_787_740_000_000L
    private val minute = 60_000L

    @Test
    fun takeover_appliesWithinWindow() {
        assertTrue(eventTakeoverApplies(now + 119 * minute, true, now, 120))
        assertTrue(eventTakeoverApplies(now + 120 * minute, true, now, 120))
    }

    @Test
    fun takeover_rejectedBeyondWindow() {
        assertFalse(eventTakeoverApplies(now + 121 * minute, true, now, 120))
    }

    @Test
    fun takeover_requiresLocation() {
        assertFalse(eventTakeoverApplies(now + 30 * minute, false, now, 120))
    }

    @Test
    fun takeover_appliesToAlreadyStartedEvent() {
        assertTrue(eventTakeoverApplies(now - 10 * minute, true, now, 120))
    }

    @Test
    fun takeover_respectsConfiguredMinutes() {
        assertFalse(eventTakeoverApplies(now + 30 * minute, true, now, 15))
        assertTrue(eventTakeoverApplies(now + 30 * minute, true, now, 30))
    }
}
