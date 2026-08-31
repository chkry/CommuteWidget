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

    /**
     * v5 FIX-9 adds `routedOverEarlier` to [CommuteSnapshot]. A v4-format snapshot JSON (predating
     * the field) must still decode, with the new field defaulting to `false` rather than failing
     * to parse.
     */
    @Test
    fun commuteSnapshotJson_v4FormatDecodesWithRoutedOverEarlierDefault() {
        val v4Json = """
            {
              "direction": "TO_WORK",
              "durationSeconds": 1200,
              "durationNoTrafficSeconds": 1000,
              "distanceMeters": 8000,
              "mapImagePath": "/cache/event-map.png",
              "fetchedAtEpochMillis": 1700000000000,
              "lastFetchFailed": false,
              "lastErrorMessage": null,
              "destinationLabel": "Client meeting",
              "destinationLat": 12.9716,
              "destinationLng": 77.5946,
              "leaveByMinuteOfDay": 540,
              "mode": "CALENDAR_EVENT",
              "eventStartEpochMillis": 1700000600000
            }
        """.trimIndent()
        val decoded = decodeCommuteSnapshot(v4Json)
        requireNotNull(decoded)
        assertEquals(SnapshotMode.CALENDAR_EVENT, decoded.mode)
        assertEquals(1700000600000L, decoded.eventStartEpochMillis)
        assertFalse(decoded.routedOverEarlier)
    }

    @Test
    fun commuteSnapshotJson_currentFormatDecodesWithRoutineAutomationFieldDefaults() {
        val currentJson = """
            {
              "direction": "TO_WORK",
              "durationSeconds": 1200,
              "durationNoTrafficSeconds": 1000,
              "distanceMeters": 8000,
              "mapImagePath": "/cache/commute-map.png",
              "fetchedAtEpochMillis": 1700000000000,
              "lastFetchFailed": false,
              "lastErrorMessage": null,
              "destinationLabel": "Work",
              "destinationLat": 12.9716,
              "destinationLng": 77.5946,
              "leaveByMinuteOfDay": 540,
              "mode": "COMMUTE",
              "eventStartEpochMillis": null,
              "nextWindowLabel": null,
              "nextWindowStartMinuteOfDay": null,
              "routedOverEarlier": false
            }
        """.trimIndent()

        val decoded = decodeCommuteSnapshot(currentJson)
        requireNotNull(decoded)
        assertNull(decoded.tomorrowEventTitle)
        assertNull(decoded.tomorrowEventStartEpochMillis)
        assertNull(decoded.todayEventCount)
        assertNull(decoded.todayFirstEventStartEpochMillis)
    }

    @Test
    fun commuteSnapshotJson_routedOverEarlierRoundTrips() {
        val snapshot = CommuteSnapshot(
            direction = Direction.TO_WORK,
            durationSeconds = 1200L,
            durationNoTrafficSeconds = 1000L,
            distanceMeters = 8000L,
            mapImagePath = null,
            fetchedAtEpochMillis = 1_700_000_000_000L,
            lastFetchFailed = false,
            lastErrorMessage = null,
            mode = SnapshotMode.CALENDAR_EVENT,
            routedOverEarlier = true,
        )
        val encoded = encodeCommuteSnapshot(snapshot)
        assertEquals(true, decodeCommuteSnapshot(encoded)?.routedOverEarlier)
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
    fun customPillsJson_roundTripWithMultipleSlotsAndDaySets() {
        val pills = listOf(
            CustomPill(
                id = "0d2b5812-04b8-4646-b9c4-b47e2c926b6d",
                name = "Vitamin D",
                slotsMinutesOfDay = listOf(480, 1_200),
                days = setOf(1, 3, 5),
            ),
            CustomPill(
                id = "3a79f428-911b-47c3-9797-a4c3ab784565",
                name = "Water",
                slotsMinutesOfDay = listOf(600, 780, 960, 1_140),
                days = setOf(2, 4, 6, 7),
            ),
        )

        assertEquals(pills, decodeCustomPills(encodeCustomPills(pills)))
    }

    @Test
    fun customPillsJson_emptyListRoundTrips() {
        assertEquals(emptyList<CustomPill>(), decodeCustomPills(encodeCustomPills(emptyList())))
    }

    @Test
    fun customPillsJson_corruptOrWrongTypeReturnsEmptyList() {
        assertEquals(emptyList<CustomPill>(), decodeCustomPills("{not json}"))
        assertEquals(emptyList<CustomPill>(), decodeCustomPills("""{"id":"not-a-list"}"""))
        assertEquals(emptyList<CustomPill>(), decodeCustomPills("""[{"id":1}]"""))
        assertEquals(emptyList<CustomPill>(), decodeCustomPills(null))
        assertEquals(emptyList<CustomPill>(), decodeCustomPills(""))
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

    @Test
    fun appSettings_eventLeaveByFieldsDefaultOnEmptyStore() {
        val settings = AppSettings()
        assertEquals(10, settings.eventLeaveByBufferMinutes)
        assertEquals(60, settings.eventRealtimeThresholdMinutes)
    }

    @Test
    fun appSettings_customPillFieldsDefaultOnEmptyStore() {
        val settings = AppSettings()
        assertEquals(emptyList<CustomPill>(), settings.customPills)
        assertEquals(60, settings.customPillActiveWindowMinutes)
    }

    /**
     * v5 renames `historyDays` to `commuteDays` but keeps the DataStore key literal
     * ("history_days_json", see [com.crpakala.commutewidget.data.SettingsRepository]'s
     * `PreferenceKeys.HISTORY_DAYS_JSON`) and the [encodeIntSet]/[decodeIntSet] wire format
     * completely unchanged, so a pre-v5 device's stored set decodes exactly as before under the
     * new field name - only the Kotlin-side name changed, not a single byte on disk.
     */
    @Test
    fun commuteDays_decodesAPreV5EncodedIntSetIdentically() {
        val preV5StoredValue = encodeIntSet(setOf(1, 2, 3, 4, 5))

        val decoded = decodeIntSet(preV5StoredValue, default = emptySet())

        assertEquals(setOf(1, 2, 3, 4, 5), decoded)
    }

    @Test
    fun commuteDays_defaultsToWeekdaysOnEmptyStore() {
        assertEquals(setOf(1, 2, 3, 4, 5), AppSettings().commuteDays)
    }

    @Test
    fun eventIdentityKey_trimsTitle() {
        assertEquals(
            "1756257000000|Client meeting",
            eventIdentityKey(1_756_257_000_000L, "  Client meeting  "),
        )
    }

    @Test
    fun eventIdentityKey_emptyTitle() {
        assertEquals("1756257000000|", eventIdentityKey(1_756_257_000_000L, ""))
        assertEquals("1756257000000|", eventIdentityKey(1_756_257_000_000L, "   "))
    }

    @Test
    fun eventIdentityKey_isDeterministic() {
        val first = eventIdentityKey(1_756_257_000_000L, "Client meeting")
        val second = eventIdentityKey(1_756_257_000_000L, "Client meeting")
        assertEquals(first, second)
        assertEquals("1756257000000|Client meeting", first)
    }

    @Test
    fun commuteSnapshotJson_currentPreHealthFormatDecodesWithHealthFieldDefaults() {
        val currentJson = """
            {
              "direction": "TO_WORK",
              "durationSeconds": 1200,
              "durationNoTrafficSeconds": 1000,
              "distanceMeters": 8000,
              "mapImagePath": "/cache/commute-map.png",
              "fetchedAtEpochMillis": 1700000000000,
              "lastFetchFailed": false,
              "lastErrorMessage": null,
              "destinationLabel": "Work",
              "destinationLat": 12.9716,
              "destinationLng": 77.5946,
              "leaveByMinuteOfDay": 540,
              "mode": "COMMUTE",
              "eventStartEpochMillis": null,
              "nextWindowLabel": null,
              "nextWindowStartMinuteOfDay": null,
              "routedOverEarlier": false
            }
        """.trimIndent()

        val decoded = decodeCommuteSnapshot(currentJson)
        requireNotNull(decoded)
        assertEquals(emptyList<HealthNudge>(), decoded.healthNudges)
        assertNull(decoded.sleepEstimateMinutes)
        assertFalse(decoded.shortSleepDay)
    }

    /**
     * Custom pill reminders add `customPillOccurrences` to [CommuteSnapshot]. A pre-custom-pill
     * stored snapshot JSON (predating the field) must still decode, with the field defaulting to
     * empty rather than failing to parse.
     */
    @Test
    fun commuteSnapshotJson_preCustomPillFormatDecodesWithCustomPillFieldDefaults() {
        val preCustomPillJson = """
            {
              "direction": "TO_WORK",
              "durationSeconds": 1200,
              "durationNoTrafficSeconds": 1000,
              "distanceMeters": 8000,
              "mapImagePath": "/cache/commute-map.png",
              "fetchedAtEpochMillis": 1700000000000,
              "lastFetchFailed": false,
              "lastErrorMessage": null,
              "destinationLabel": "Work",
              "destinationLat": 12.9716,
              "destinationLng": 77.5946,
              "leaveByMinuteOfDay": 540,
              "mode": "COMMUTE",
              "eventStartEpochMillis": null,
              "nextWindowLabel": null,
              "nextWindowStartMinuteOfDay": null,
              "routedOverEarlier": false,
              "healthNudges": [],
              "sleepEstimateMinutes": 390,
              "shortSleepDay": false
            }
        """.trimIndent()

        val decoded = decodeCommuteSnapshot(preCustomPillJson)
        requireNotNull(decoded)
        assertEquals(390, decoded.sleepEstimateMinutes)
        assertEquals(emptyList<CustomPillOccurrence>(), decoded.customPillOccurrences)
    }

    /**
     * The short-lived `customPillOverflowCount` field (removed before release when the overflow
     * became render-derived) must be IGNORED, not fatal, if a stored snapshot ever carried it.
     */
    @Test
    fun commuteSnapshotJson_staleOverflowCountFieldIsIgnored() {
        val withOverflowJson = """
            {
              "direction": "TO_WORK",
              "durationSeconds": 1200,
              "durationNoTrafficSeconds": 1000,
              "distanceMeters": 8000,
              "mapImagePath": null,
              "fetchedAtEpochMillis": 1700000000000,
              "lastFetchFailed": false,
              "lastErrorMessage": null,
              "customPillOccurrences": [],
              "customPillOverflowCount": 2
            }
        """.trimIndent()

        val decoded = decodeCommuteSnapshot(withOverflowJson)
        requireNotNull(decoded)
        assertEquals(emptyList<CustomPillOccurrence>(), decoded.customPillOccurrences)
    }

    @Test
    fun commuteSnapshotJson_customPillOccurrencesRoundTrip() {
        val snapshot = CommuteSnapshot(
            direction = Direction.TO_WORK,
            durationSeconds = 1200L,
            durationNoTrafficSeconds = 1000L,
            distanceMeters = 8000L,
            mapImagePath = null,
            fetchedAtEpochMillis = 1_700_000_000_000L,
            lastFetchFailed = false,
            lastErrorMessage = null,
            customPillOccurrences = listOf(
                CustomPillOccurrence(pillId = "0d2b5812-04b8-4646-b9c4-b47e2c926b6d", label = "Vitamin D", slotMinuteOfDay = 480, active = true),
                CustomPillOccurrence(pillId = "3a79f428-911b-47c3-9797-a4c3ab784565", label = "Water", slotMinuteOfDay = 600, active = false),
            ),
        )

        assertEquals(snapshot, decodeCommuteSnapshot(encodeCommuteSnapshot(snapshot)))
    }

    @Test
    fun commuteSnapshotJson_healthNudgesRoundTrip() {
        val snapshot = CommuteSnapshot(
            direction = Direction.TO_HOME,
            durationSeconds = 1200L,
            durationNoTrafficSeconds = 1000L,
            distanceMeters = 8000L,
            mapImagePath = null,
            fetchedAtEpochMillis = 1_700_000_000_000L,
            lastFetchFailed = false,
            lastErrorMessage = null,
            healthNudges = listOf(
                HealthNudge(
                    kind = HealthNudgeKind.WATER,
                    label = "Water",
                    startMinuteOfDay = 600,
                    endMinuteOfDay = 720,
                    targetMinuteOfDay = 660,
                    demoted = true,
                ),
            ),
            sleepEstimateMinutes = 390,
            shortSleepDay = true,
        )

        assertEquals(snapshot, decodeCommuteSnapshot(encodeCommuteSnapshot(snapshot)))
    }

    @Test
    fun commuteSnapshotJson_sleepTapNudgeKindsRoundTrip() {
        val snapshot = CommuteSnapshot(
            direction = Direction.TO_HOME,
            durationSeconds = 1200L,
            durationNoTrafficSeconds = 1000L,
            distanceMeters = 8000L,
            mapImagePath = null,
            fetchedAtEpochMillis = 1_700_000_000_000L,
            lastFetchFailed = false,
            lastErrorMessage = null,
            healthNudges = listOf(
                HealthNudge(
                    kind = HealthNudgeKind.SLEEP_TO_BED,
                    label = "To bed",
                    startMinuteOfDay = 1260,
                    endMinuteOfDay = 1440,
                ),
                HealthNudge(
                    kind = HealthNudgeKind.SLEEP_WOKE_UP,
                    label = "Woke up",
                    startMinuteOfDay = 270,
                    endMinuteOfDay = 600,
                ),
            ),
        )

        assertEquals(snapshot, decodeCommuteSnapshot(encodeCommuteSnapshot(snapshot)))
    }

    @Test
    fun healthDayStateJson_roundTrip() {
        val state = HealthDayState(
            date = "2026-08-31",
            morningSupplementsTakenMinute = 450,
            proteinTakenMinute = 1140,
            waterTapMinutes = listOf(540, 720),
            waterSlotPlanMinutes = listOf(540, 720, 900),
            walkDismissed = true,
            dismissedFocusGapStartMinutes = listOf(840),
            gymDetected = true,
            waterPulseShownMinute = 1200,
            customPillTakenSlots = setOf(
                "0d2b5812-04b8-4646-b9c4-b47e2c926b6d:480",
                "0d2b5812-04b8-4646-b9c4-b47e2c926b6d:1200",
            ),
        )

        assertEquals(state, decodeHealthDayState(encodeHealthDayState(state)))
    }

    @Test
    fun healthHistoryJson_roundTrip() {
        val history = HealthHistory(
            days = listOf(
                HealthDayRecord(date = "2026-08-30", steps = 7000, sleepMinutes = 420),
                HealthDayRecord(date = "2026-08-31", overnightUnlockCount = 3),
            ),
        )

        assertEquals(history, decodeHealthHistory(encodeHealthHistory(history)))
    }

    @Test
    fun healthJson_invalidBlankAndNullReturnNull() {
        assertNull(decodeHealthDayState("{not json}"))
        assertNull(decodeHealthDayState(""))
        assertNull(decodeHealthDayState(null))
        assertNull(decodeHealthHistory("{not json}"))
        assertNull(decodeHealthHistory(" "))
        assertNull(decodeHealthHistory(null))
    }

    @Test
    fun healthDayStateJson_oldFormatDecodesWithNewFieldDefaults() {
        val oldJson = """
            {
              "date": "2026-08-31",
              "morningSupplementsTakenMinute": 450,
              "proteinTakenMinute": null,
              "waterTapMinutes": [540],
              "waterSlotPlanMinutes": [540, 720],
              "walkDismissed": false,
              "dismissedFocusGapStartMinutes": [],
              "gymDetected": true
            }
        """.trimIndent()

        val decoded = decodeHealthDayState(oldJson)
        requireNotNull(decoded)
        assertEquals("2026-08-31", decoded.date)
        assertEquals(450, decoded.morningSupplementsTakenMinute)
        assertEquals(listOf(540), decoded.waterTapMinutes)
        assertEquals(listOf(540, 720), decoded.waterSlotPlanMinutes)
        assertFalse(decoded.walkDismissed)
        assertEquals(emptyList<Int>(), decoded.dismissedFocusGapStartMinutes)
        assertEquals(true, decoded.gymDetected)
        assertNull(decoded.waterPulseShownMinute)
    }

    /**
     * Sprint 2 adds `audibleLastPlayingMinute` and `walkNotified` to [HealthDayState]. A
     * pre-Sprint-2 stored day-state JSON (predating both fields) must still decode, with both new
     * fields defaulting rather than failing to parse - see [HealthDayState]'s field docs.
     */
    @Test
    fun healthDayStateJson_preSprint2FormatDecodesWithLatchAndWalkNotifiedDefaults() {
        val preSprint2Json = """
            {
              "date": "2026-08-31",
              "morningSupplementsTakenMinute": null,
              "proteinTakenMinute": null,
              "waterTapMinutes": [],
              "waterSlotPlanMinutes": [450, 630, 810, 990, 1170],
              "walkDismissed": false,
              "dismissedFocusGapStartMinutes": [],
              "gymDetected": false,
              "waterPulseShownMinute": null
            }
        """.trimIndent()

        val decoded = decodeHealthDayState(preSprint2Json)
        requireNotNull(decoded)
        assertEquals("2026-08-31", decoded.date)
        assertNull(decoded.audibleLastPlayingMinute)
        assertFalse(decoded.walkNotified)
    }

    @Test
    fun healthDayStateJson_audibleLatchAndWalkNotifiedRoundTrip() {
        val state = HealthDayState(
            date = "2026-08-31",
            audibleLastPlayingMinute = 1_050,
            walkNotified = true,
        )

        val decoded = decodeHealthDayState(encodeHealthDayState(state))
        requireNotNull(decoded)
        assertEquals(1_050, decoded.audibleLastPlayingMinute)
        assertTrue(decoded.walkNotified)
    }

    /**
     * The 2026-08-31 owner request adds `sleepPillDismissed` and `morningLightDismissed`. A
     * stored day-state JSON predating both fields must decode with both defaulting to false.
     */
    @Test
    fun healthDayStateJson_preInfoPillFormatDecodesWithDismissalDefaults() {
        val preInfoPillJson = """
            {
              "date": "2026-08-31",
              "waterTapMinutes": [540],
              "waterSlotPlanMinutes": [450, 630],
              "audibleLastPlayingMinute": 1050,
              "walkNotified": true
            }
        """.trimIndent()

        val decoded = decodeHealthDayState(preInfoPillJson)
        requireNotNull(decoded)
        assertEquals("2026-08-31", decoded.date)
        assertFalse(decoded.sleepPillDismissed)
        assertFalse(decoded.morningLightDismissed)
        assertEquals(emptySet<String>(), decoded.customPillTakenSlots)
    }

    /**
     * Sprint 2 adds `sleepStartEpochMillis` to [HealthDayRecord]. A pre-Sprint-2 stored history
     * JSON (predating the field) must still decode, with the new field defaulting to null.
     */
    @Test
    fun healthHistoryJson_preSprint2FormatDecodesWithSleepStartEpochMillisDefault() {
        val preSprint2Json = """
            {
              "days": [
                {"date": "2026-08-30", "steps": 7000, "sleepMinutes": 420, "overnightUnlockCount": 1}
              ]
            }
        """.trimIndent()

        val decoded = decodeHealthHistory(preSprint2Json)
        requireNotNull(decoded)
        assertNull(decoded.days.single().sleepStartEpochMillis)
    }

    @Test
    fun healthHistoryJson_sleepStartEpochMillisRoundTrips() {
        val history = HealthHistory(
            days = listOf(
                HealthDayRecord(date = "2026-08-30", sleepMinutes = 420, sleepStartEpochMillis = 1_700_000_000_000L),
            ),
        )

        val decoded = decodeHealthHistory(encodeHealthHistory(history))
        requireNotNull(decoded)
        assertEquals(1_700_000_000_000L, decoded.days.single().sleepStartEpochMillis)
    }

    @Test
    fun healthHistory_prunedAndUpsertedReplacesAndKeepsNewestFourteen() {
        val unsortedHistory = HealthHistory(
            days = (1..15).reversed().map { day ->
                HealthDayRecord(date = "2026-08-${day.toString().padStart(2, '0')}", steps = day)
            },
        )

        val updated = unsortedHistory.prunedAndUpserted(
            HealthDayRecord(date = "2026-08-15", steps = 15_000),
        )

        assertEquals(14, updated.days.size)
        assertEquals("2026-08-02", updated.days.first().date)
        assertEquals("2026-08-15", updated.days.last().date)
        assertEquals(15_000, updated.days.last().steps)
    }
}
