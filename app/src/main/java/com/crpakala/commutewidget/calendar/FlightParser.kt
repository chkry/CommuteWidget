package com.crpakala.commutewidget.calendar

/**
 * Detects a Gmail-auto-added flight event and extracts its fields from raw calendar row text.
 * Pure and defensive: never throws, and returns null for anything that isn't recognisably a
 * flight. A bare designator is never enough on its own: it must be flight-anchored (see
 * [findDesignator]) and the text must also carry a cue word (see [CUE_REGEX]), so a hotel
 * reservation carrying a confirmation number and a "Check-in at 3 pm" line cannot take the widget.
 */
object FlightParser {
    fun parse(
        eventId: Long,
        calendarId: Long,
        title: String?,
        location: String?,
        description: String?,
        beginMillis: Long,
        endMillis: Long,
        allDay: Boolean,
    ): FlightEvent? = runCatching {
        if (allDay) return@runCatching null

        val titleText = normalizeSourceText(title)
        val cleanDescription = normalizeSourceText(description)
        val cleanLocation = normalizeSourceText(location)

        val designatorMatch = findDesignator(titleText, cleanDescription) ?: return@runCatching null

        if (!CUE_REGEX.containsMatchIn(titleText) && !CUE_REGEX.containsMatchIn(cleanDescription)) {
            return@runCatching null
        }

        val airlineCode = designatorMatch.groupValues[1].uppercase()
        val flightNumber = designatorMatch.groupValues[2]

        var departureAirportName: String? = null
        var departureIata: String? = null
        var arrivalAirportName: String? = null
        var arrivalIata: String? = null
        var departureLocalHm: String? = null
        var arrivalLocalHm: String? = null

        val routeWithNames = ROUTE_WITH_NAMES_REGEX.find(cleanDescription)
            ?: ROUTE_WITH_BARE_CODES_REGEX.find(cleanDescription)
        if (routeWithNames != null) {
            departureAirportName = routeWithNames.groupValues[1].trim().takeIf { it.isNotEmpty() }
            departureIata = routeWithNames.groupValues[2]
            departureLocalHm = routeWithNames.groupValues[3].takeIf { it.isNotEmpty() }
            arrivalAirportName = routeWithNames.groupValues[4].trim().takeIf { it.isNotEmpty() }
            arrivalIata = routeWithNames.groupValues[5]
            arrivalLocalHm = routeWithNames.groupValues[6].takeIf { it.isNotEmpty() }
        } else {
            val routeBare = ROUTE_BARE_REGEX.find(cleanDescription)
            if (routeBare != null) {
                departureIata = routeBare.groupValues[1]
                arrivalIata = routeBare.groupValues[2]
            }
        }

        val locationAirport = LOCATION_AIRPORT_CODE_REGEX.find(cleanLocation)
            ?: LOCATION_BARE_CODE_REGEX.find(cleanLocation)
        if (locationAirport != null) {
            departureAirportName = locationAirport.groupValues[1].trim().takeIf { it.isNotEmpty() }
            departureIata = locationAirport.groupValues[2]
        } else if (cleanLocation.contains("airport", ignoreCase = true)) {
            departureAirportName = cleanLocation.trim().takeIf { it.isNotEmpty() }
        }

        if (arrivalAirportName == null && arrivalIata == null) {
            TITLE_ARRIVAL_REGEX.find(titleText)?.let { match ->
                arrivalAirportName = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
            }
        }

        val confirmationNumber = findConfirmationNumber(cleanDescription)
        val seat = SEAT_REGEX.find(cleanDescription)?.groupValues?.get(1)?.uppercase()

        val terminalMatches = TERMINAL_REGEX.findAll(cleanDescription).toList()
        val departureTerminal = terminalMatches.getOrNull(0)?.groupValues?.get(1)
        val arrivalTerminal = terminalMatches.getOrNull(1)?.groupValues?.get(1)

        FlightEvent(
            eventId = eventId,
            calendarId = calendarId,
            title = title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Flight",
            airlineCode = airlineCode,
            flightNumber = flightNumber,
            departureMillis = beginMillis,
            arrivalMillis = endMillis,
            locationText = location,
            departureAirportName = departureAirportName,
            departureIata = departureIata,
            arrivalAirportName = arrivalAirportName,
            arrivalIata = arrivalIata,
            confirmationNumber = confirmationNumber,
            seat = seat,
            departureTerminal = departureTerminal,
            arrivalTerminal = arrivalTerminal,
            departureLocalHm = departureLocalHm,
            arrivalLocalHm = arrivalLocalHm,
        )
    }.getOrNull()

