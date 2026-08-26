package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5 precedence: [resolveDirectionForSnapshot] is now a strict two-way choice - window-mode
 * commute direction, or the calendar-mode fallback direction - with nothing else competing. The
 * v3 favourite-override precedence this test used to cover (`resolveDestinationTarget`,
 * `DestinationTarget.FavouriteTarget`/`DefaultTarget`, `shouldRecordHistorySample`) is retired
 * along with the favourite override pipeline and the history subsystem entirely: favourites
 * shrink to a saved-places list with no engine-level effect on destination resolution at all.
 */
class DestinationResolutionTest {
    @Test
    fun commuteMode_alwaysUsesTheWindowModeDirection_regardlessOfNextWindowHint() {
        val toWork = resolveDirectionForSnapshot(WidgetMode.Commute(Direction.TO_WORK), Direction.TO_HOME)
        val toHome = resolveDirectionForSnapshot(WidgetMode.Commute(Direction.TO_HOME), Direction.TO_WORK)

        assertEquals(Direction.TO_WORK, toWork)
        assertEquals(Direction.TO_HOME, toHome)
    }

    @Test
    fun calendarMode_usesTheNextWindowHintWhenPresent() {
        val target = resolveDirectionForSnapshot(WidgetMode.Calendar, Direction.TO_HOME)

        assertEquals(Direction.TO_HOME, target)
    }

    @Test
    fun calendarMode_fallsBackToWorkWhenNoNextWindowHintExists() {
        val target = resolveDirectionForSnapshot(WidgetMode.Calendar, null)

        assertEquals(Direction.TO_WORK, target)
    }
}
