package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.data.Direction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the v2 integration bug where a slot/tick-style fetch (v2-v4:
 * `RefreshTrigger.SLOT`; v5: [RefreshTrigger.TICK]) could reuse a stale map image left over from a
 * different destination simply because the home/work-by-time-of-day [Direction] happened to be
 * unchanged. See [shouldReuseSlotMap] for the fixed decision logic, now serving
 * [RefreshTrigger.TICK]'s v5 calendar-staleness role instead of the retired 10-minute
 * history-sampling cadence.
 */
class SlotMapReuseTest {
    private val direction = Direction.TO_WORK
    private val destination = LatLng(12.9, 77.6)

    @Test
    fun sameDirectionAndSameDestination_reusesMap() {
        assertTrue(
            shouldReuseSlotMap(
                previousDirection = Direction.TO_WORK,
                previousDestinationLat = 12.9,
                previousDestinationLng = 77.6,
                direction = direction,
                destination = destination,
            ),
        )
    }

    @Test
    fun sameDirectionButDifferentDestination_doesNotReuseMap() {
        // e.g. the resolved destination coordinates moved between fetches while the plain
        // home/work-by-time-of-day direction stayed the same.
        assertFalse(
            shouldReuseSlotMap(
                previousDirection = Direction.TO_WORK,
                previousDestinationLat = 13.1,
                previousDestinationLng = 77.9,
                direction = direction,
                destination = destination,
            ),
        )
    }

    @Test
    fun differentDirectionSameCoordinates_doesNotReuseMap() {
        assertFalse(
            shouldReuseSlotMap(
                previousDirection = Direction.TO_HOME,
                previousDestinationLat = 12.9,
                previousDestinationLng = 77.6,
                direction = direction,
                destination = destination,
            ),
        )
    }

    @Test
    fun missingPreviousDestinationCoordinates_doesNotReuseMap() {
        // Covers a v1-format snapshot decoded before destinationLat/Lng existed: null defaults
        // must not be treated as an accidental coordinate match.
        assertFalse(
            shouldReuseSlotMap(
                previousDirection = Direction.TO_WORK,
                previousDestinationLat = null,
                previousDestinationLng = null,
                direction = direction,
                destination = destination,
            ),
        )
    }

    @Test
    fun noPreviousDirection_doesNotReuseMap() {
        assertFalse(
            shouldReuseSlotMap(
                previousDirection = null,
                previousDestinationLat = 12.9,
                previousDestinationLng = 77.6,
                direction = direction,
                destination = destination,
            ),
        )
    }
}