    private val HTML_TAG_REGEX = Regex("<[^>]*>")

    /**
     * Any HTML character reference, named or numeric. A Gmail-written description is HTML, and the
     * one that reaches the calendar provider keeps its references; the label of the owner's own
     * event is "Confirmation" and "number" joined by a non-breaking space, which is what made
     * [CONFIRMATION_REGEX]'s two-word label fail to match at all. Nothing this parser extracts
     * (a code, a designator, a time) can contain a character reference, so replacing every one
     * with a space is both safe and enough.
     */
    private val HTML_ENTITY_REGEX = Regex(
        """&(?:#[0-9]{1,6}|#[Xx][0-9A-Fa-f]{1,5}|[A-Za-z][A-Za-z0-9]{1,9});""",
    )

    /**
     * Calendar text as the rest of this object expects it: no HTML tags, no character references,
     * and every space that is not a line break flattened to a plain ASCII one. That last step is
     * the important one - Java's `\s` covers ASCII whitespace only, so a non-breaking space
     * anywhere in a label or between a label and its value silently defeats every regex below.
     * Line breaks survive because [findDesignator] reads the description a line at a time.
     */
    private fun normalizeSourceText(raw: String?): String {
        if (raw.isNullOrEmpty()) {
            return ""
        }
        val stripped = HTML_ENTITY_REGEX.replace(HTML_TAG_REGEX.replace(raw, " "), " ")
        return buildString(stripped.length) {
            for (ch in stripped) {
                append(if (ch != '\n' && ch != '\r' && ch.isWhitespace()) ' ' else ch)
            }
        }
    }

    /**
     * The one place a designator is allowed to come from. A bare 2-char-plus-digits token is far
     * too common in ordinary calendar text ("Q1 2026", "T2 4", "FY 2026"), so a match counts only
     * when it is anchored to a flight: inside a parenthesised group in the title ("Flight to
     * Sydney (QF 401)"), immediately after the word flight/flt in either field ("Flight: QF401"),
     * or in a description that also carries a route pair ("Qantas QF 401" above "(MEL) - (SYD)").
     * Airline codes are matched upper-case only, which is how every itinerary mail writes them.
     * The title group is read first, so a description that names the number without the airline
     * ("Singapore Air flight 509") still takes its airline code from the title's "(SQ 509)".
     */
    private fun findDesignator(titleText: String, cleanDescription: String): MatchResult? {
        for (group in TITLE_PAREN_GROUP_REGEX.findAll(titleText)) {
            DESIGNATOR_REGEX.find(group.groupValues[1])?.let { return it }
        }
        FLIGHT_LABELLED_DESIGNATOR_REGEX.find(titleText)?.let { return it }
        FLIGHT_LABELLED_DESIGNATOR_REGEX.find(cleanDescription)?.let { return it }
        if (ROUTE_WITH_NAMES_REGEX.containsMatchIn(cleanDescription) ||
            ROUTE_WITH_BARE_CODES_REGEX.containsMatchIn(cleanDescription) ||
            ROUTE_BARE_REGEX.containsMatchIn(cleanDescription)
        ) {
            for (line in cleanDescription.lineSequence()) {
                DESIGNATOR_REGEX.find(line)?.let { return it }
            }
        }
        return null
    }

    /**
     * The confirmation number, or null when the description carries none that reads like one.
     * A value is only accepted when it carries a digit or is written entirely in upper case, which
     * is true of every airline record locator and false of the ordinary English word that follows
     * a bare label ("confirmation to follow").
     */
    private fun findConfirmationNumber(cleanDescription: String): String? = CONFIRMATION_REGEX
        .findAll(cleanDescription)
        .map { it.groupValues[1] }
        .firstOrNull { code -> code.any { it.isDigit() } || code.none { it.isLowerCase() } }
        ?.uppercase()

    /** A 2-char IATA airline code (at least one letter, upper case) plus a 1-4 digit flight number. */
    private val DESIGNATOR_REGEX = Regex(
        """\b([A-Z][A-Z0-9]|[0-9][A-Z]) ?(\d{1,4})\b""",
    )

    private val TITLE_PAREN_GROUP_REGEX = Regex("""\(([^)]*)\)""")

