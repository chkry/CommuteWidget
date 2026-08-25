package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.ActiveFavourite
import com.crpakala.commutewidget.data.Direction
import com.crpakala.commutewidget.data.Favourite
import com.crpakala.commutewidget.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3 precedence: [resolveDestinationTarget] only ever distinguishes an active favourite from the
 * window-model default direction. The v2 tap-triggered calendar override (`shouldAttemptCalendarLookup`,
 * `DestinationTarget.CalendarTarget`) is retired along with the per-tap calendar lookup itself -
 * calendar mode is now a distinct [WidgetMode] branch, not a target competing in this precedence.
 */
class DestinationResolutionTest {
    private val favourite = Favourite("Gym", Place("123 Gym St", 12.9, 77.6))
    private val active = ActiveFavourite(favourite, activatedAtEpochMillis = 0, expiresAtEpochMillis = Long.MAX_VALUE)

    @Test
    fun activeFavourite_takesPrecedenceOverDefault() {
        val target = resolveDestinationTarget(active, Direction.TO_WORK)

        assertTrue(target is DestinationTarget.FavouriteTarget)
        assertEquals(favourite, (target as DestinationTarget.FavouriteTarget).favourite)
    }

    @Test
    fun noActiveFavourite_fallsBackToDefaultWithGivenDirection() {
        val toWork = resolveDestinationTarget(null, Direction.TO_WORK)
        val toHome = resolveDestinationTarget(null, Direction.TO_HOME)

        assertEquals(DestinationTarget.DefaultTarget(Direction.TO_WORK), toWork)
        assertEquals(DestinationTarget.DefaultTarget(Direction.TO_HOME), toHome)
    }

    @Test
    fun nullActiveFavourite_isNeverTreatedAsFavourite() {
        val target = resolveDestinationTarget(null, Direction.TO_WORK)

        assertFalse(target is DestinationTarget.FavouriteTarget)
    }

    @Test
    fun shouldRecordHistorySample_onlyDefaultTargetAndHistoryEnabled() {
        val default = DestinationTarget.DefaultTarget(Direction.TO_WORK)
        val favouriteTarget = DestinationTarget.FavouriteTarget(favourite)

        assertTrue(shouldRecordHistorySample(historyEnabled = true, target = default))
        assertFalse(shouldRecordHistorySample(historyEnabled = false, target = default))
        assertFalse(shouldRecordHistorySample(historyEnabled = true, target = favouriteTarget))
        assertFalse(shouldRecordHistorySample(historyEnabled = false, target = favouriteTarget))
    }
}
