package com.crpakala.commutewidget

import android.icu.util.TimeZone
import java.time.DateTimeException
import java.time.ZoneId

/**
 * Every zone ICU lists for [countryCode] - the two-letter code AirLabs' flight endpoint sends as
 * `dep_country` and `arr_country` - as java.time zones, for [zoneLabel] to pick from.
 *
 * AirLabs never sends a zone, only an offset, and an offset cannot be named on its own: +8 is SGT
 * in Singapore, AWST in Perth and CST in Shanghai. The country narrows it to a handful of zones and
 * the offset picks the one in force on the day, which is the whole of the resolution.
 *
 * This is the one function in the pass with no unit test, and the reason the rest take their
 * candidates as a parameter: android.icu does not exist on the JVM the tests run on. Nothing is
 * cached because there is nothing worth caching - ICU answers from a table it already holds, and a
 * country has at most a few dozen zones.
 */
internal fun countryZoneCandidates(countryCode: String?): List<ZoneId> {
    if (countryCode == null || countryCode.length != 2) {
        return emptyList()
    }
    return TimeZone.getAvailableIDs(countryCode.uppercase()).mapNotNull { id ->
        // ICU carries a few ids tzdb has since retired; one of them must not take the card down.
        try {
            ZoneId.of(id)
        } catch (e: DateTimeException) {
            null
        }
    }
}
