package com.crpakala.commutewidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredValueCodecTest {
    @Test
    fun parseTravelMode_validName() {
        assertEquals(TravelMode.TWO_WHEELER, parseTravelMode("TWO_WHEELER"))
    }

    @Test
    fun parseTravelMode_unknownFallsBackToDefault() {
        assertEquals(TravelMode.DRIVE, parseTravelMode("BICYCLE"))
    }

    @Test
    fun parseTravelMode_nullAndBlankFallBackToDefault() {
        assertEquals(TravelMode.DRIVE, parseTravelMode(null))
        assertEquals(TravelMode.DRIVE, parseTravelMode(""))
        assertEquals(TravelMode.DRIVE, parseTravelMode("   "))
    }

    @Test
    fun parseTravelMode_customDefault() {
        assertEquals(TravelMode.TWO_WHEELER, parseTravelMode("INVALID", TravelMode.TWO_WHEELER))
    }

    @Test
    fun parseDirection_validName() {
        assertEquals(Direction.TO_HOME, parseDirection("TO_HOME"))
    }

    @Test
    fun parseDirection_unknownFallsBackToDefault() {
        assertEquals(Direction.TO_WORK, parseDirection("TO_MARS"))
    }

    @Test
    fun parseDirection_nullAndBlankFallBackToDefault() {
        assertEquals(Direction.TO_WORK, parseDirection(null))
        assertEquals(Direction.TO_WORK, parseDirection(""))
    }

    @Test
    fun placeJson_roundTrip() {
        val place = Place(address = "123 Main St", lat = 12.9716, lng = 77.5946)
        val encoded = encodePlace(place)
        assertEquals(place, decodePlace(encoded))
    }

    @Test
    fun placeJson_invalidReturnsNull() {
        assertNull(decodePlace("{not json}"))
        assertNull(decodePlace(null))
        assertNull(decodePlace(""))
    }

    @Test
    fun commuteSnapshotJson_roundTrip() {
        val snapshot = CommuteSnapshot(
            direction = Direction.TO_WORK,
            durationSeconds = 1800L,
            durationNoTrafficSeconds = 1500L,
            distanceMeters = 12000L,
            mapImagePath = "/cache/map.png",
            fetchedAtEpochMillis = 1_700_000_000_000L,
            lastFetchFailed = false,
            lastErrorMessage = null,
        )
        val encoded = encodeCommuteSnapshot(snapshot)
        assertEquals(snapshot, decodeCommuteSnapshot(encoded))
    }

    @Test
    fun commuteSnapshotJson_invalidReturnsNull() {
        assertNull(decodeCommuteSnapshot("[]"))
        assertNull(decodeCommuteSnapshot(null))
    }
}
