package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.data.Direction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the v2 integration bug where a [RefreshTrigger.SLOT] fetch could reuse
 * a stale map image left over from a different destination (e.g. a favourite or calendar target)
 * simply because the home/work-by-time-of-day [Direction] happened to be unchanged. See
 * [shouldReuseSlotMap] for the fixed decision logic.
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
        // e.g. an active favourite occupied the previous SLOT fetch while the plain
        // home/work-by-time-of-day direction stayed the same across both fetches.
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
