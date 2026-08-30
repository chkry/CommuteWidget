package com.crpakala.commutewidget.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenEventsReaderTest {
    @Test
    fun mapScreenEventType_screenInteractiveMapsToTrue() {
        assertEquals(true, ScreenEventsReader.mapScreenEventType(SCREEN_INTERACTIVE))
    }

    @Test
    fun mapScreenEventType_screenNonInteractiveMapsToFalse() {
        assertEquals(false, ScreenEventsReader.mapScreenEventType(SCREEN_NON_INTERACTIVE))
    }

    @Test
    fun mapScreenEventType_otherEventTypesMapToNull() {
        assertNull(ScreenEventsReader.mapScreenEventType(ACTIVITY_RESUMED))
        assertNull(ScreenEventsReader.mapScreenEventType(-1))
        assertNull(ScreenEventsReader.mapScreenEventType(0))
    }

    private companion object {
        // Mirrors android.app.usage.UsageEvents.Event constants (added API 28: 15 and 16),
        // kept as literals so this test does not depend on Android framework stub behavior.
        const val ACTIVITY_RESUMED = 1
        const val SCREEN_INTERACTIVE = 15
        const val SCREEN_NON_INTERACTIVE = 16
    }
}
