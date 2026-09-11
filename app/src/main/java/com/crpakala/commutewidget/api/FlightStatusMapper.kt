package com.crpakala.commutewidget.api

import com.crpakala.commutewidget.data.AircraftRecord
import com.crpakala.commutewidget.data.AirportDelayStats
import com.crpakala.commutewidget.data.FlightStatus
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val flightStatusJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    coerceInputValues = true
    explicitNulls = false
}

// AirLabs "utc" fields are "yyyy-MM-dd HH:mm" (space separator, no seconds, no timezone letter).
private val UTC_TIMESTAMP_REGEX = Regex("^(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2})$")

object FlightStatusMapper {
    fun fromSchedulesJson(body: String, designator: String, localDate: LocalDate): FlightStatus? {
        val decoded = decode<SchedulesResponseDto>(body) ?: return null
        val elements = decoded.response ?: return null
        val selected = selectScheduleElement(elements, localDate) ?: return null
        return selected.toFlightStatus(designator)
    }

    fun fromFlightJson(body: String, designator: String): FlightStatus? {
        val decoded = decode<FlightResponseDto>(body) ?: return null
        val dto = decoded.response ?: return null
        return dto.toFlightStatus(designator)
    }

    fun fromRoutesJson(body: String, designator: String, localDate: LocalDate, departureIata: String?): FlightStatus? {
        val decoded = decode<RoutesResponseDto>(body) ?: return null
        val elements = decoded.response ?: return null
        val selected = if (departureIata != null) {
            elements.firstOrNull { it.dep_iata?.equals(departureIata, ignoreCase = true) == true }
        } else {
            elements.firstOrNull()
        } ?: return null
        return selected.toFlightStatus(designator, localDate)
    }

    fun merge(schedule: FlightStatus, live: FlightStatus): FlightStatus = FlightStatus(
        designator = schedule.designator,
        status = live.status,
        departureScheduledUtcMillis = live.departureScheduledUtcMillis ?: schedule.departureScheduledUtcMillis,
        departureEstimatedUtcMillis = live.departureEstimatedUtcMillis ?: schedule.departureEstimatedUtcMillis,
        departureTerminal = live.departureTerminal ?: schedule.departureTerminal,
        departureGate = live.departureGate ?: schedule.departureGate,
        departureCheckInDesk = live.departureCheckInDesk ?: schedule.departureCheckInDesk,
        arrivalScheduledUtcMillis = live.arrivalScheduledUtcMillis ?: schedule.arrivalScheduledUtcMillis,
        arrivalEstimatedUtcMillis = live.arrivalEstimatedUtcMillis ?: schedule.arrivalEstimatedUtcMillis,
        arrivalTerminal = live.arrivalTerminal ?: schedule.arrivalTerminal,
        arrivalGate = live.arrivalGate ?: schedule.arrivalGate,
        arrivalBaggageBelt = live.arrivalBaggageBelt ?: schedule.arrivalBaggageBelt,
        aircraftModel = live.aircraftModel ?: schedule.aircraftModel,
        aircraftRegistration = live.aircraftRegistration ?: schedule.aircraftRegistration,
        operatingAirline = live.operatingAirline ?: schedule.operatingAirline,
        codeshareOf = live.codeshareOf ?: schedule.codeshareOf,
        lastUpdatedUtcMillis = live.lastUpdatedUtcMillis ?: schedule.lastUpdatedUtcMillis,
        departureActualUtcMillis = live.departureActualUtcMillis ?: schedule.departureActualUtcMillis,
        arrivalActualUtcMillis = live.arrivalActualUtcMillis ?: schedule.arrivalActualUtcMillis,
        departureDelayedMinutes = live.departureDelayedMinutes ?: schedule.departureDelayedMinutes,
        arrivalDelayedMinutes = live.arrivalDelayedMinutes ?: schedule.arrivalDelayedMinutes,
        durationMinutes = live.durationMinutes ?: schedule.durationMinutes,
        airlineName = live.airlineName ?: schedule.airlineName,
        departureAirportName = live.departureAirportName ?: schedule.departureAirportName,
        departureCity = live.departureCity ?: schedule.departureCity,
        arrivalAirportName = live.arrivalAirportName ?: schedule.arrivalAirportName,
        arrivalCity = live.arrivalCity ?: schedule.arrivalCity,
        departureIata = live.departureIata ?: schedule.departureIata,
        arrivalIata = live.arrivalIata ?: schedule.arrivalIata,
        aircraftIcao = live.aircraftIcao ?: schedule.aircraftIcao,
        percentComplete = live.percentComplete ?: schedule.percentComplete,
        latitude = live.latitude ?: schedule.latitude,
        longitude = live.longitude ?: schedule.longitude,
        altitudeMeters = live.altitudeMeters ?: schedule.altitudeMeters,
        speedKmh = live.speedKmh ?: schedule.speedKmh,
        headingDegrees = live.headingDegrees ?: schedule.headingDegrees,
        source = live.source ?: schedule.source,
        departureUtcOffsetMinutes = live.departureUtcOffsetMinutes ?: schedule.departureUtcOffsetMinutes,
        arrivalUtcOffsetMinutes = live.arrivalUtcOffsetMinutes ?: schedule.arrivalUtcOffsetMinutes,
        departureCountry = live.departureCountry ?: schedule.departureCountry,
        arrivalCountry = live.arrivalCountry ?: schedule.arrivalCountry,
        airlineIata = live.airlineIata ?: schedule.airlineIata,
        airlineIcao = live.airlineIcao ?: schedule.airlineIcao,
        aircraftManufacturer = live.aircraftManufacturer ?: schedule.aircraftManufacturer,
        aircraftSerialNumber = live.aircraftSerialNumber ?: schedule.aircraftSerialNumber,
        aircraftEngineType = live.aircraftEngineType ?: schedule.aircraftEngineType,
        aircraftEngineCount = live.aircraftEngineCount ?: schedule.aircraftEngineCount,
        aircraftBuiltYear = live.aircraftBuiltYear ?: schedule.aircraftBuiltYear,
        aircraftAgeYears = live.aircraftAgeYears ?: schedule.aircraftAgeYears,
        verticalSpeedKmh = live.verticalSpeedKmh ?: schedule.verticalSpeedKmh,
        transponderCode = live.transponderCode ?: schedule.transponderCode,
    )

