package com.crpakala.commutewidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun commuteSnapshotJson_v1FormatDecodesWithNewFieldDefaults() {
        val v1Json = """
            {
              "direction": "TO_WORK",
              "durationSeconds": 1800,
              "durationNoTrafficSeconds": 1500,
              "distanceMeters": 12000,
              "mapImagePath": "/cache/map.png",
              "fetchedAtEpochMillis": 1700000000000,
              "lastFetchFailed": false,
              "lastErrorMessage": null
            }
        """.trimIndent()
        val decoded = decodeCommuteSnapshot(v1Json)
        requireNotNull(decoded)
        assertEquals(Direction.TO_WORK, decoded.direction)
        assertEquals(1800L, decoded.durationSeconds)
        assertEquals(1500L, decoded.durationNoTrafficSeconds)
        assertEquals(12000L, decoded.distanceMeters)
        assertEquals("/cache/map.png", decoded.mapImagePath)
        assertEquals(1_700_000_000_000L, decoded.fetchedAtEpochMillis)
        assertEquals(false, decoded.lastFetchFailed)
        assertNull(decoded.lastErrorMessage)
        assertNull(decoded.destinationLabel)
        assertNull(decoded.destinationLat)
        assertNull(decoded.destinationLng)
        assertNull(decoded.leaveByMinuteOfDay)
        assertEquals(SnapshotMode.COMMUTE, decoded.mode)
        assertNull(decoded.eventStartEpochMillis)
        assertNull(decoded.nextWindowLabel)
        assertNull(decoded.nextWindowStartMinuteOfDay)
    }

    @Test
    fun commuteSnapshotJson_v2FormatDecodesWithV3FieldDefaults() {
        val v2Json = """
            {
              "direction": "TO_HOME",
              "durationSeconds": 2400,
              "durationNoTrafficSeconds": 2100,
              "distanceMeters": 18000,
              "mapImagePath": "/cache/map-home.png",
              "fetchedAtEpochMillis": 1700000000000,
              "lastFetchFailed": false,
              "lastErrorMessage": null,
              "destinationLabel": "Client meeting",
              "destinationLat": 12.9716,
              "destinationLng": 77.5946,
              "leaveByMinuteOfDay": 540
            }
        """.trimIndent()
        val decoded = decodeCommuteSnapshot(v2Json)
        requireNotNull(decoded)
        assertEquals(Direction.TO_HOME, decoded.direction)
        assertEquals(2400L, decoded.durationSeconds)
        assertEquals(2100L, decoded.durationNoTrafficSeconds)
        assertEquals(18000L, decoded.distanceMeters)
        assertEquals("/cache/map-home.png", decoded.mapImagePath)
        assertEquals(1_700_000_000_000L, decoded.fetchedAtEpochMillis)
        assertEquals(false, decoded.lastFetchFailed)
        assertNull(decoded.lastErrorMessage)
        assertEquals("Client meeting", decoded.destinationLabel)
        assertEquals(12.9716, decoded.destinationLat!!, 0.0001)
        assertEquals(77.5946, decoded.destinationLng!!, 0.0001)
        assertEquals(540, decoded.leaveByMinuteOfDay)
        assertEquals(SnapshotMode.COMMUTE, decoded.mode)
        assertNull(decoded.eventStartEpochMillis)
        assertNull(decoded.nextWindowLabel)
        assertNull(decoded.nextWindowStartMinuteOfDay)
    }

    @Test
    fun snapshotMode_roundTrip() {
        for (mode in SnapshotMode.entries) {
            val snapshot = CommuteSnapshot(
                direction = Direction.TO_WORK,
                durationSeconds = 900L,
                durationNoTrafficSeconds = 800L,
                distanceMeters = 5000L,
                mapImagePath = null,
                fetchedAtEpochMillis = 1_000L,
                lastFetchFailed = false,
                lastErrorMessage = null,
                mode = mode,
            )
            val encoded = encodeCommuteSnapshot(snapshot)
            val decoded = decodeCommuteSnapshot(encoded)
            requireNotNull(decoded)
            assertEquals(mode, decoded.mode)
        }
    }

    @Test
    fun snapshotMode_corruptFallsBackToCommute() {
        val json = """
            {
              "direction": "TO_WORK",
              "durationSeconds": 900,
              "durationNoTrafficSeconds": 800,
              "distanceMeters": 5000,
              "mapImagePath": null,
              "fetchedAtEpochMillis": 1000,
              "lastFetchFailed": false,
              "lastErrorMessage": null,
              "mode": "NOT_A_REAL_MODE"
            }
        """.trimIndent()
        val decoded = decodeCommuteSnapshot(json)
        requireNotNull(decoded)
        assertEquals(SnapshotMode.COMMUTE, decoded.mode)
    }

    @Test
    fun parseSnapshotMode_validAndCorrupt() {
        assertEquals(SnapshotMode.CALENDAR_EVENT, parseSnapshotMode("CALENDAR_EVENT"))
        assertEquals(SnapshotMode.COMMUTE, parseSnapshotMode("UNKNOWN"))
        assertEquals(SnapshotMode.COMMUTE, parseSnapshotMode(null))
        assertEquals(SnapshotMode.COMMUTE, parseSnapshotMode(""))
    }

    @Test
    fun favouritesJson_roundTrip() {
        val favourites = listOf(
            Favourite(label = "Gym", place = Place("Gym St", 12.0, 77.0)),
            Favourite(label = "School", place = Place("School Rd", 13.0, 78.0)),
        )
        val encoded = encodeFavourites(favourites)
        assertEquals(favourites, decodeFavourites(encoded))
    }

    @Test
    fun favouritesJson_corruptReturnsEmptyList() {
        assertEquals(emptyList<Favourite>(), decodeFavourites("{not json}"))
        assertEquals(emptyList<Favourite>(), decodeFavourites("[{bad}]"))
        assertEquals(emptyList<Favourite>(), decodeFavourites(null))
        assertEquals(emptyList<Favourite>(), decodeFavourites(""))
    }

    @Test
    fun activeFavouriteJson_roundTrip() {
        val favourite = Favourite(label = "Gym", place = Place("Gym St", 12.0, 77.0))
        val active = ActiveFavourite(
            favourite = favourite,
            activatedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 3_600_000L,
        )
        val encoded = encodeActiveFavourite(active)
        assertEquals(active, decodeActiveFavourite(encoded))
    }

    @Test
    fun isActive_beforeExpiry() {
        val active = ActiveFavourite(
            favourite = Favourite("Gym", Place("Gym St", 12.0, 77.0)),
            activatedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 5_000L,
        )
        assertTrue(isActive(active, nowEpochMillis = 4_999L))
    }

    @Test
    fun isActive_atExpiry() {
        val active = ActiveFavourite(
            favourite = Favourite("Gym", Place("Gym St", 12.0, 77.0)),
            activatedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 5_000L,
        )
        assertFalse(isActive(active, nowEpochMillis = 5_000L))
    }

    @Test
    fun isActive_afterExpiry() {
        val active = ActiveFavourite(
            favourite = Favourite("Gym", Place("Gym St", 12.0, 77.0)),
            activatedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 5_000L,
        )
        assertFalse(isActive(active, nowEpochMillis = 5_001L))
    }

    @Test
    fun isActive_nullIsInactive() {
        assertFalse(isActive(null, nowEpochMillis = 0L))
    }

    @Test
    fun longSetJson_roundTrip() {
        val values = setOf(42L, 7L, 99L)
        val encoded = encodeLongSet(values)
        assertEquals(values, decodeLongSet(encoded))
    }

    @Test
    fun longSetJson_corruptReturnsDefault() {
        assertEquals(emptySet<Long>(), decodeLongSet("{bad}"))
        assertEquals(emptySet<Long>(), decodeLongSet(null))
        assertEquals(setOf(1L), decodeLongSet("{bad}", default = setOf(1L)))
    }

    @Test
    fun intSetJson_roundTrip() {
        val values = setOf(1, 3, 5)
        val encoded = encodeIntSet(values)
        assertEquals(values, decodeIntSet(encoded))
    }

    @Test
    fun intSetJson_corruptReturnsDefault() {
        assertEquals(emptySet<Int>(), decodeIntSet("{bad}"))
        assertEquals(emptySet<Int>(), decodeIntSet(null))
        assertEquals(setOf(1, 2, 3, 4, 5), decodeIntSet("{bad}", default = setOf(1, 2, 3, 4, 5)))
    }
}
