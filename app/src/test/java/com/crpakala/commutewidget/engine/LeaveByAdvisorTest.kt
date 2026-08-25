package com.crpakala.commutewidget.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaveByAdvisorTest {
    @Test
    fun computeLeaveByMinuteOfDay_subtractsCeiledTravelMinutes() {
        // 25 minutes 1 second of travel rounds up to 26 minutes.
        assertEquals(544, computeLeaveByMinuteOfDay(arriveByMinuteOfDay = 570, durationSeconds = 1_501))
    }

    @Test
    fun computeLeaveByMinuteOfDay_exactMinuteBoundaryDoesNotRoundUp() {
        assertEquals(545, computeLeaveByMinuteOfDay(arriveByMinuteOfDay = 570, durationSeconds = 1_500))
    }

    @Test
    fun computeLeaveByMinuteOfDay_zeroDurationLeavesRightAtArriveBy() {
        assertEquals(570, computeLeaveByMinuteOfDay(arriveByMinuteOfDay = 570, durationSeconds = 0))
    }

    @Test
    fun computeLeaveByMinuteOfDay_clampsToStartOfDayWhenAlreadyLate() {
        // A 20-hour "commute" against an early arrive-by would go negative without clamping.
        assertEquals(0, computeLeaveByMinuteOfDay(arriveByMinuteOfDay = 100, durationSeconds = 20 * 3_600L))
    }

    @Test
    fun computeLeaveByMinuteOfDay_neverReturnsNegative() {
        val result = computeLeaveByMinuteOfDay(arriveByMinuteOfDay = 0, durationSeconds = 60)
        assertTrue(result >= 0)
        assertEquals(0, result)
    }

    @Test
    fun shouldFireLeaveByNotification_beforeWindowDoesNotFire() {
        assertFalse(
            shouldFireLeaveByNotification(
                leaveByEnabled = true,
                nowMinuteOfDay = 540,
                leaveByMinuteOfDay = 545,
                arriveByMinuteOfDay = 570,
                alreadyNotifiedToday = false,
            ),
        )
    }

    @Test
    fun shouldFireLeaveByNotification_insideWindowNotYetNotifiedFires() {
        assertTrue(
            shouldFireLeaveByNotification(
                leaveByEnabled = true,
                nowMinuteOfDay = 550,
                leaveByMinuteOfDay = 545,
                arriveByMinuteOfDay = 570,
                alreadyNotifiedToday = false,
            ),
        )
    }

    @Test
    fun shouldFireLeaveByNotification_insideWindowAlreadyNotifiedTodayDoesNotFire() {
        assertFalse(
            shouldFireLeaveByNotification(
                leaveByEnabled = true,
                nowMinuteOfDay = 550,
                leaveByMinuteOfDay = 545,
                arriveByMinuteOfDay = 570,
                alreadyNotifiedToday = true,
            ),
        )
    }

    @Test
    fun shouldFireLeaveByNotification_afterArriveByDoesNotFire() {
        assertFalse(
            shouldFireLeaveByNotification(
                leaveByEnabled = true,
                nowMinuteOfDay = 571,
                leaveByMinuteOfDay = 545,
                arriveByMinuteOfDay = 570,
                alreadyNotifiedToday = false,
            ),
        )
    }

    @Test
    fun shouldFireLeaveByNotification_disabledNeverFires() {
        assertFalse(
            shouldFireLeaveByNotification(
                leaveByEnabled = false,
                nowMinuteOfDay = 550,
                leaveByMinuteOfDay = 545,
                arriveByMinuteOfDay = 570,
                alreadyNotifiedToday = false,
            ),
        )
    }

    @Test
    fun shouldFireLeaveByNotification_boundariesAreInclusive() {
        assertTrue(
            shouldFireLeaveByNotification(
                leaveByEnabled = true,
                nowMinuteOfDay = 545,
                leaveByMinuteOfDay = 545,
                arriveByMinuteOfDay = 570,
                alreadyNotifiedToday = false,
            ),
        )
        assertTrue(
            shouldFireLeaveByNotification(
                leaveByEnabled = true,
                nowMinuteOfDay = 570,
                leaveByMinuteOfDay = 545,
                arriveByMinuteOfDay = 570,
                alreadyNotifiedToday = false,
            ),
        )
    }
}