    /**
     * The one airframe AirLabs holds for [registration]. An empty response is still a record, all
     * detail fields null, so the caller can cache the fact that this plan knows nothing about the
     * airframe and never ask again. Null means the body itself was unusable.
     */
    fun fromFleetJson(body: String, registration: String, fetchedAtMillis: Long): AircraftRecord? {
        val decoded = decode<FleetResponseDto>(body) ?: return null
        val dto = decoded.response?.firstOrNull()
        return AircraftRecord(
            reg = dto?.reg_number ?: registration,
            model = dto?.model,
            manufacturer = dto?.manufacturer,
            typeIcao = dto?.icao,
            typeIata = dto?.iata,
            engineType = dto?.engine,
            engineCount = dto?.engine_count,
            builtYear = dto?.built,
            ageYears = dto?.age,
            serialNumber = dto?.msn.text,
            fetchedAtMillis = fetchedAtMillis,
        )
    }

    /**
     * The departure board's delay rows folded into the three numbers the card shows: every row the
     * endpoint returned counts, including the codeshare duplicates of one operating flight, because
     * that is what the board itself shows the traveller standing in front of it.
     */
    fun fromDelaysJson(body: String, iata: String, fetchedAtMillis: Long): AirportDelayStats? {
        val decoded = decode<DelaysResponseDto>(body) ?: return null
        val elements = decoded.response ?: return null
        val delays = elements.mapNotNull { it.dep_delayed }
        if (delays.isEmpty()) return null
        return AirportDelayStats(
            iata = iata.uppercase(),
            delayedCount = delays.size,
            averageDelayMinutes = (delays.sum().toDouble() / delays.size).roundToInt(),
            maxDelayMinutes = delays.max(),
            fetchedAtMillis = fetchedAtMillis,
        )
    }