    /** [DESIGNATOR_REGEX] preceded by flight/flt and an optional number label; only the label half is case-insensitive. */
    private val FLIGHT_LABELLED_DESIGNATOR_REGEX = Regex(
        """(?i:\b(?:flight|flt)\b[ \t]*(?:number|no\.?|#)?[ \t]*:?[ \t]*)([A-Z][A-Z0-9]|[0-9][A-Z]) ?(\d{1,4})\b""",
    )

    /** Word-bounded so "gate" cannot match "delegate" and "confirmation" needs its own word. */
    private val CUE_REGEX = Regex(
        """(?i)\b(?:flight|departs|departure|boarding|airport|confirmation|""" +
            """booking reference|record locator|pnr|terminal|gate)\b""",
    )

    /**
     * e.g. "Melbourne (MEL) - Sydney (SYD)", and the timed "Bengaluru (BLR) 11:35 (local time) -
     * Singapore (SIN) 19:00 (local time)". Groups 3 and 6 are the two printed clock times, which
     * are the only airport-local times a calendar-only card ever has; both are optional, and the
     * group numbering is deliberately identical to [ROUTE_WITH_BARE_CODES_REGEX] so one branch can
     * read either match.
     */
    private val ROUTE_WITH_NAMES_REGEX = Regex(
        """([A-Za-z][A-Za-z .]*?)\s*\(([A-Z]{3})\)(?:\s+(\d{1,2}:\d{2}))?(?:\s*\([^)\n]{0,24}\))?""" +
            """\s*-\s*([A-Za-z][A-Za-z .]*?)\s*\(([A-Z]{3})\)(?:\s+(\d{1,2}:\d{2}))?""",
    )

    /**
     * The unparenthesised itinerary line: "Bengaluru BLR 11:35 (local time) - Singapore SIN 19:00
     * (local time)", and its shorter "Bengaluru BLR - Singapore SIN" form. Only a departure time
     * and one bracketed aside may sit between a code and the dash, and a code is three upper-case
     * letters as a whole word, so neither "11:35" nor "local" can ever be read as one.
     */
    private val ROUTE_WITH_BARE_CODES_REGEX = Regex(
        """([A-Za-z][A-Za-z .]*?)\s+([A-Z]{3})\b(?:\s+(\d{1,2}:\d{2}))?(?:\s*\([^)\n]{0,24}\))?""" +
            """\s*-\s*([A-Za-z][A-Za-z .]*?)\s+([A-Z]{3})\b(?:\s+(\d{1,2}:\d{2}))?""",
    )

    /** e.g. "MEL - SYD" or "MEL to SYD". */
    private val ROUTE_BARE_REGEX = Regex("""\b([A-Z]{3})\s*(?:-|[Tt][Oo])\s*([A-Z]{3})\b""")

    /** e.g. "Melbourne Airport (MEL)". */
    private val LOCATION_AIRPORT_CODE_REGEX = Regex(
        """([A-Za-z][A-Za-z .]*?)\s*\(([A-Z]{3})\)""",
    )

    /**
     * The location Gmail actually writes: "Bengaluru BLR", "Melbourne Airport MEL". The code is
     * the last whole word of the whole field and the name before it carries letters only, so
     * "Room 3 BLR" is a room and not an airport, and "Bengaluru BLRX" yields no code at all.
     */
    private val LOCATION_BARE_CODE_REGEX = Regex(
        """^([A-Za-z][A-Za-z .]*?)\s+([A-Z]{3})\s*$""",
    )

    private val TITLE_ARRIVAL_REGEX = Regex("""(?i)^flight to\s+([^(]+)""")

    /**
     * A confirmation label, the qualifier word that belongs to the label rather than to the value,
     * and the code itself. The qualifier is matched as part of the label so the short
     * "confirmation" form can never capture the word that follows it.
     */
    private val CONFIRMATION_REGEX = Regex(
        """(?i)\b(?:confirmation|booking|reservation|record locator|reference|pnr)\b""" +
            """(?:\s*(?:(?:number|no|code|reference|ref|locator|id)\b\.?|#))?""" +
            """\s*:?\s*([A-Za-z0-9]{5,8})\b""",
    )

    private val SEAT_REGEX = Regex("""(?i)\bseat\b\s*:?\s*(\d{1,3}[A-Ka-k])\b""")

    private val TERMINAL_REGEX = Regex("""(?i)\bterminal\b\s*:?\s*([A-Za-z0-9]{1,3})\b""")
}
