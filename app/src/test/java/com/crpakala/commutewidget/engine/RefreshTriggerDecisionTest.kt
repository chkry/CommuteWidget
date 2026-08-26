package com.crpakala.commutewidget.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5 FIX-1/FIX-2: [shouldRenderEarlyBeforeFetch] and [shouldPlayCooldownPendingFrame] both gate
 * on [RefreshTrigger.TAP] only - a human is watching a tap happen, nobody is watching a background
 * AUTO (window boundary) or TICK (calendar staleness) refresh. Covers every trigger value so a
 * future addition to the enum cannot silently fall through untested.
 */
class RefreshTriggerDecisionTest {
    @Test
    fun shouldRenderEarlyBeforeFetch_onlyTrueForTap() {
        assertTrue(shouldRenderEarlyBeforeFetch(RefreshTrigger.TAP))
        assertFalse(shouldRenderEarlyBeforeFetch(RefreshTrigger.AUTO))
        assertFalse(shouldRenderEarlyBeforeFetch(RefreshTrigger.TICK))
    }

    @Test
    fun shouldPlayCooldownPendingFrame_onlyTrueForTap() {
        assertTrue(shouldPlayCooldownPendingFrame(RefreshTrigger.TAP))
        assertFalse(shouldPlayCooldownPendingFrame(RefreshTrigger.AUTO))
        assertFalse(shouldPlayCooldownPendingFrame(RefreshTrigger.TICK))
    }
}