    fun errorMessage(body: String): String? = decodeError(body)?.message

    internal fun errorCode(body: String): String? = decodeError(body)?.code
}

private inline fun <reified T> decode(body: String): T? = try {
    flightStatusJson.decodeFromString<T>(body)
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

private fun decodeError(body: String): ErrorDto? = decode<ErrorEnvelopeDto>(body)?.error

private fun selectScheduleElement(elements: List<ScheduleDto>, localDate: LocalDate): ScheduleDto? {
    val targetDate = localDate.toString()
    val windowStartEpochSeconds = localDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    return elements.firstOrNull { element ->
        element.dep_time?.take(10) == targetDate ||
            element.dep_time_ts?.let { abs(it - windowStartEpochSeconds) <= 12 * 3600 } == true
    }
}

internal fun timestampMillis(epochSeconds: Long?, utcString: String?): Long? {
    if (epochSeconds != null) return epochSeconds * 1000L
    return parseTimestamp(utcString)?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
}

private fun parseTimestamp(value: String?): LocalDateTime? {
    val match = value?.trim()?.let { UTC_TIMESTAMP_REGEX.matchEntire(it) } ?: return null
    val values = match.groupValues
    return try {
        LocalDateTime.of(
            values[1].toInt(),
            values[2].toInt(),
            values[3].toInt(),
            values[4].toInt(),
            values[5].toInt(),
        )
    } catch (e: DateTimeException) {
        null
    }
}

/**
 * How far the airport's own clock is ahead of UTC, in minutes, from AirLabs' pair of full
 * "yyyy-MM-dd HH:mm" stamps for the same instant. Both carry the date, so an offset that crosses
 * midnight needs no normalising. Null when either stamp is missing or malformed.
 */
internal fun utcOffsetMinutes(localString: String?, utcString: String?): Int? {
    val local = parseTimestamp(localString) ?: return null
    val utc = parseTimestamp(utcString) ?: return null
    return ChronoUnit.MINUTES.between(utc, local).toInt()
}

/**
 * The same offset from the `routes` endpoint's bare "HH:mm" pair, which carries no date: the raw
 * difference is normalised into the -12h..+14h band real zones live in, exactly as the instant
 * these two strings locate is.
 */
internal fun hmOffsetMinutes(localTimeStr: String?, utcTimeStr: String?): Int? {
    val localTime = parseHm(localTimeStr) ?: return null
    val utcTime = parseHm(utcTimeStr) ?: return null
    return normalizeOffsetMinutes((localTime.toSecondOfDay() - utcTime.toSecondOfDay()) / 60)
}

/** AirLabs sends these as either a JSON string or a JSON number; the card only ever prints them. */
private val JsonPrimitive?.text: String? get() = this?.takeIf { it !is JsonNull }?.content

private fun isCodeshare(flightIata: String?, csFlightIata: String?, designator: String): Boolean =
    csFlightIata != null && flightIata?.equals(designator, ignoreCase = true) != true

private fun ScheduleDto.toFlightStatus(designator: String): FlightStatus = FlightStatus(
    designator = designator,
    status = status ?: "unknown",
    departureScheduledUtcMillis = timestampMillis(dep_time_ts, dep_time_utc),
    departureEstimatedUtcMillis = timestampMillis(dep_estimated_ts, dep_estimated_utc),
    departureTerminal = dep_terminal,
    departureGate = dep_gate,
    departureCheckInDesk = null,
    arrivalScheduledUtcMillis = timestampMillis(arr_time_ts, arr_time_utc),
    arrivalEstimatedUtcMillis = timestampMillis(arr_estimated_ts, arr_estimated_utc),
    arrivalTerminal = arr_terminal,
    arrivalGate = arr_gate,
    arrivalBaggageBelt = arr_baggage,
    aircraftModel = null,
    aircraftRegistration = null,
    operatingAirline = null,
    codeshareOf = if (isCodeshare(flight_iata, cs_flight_iata, designator)) flight_iata else null,
    lastUpdatedUtcMillis = null,
    departureActualUtcMillis = timestampMillis(dep_actual_ts, dep_actual_utc),
    arrivalActualUtcMillis = timestampMillis(arr_actual_ts, arr_actual_utc),
    departureDelayedMinutes = dep_delayed,
    arrivalDelayedMinutes = arr_delayed,
    durationMinutes = duration,
    airlineName = null,
    departureAirportName = null,
    departureCity = null,
    arrivalAirportName = null,
    arrivalCity = null,
    departureIata = dep_iata,
    arrivalIata = arr_iata,
    aircraftIcao = aircraft_icao,
    percentComplete = null,
    latitude = null,
    longitude = null,
    altitudeMeters = null,
    speedKmh = null,
    headingDegrees = null,
    source = "schedules",
    departureUtcOffsetMinutes = utcOffsetMinutes(dep_time, dep_time_utc),
    arrivalUtcOffsetMinutes = utcOffsetMinutes(arr_time, arr_time_utc),
    airlineIata = airline_iata,
)

private fun FlightDto.toFlightStatus(designator: String): FlightStatus = FlightStatus(
    designator = designator,
    status = status ?: "unknown",
    departureScheduledUtcMillis = timestampMillis(dep_time_ts, dep_time_utc),
    departureEstimatedUtcMillis = timestampMillis(dep_estimated_ts, dep_estimated_utc),
    departureTerminal = dep_terminal,
    departureGate = dep_gate,
    departureCheckInDesk = null,
    arrivalScheduledUtcMillis = timestampMillis(arr_time_ts, arr_time_utc),
    arrivalEstimatedUtcMillis = timestampMillis(arr_estimated_ts, arr_estimated_utc),
    arrivalTerminal = arr_terminal,
    arrivalGate = arr_gate,
    arrivalBaggageBelt = arr_baggage,
    aircraftModel = model,
    aircraftRegistration = reg_number,
    operatingAirline = airline_name,
    codeshareOf = if (isCodeshare(flight_iata, cs_flight_iata, designator)) flight_iata else null,
    lastUpdatedUtcMillis = updated?.let { it * 1000L },
    departureActualUtcMillis = timestampMillis(dep_actual_ts, dep_actual_utc),
    arrivalActualUtcMillis = timestampMillis(arr_actual_ts, arr_actual_utc),
    departureDelayedMinutes = dep_delayed,
    arrivalDelayedMinutes = arr_delayed,
    durationMinutes = duration,
    airlineName = airline_name,
    departureAirportName = dep_name,
    departureCity = dep_city,
    arrivalAirportName = arr_name,
    arrivalCity = arr_city,
    departureIata = dep_iata,
    arrivalIata = arr_iata,
    aircraftIcao = aircraft_icao,
    percentComplete = percent,
    latitude = lat,
    longitude = lng,
    altitudeMeters = alt?.roundToInt(),
    speedKmh = speed?.roundToInt(),
    headingDegrees = dir?.roundToInt(),
    source = "flight",
    departureUtcOffsetMinutes = utcOffsetMinutes(dep_time, dep_time_utc),
    arrivalUtcOffsetMinutes = utcOffsetMinutes(arr_time, arr_time_utc),
    departureCountry = dep_country,
    arrivalCountry = arr_country,
    airlineIata = airline_iata,
    airlineIcao = airline_icao,
    aircraftManufacturer = manufacturer,
    aircraftSerialNumber = msn.text,
    aircraftEngineType = engine,
    aircraftEngineCount = engine_count,
    aircraftBuiltYear = built,
    aircraftAgeYears = age,
    verticalSpeedKmh = v_speed?.roundToInt(),
    transponderCode = squawk.text,
)

private fun FlightRouteDto.toFlightStatus(designator: String, localDate: LocalDate): FlightStatus {
    val departureMillis = localAirportInstantMillis(localDate, dep_time, dep_time_utc)
    val arrivalMillis = if (duration != null && departureMillis != null) {
        departureMillis + duration * 60_000L
    } else {
        val raw = localAirportInstantMillis(localDate, arr_time, arr_time_utc)
        if (raw != null && departureMillis != null && raw < departureMillis) raw + 86_400_000L else raw
    }
    val weekday = localDate.dayOfWeek.name.take(3).lowercase()
    val status = if (days != null && weekday !in days) "not scheduled" else "scheduled"
    return FlightStatus(
        designator = designator,
        status = status,
        departureScheduledUtcMillis = departureMillis,
        departureEstimatedUtcMillis = null,
        departureTerminal = dep_terminals?.singleOrNull(),
        departureGate = null,
        departureCheckInDesk = null,
        arrivalScheduledUtcMillis = arrivalMillis,
        arrivalEstimatedUtcMillis = null,
        arrivalTerminal = arr_terminals?.singleOrNull(),
        arrivalGate = null,
        arrivalBaggageBelt = null,
        aircraftModel = null,
        aircraftRegistration = null,
        operatingAirline = null,
        codeshareOf = if (isCodeshare(flight_iata, cs_flight_iata, designator)) flight_iata else null,
        lastUpdatedUtcMillis = updated?.let(::parseIsoInstantMillis),
        departureActualUtcMillis = null,
        arrivalActualUtcMillis = null,
        departureDelayedMinutes = null,
        arrivalDelayedMinutes = null,
        durationMinutes = duration,
        airlineName = null,
        departureAirportName = null,
        departureCity = null,
        arrivalAirportName = null,
        arrivalCity = null,
        departureIata = dep_iata,
        arrivalIata = arr_iata,
        aircraftIcao = aircraft_icao,
        percentComplete = null,
        latitude = null,
        longitude = null,
        altitudeMeters = null,
        speedKmh = null,
        headingDegrees = null,
        source = "routes",
        departureUtcOffsetMinutes = hmOffsetMinutes(dep_time, dep_time_utc),
        arrivalUtcOffsetMinutes = hmOffsetMinutes(arr_time, arr_time_utc),
    )
}

/** [localTimeStr]/[utcTimeStr] are "HH:mm" wall times with no date; the offset between them locates [localTimeStr] on [localDate] in UTC. */
private fun localAirportInstantMillis(localDate: LocalDate, localTimeStr: String?, utcTimeStr: String?): Long? {
    val localTime = parseHm(localTimeStr) ?: return null
    val offsetMinutes = hmOffsetMinutes(localTimeStr, utcTimeStr) ?: return null
    return try {
        LocalDateTime.of(localDate, localTime).minusMinutes(offsetMinutes.toLong()).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (e: DateTimeException) {
        null
    }
}

private fun normalizeOffsetMinutes(rawMinutes: Int): Int = when {
    rawMinutes > 14 * 60 -> rawMinutes - 24 * 60
    rawMinutes < -12 * 60 -> rawMinutes + 24 * 60
    else -> rawMinutes
}

private fun parseHm(value: String?): LocalTime? = try {
    if (value == null) null else LocalTime.parse(value)
} catch (e: DateTimeException) {
    null
}

private fun parseIsoInstantMillis(value: String): Long? = try {
    Instant.parse(value).toEpochMilli()
} catch (e: DateTimeException) {
    null
}

@Serializable
private data class SchedulesResponseDto(val response: List<ScheduleDto>? = null)

@Serializable
private data class RoutesResponseDto(val response: List<FlightRouteDto>? = null)

@Serializable
private data class FlightResponseDto(val response: FlightDto? = null)

@Serializable
private data class ErrorEnvelopeDto(val error: ErrorDto? = null)

@Serializable
private data class ErrorDto(val message: String? = null, val code: String? = null)

@Serializable
internal data class ScheduleDto(
    val airline_iata: String? = null,
    val flight_iata: String? = null,
    val flight_number: String? = null,
    val dep_iata: String? = null,
    val dep_terminal: String? = null,
    val dep_gate: String? = null,
    val dep_time: String? = null,
    val dep_time_utc: String? = null,
    val dep_time_ts: Long? = null,
    val dep_estimated_utc: String? = null,
    val dep_estimated_ts: Long? = null,
    val dep_actual_utc: String? = null,
    val dep_actual_ts: Long? = null,
    val arr_iata: String? = null,
    val arr_terminal: String? = null,
    val arr_gate: String? = null,
    val arr_baggage: String? = null,
    val arr_time: String? = null,
    val arr_time_utc: String? = null,
    val arr_time_ts: Long? = null,
    val arr_estimated_utc: String? = null,
    val arr_estimated_ts: Long? = null,
    val arr_actual_utc: String? = null,
    val arr_actual_ts: Long? = null,
    val cs_airline_iata: String? = null,
    val cs_flight_iata: String? = null,
    val cs_flight_number: String? = null,
    val status: String? = null,
    val duration: Int? = null,
    val dep_delayed: Int? = null,
    val arr_delayed: Int? = null,
    val aircraft_icao: String? = null,
)

@Serializable
internal data class FlightDto(
    val flight_iata: String? = null,
    val status: String? = null,
    val dep_iata: String? = null,
    val dep_terminal: String? = null,
    val dep_gate: String? = null,
    val dep_time: String? = null,
    val dep_time_utc: String? = null,
    val dep_time_ts: Long? = null,
    val dep_estimated_utc: String? = null,
    val dep_estimated_ts: Long? = null,
    val dep_actual_utc: String? = null,
    val dep_actual_ts: Long? = null,
    val arr_iata: String? = null,
    val arr_terminal: String? = null,
    val arr_gate: String? = null,
    val arr_baggage: String? = null,
    val arr_time: String? = null,
    val arr_time_utc: String? = null,
    val arr_time_ts: Long? = null,
    val arr_estimated_utc: String? = null,
    val arr_estimated_ts: Long? = null,
    val arr_actual_utc: String? = null,
    val arr_actual_ts: Long? = null,
    val cs_flight_iata: String? = null,
    val reg_number: String? = null,
    val duration: Int? = null,
    val dep_delayed: Int? = null,
    val arr_delayed: Int? = null,
    val updated: Long? = null,
    val dep_name: String? = null,
    val dep_city: String? = null,
    val arr_name: String? = null,
    val arr_city: String? = null,
    val airline_name: String? = null,
    val percent: Int? = null,
    val aircraft_icao: String? = null,
    val model: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val alt: Double? = null,
    val speed: Double? = null,
    val v_speed: Double? = null,
    val dir: Double? = null,
    val dep_country: String? = null,
    val arr_country: String? = null,
    val airline_iata: String? = null,
    val airline_icao: String? = null,
    val manufacturer: String? = null,
    val engine: String? = null,
    val engine_count: Int? = null,
    val built: Int? = null,
    val age: Int? = null,
    // AirLabs sends the serial and the squawk as a bare string on some airframes and as a number on
    // others; a strict String? would drop the whole flight response over one field the card only
    // uses as text.
    val msn: JsonPrimitive? = null,
    val squawk: JsonPrimitive? = null,
)

@Serializable
private data class FleetResponseDto(val response: List<FleetDto>? = null)

@Serializable
internal data class FleetDto(
    val reg_number: String? = null,
    val icao: String? = null,
    val iata: String? = null,
    val model: String? = null,
    val manufacturer: String? = null,
    val engine: String? = null,
    val engine_count: Int? = null,
    val built: Int? = null,
    val age: Int? = null,
    val msn: JsonPrimitive? = null,
)

@Serializable
private data class DelaysResponseDto(val response: List<DelayRowDto>? = null)

@Serializable
internal data class DelayRowDto(val dep_delayed: Int? = null)

@Serializable
internal data class FlightRouteDto(
    val flight_iata: String? = null,
    val cs_flight_iata: String? = null,
    val dep_iata: String? = null,
    val dep_terminals: List<String>? = null,
    val dep_time: String? = null,
    val dep_time_utc: String? = null,
    val arr_iata: String? = null,
    val arr_terminals: List<String>? = null,
    val arr_time: String? = null,
    val arr_time_utc: String? = null,
    val duration: Int? = null,
    val aircraft_icao: String? = null,
    val updated: String? = null,
    val days: List<String>? = null,
)
