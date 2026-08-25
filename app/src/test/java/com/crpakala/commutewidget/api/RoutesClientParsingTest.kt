package com.crpakala.commutewidget.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesClientParsingTest {
    private fun assertSuccess(result: ApiResult<RouteResult>): RouteResult {
        if (result !is ApiResult.Success) {
            throw AssertionError("expected Success but was $result")
        }
        return result.value
    }

    private fun assertFailure(result: ApiResult<RouteResult>): ApiResult.Failure {
        if (result !is ApiResult.Failure) {
            throw AssertionError("expected Failure but was $result")
        }
        return result
    }

    @Test
    fun parseComputeRoutesBody_success_parsesDurationsAndSpeedIntervals() {
        // First interval omits startPolylinePointIndex, as the live API does when the value is 0.
        val json = """
            {
              "routes": [
                {
                  "duration": "1234s",
                  "staticDuration": "1000s",
                  "distanceMeters": 15000,
                  "polyline": {
                    "encodedPolyline": "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
                  },
                  "travelAdvisory": {
                    "speedReadingIntervals": [
                      { "endPolylinePointIndex": 1, "speed": "NORMAL" },
                      { "startPolylinePointIndex": 1, "endPolylinePointIndex": 2, "speed": "SLOW" }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val route = assertSuccess(parseComputeRoutesBody(json))

        assertEquals(1234L, route.durationSeconds)
        assertEquals(1000L, route.staticDurationSeconds)
        assertEquals(15000L, route.distanceMeters)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", route.encodedPolyline)
        assertEquals(
            listOf(
                SpeedInterval(0, 1, SpeedClass.NORMAL),
                SpeedInterval(1, 2, SpeedClass.SLOW),
            ),
            route.speedIntervals,
        )
    }

    @Test
    fun parseComputeRoutesBody_unknownSpeedStringMapsToUnknown() {
        val json = """
            {
              "routes": [
                {
                  "duration": "1s",
                  "distanceMeters": 1,
                  "polyline": { "encodedPolyline": "abc" },
                  "travelAdvisory": {
                    "speedReadingIntervals": [
                      { "endPolylinePointIndex": 1, "speed": "SPEED_UNSPECIFIED" }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val route = assertSuccess(parseComputeRoutesBody(json))
        assertEquals(SpeedClass.UNKNOWN, route.speedIntervals.single().speed)
    }

    @Test
    fun parseComputeRoutesBody_emptyRoutesReturnsNoRouteFound() {
        val failure = assertFailure(parseComputeRoutesBody("""{ "routes": [] }"""))
        assertEquals("No route found", failure.message)
    }

    @Test
    fun parseComputeRoutesBody_missingRoutesReturnsNoRouteFound() {
        val failure = assertFailure(parseComputeRoutesBody("{}"))
        assertEquals("No route found", failure.message)
    }

    @Test
    fun parseComputeRoutesBody_noSpeedIntervalsYieldsEmptyList() {
        val json = """
            {
              "routes": [
                { "duration": "10s", "distanceMeters": 10, "polyline": { "encodedPolyline": "xyz" } }
              ]
            }
        """.trimIndent()

        val route = assertSuccess(parseComputeRoutesBody(json))
        assertTrue(route.speedIntervals.isEmpty())
    }

    @Test
    fun parseDurationSeconds_handlesIntegerAndFractionalSuffix() {
        assertEquals(1234L, parseDurationSeconds("1234s"))
        assertEquals(1234L, parseDurationSeconds("1234.7s"))
        assertEquals(0L, parseDurationSeconds(null))
        assertEquals(0L, parseDurationSeconds(""))
        assertEquals(0L, parseDurationSeconds("not-a-duration"))
    }

    @Test
    fun parseSpeedClass_mapsKnownValuesAndDefaultsToUnknown() {
        assertEquals(SpeedClass.NORMAL, parseSpeedClass("NORMAL"))
        assertEquals(SpeedClass.SLOW, parseSpeedClass("SLOW"))
        assertEquals(SpeedClass.TRAFFIC_JAM, parseSpeedClass("TRAFFIC_JAM"))
        assertEquals(SpeedClass.UNKNOWN, parseSpeedClass("SPEED_UNSPECIFIED"))
        assertEquals(SpeedClass.UNKNOWN, parseSpeedClass(null))
    }

    @Test
    fun mapErrorResponse_403IsApiKeyInvalid() {
        val failure = mapErrorResponse(403, "")
        assertEquals("API key invalid", failure.message)
    }

    @Test
    fun mapErrorResponse_400WithApiKeyMessageIsApiKeyInvalid() {
        val body = """{ "error": { "code": 400, "message": "API key not valid.", "status": "INVALID_ARGUMENT" } }"""
        val failure = mapErrorResponse(400, body)
        assertEquals("API key invalid", failure.message)
    }

    @Test
    fun mapErrorResponse_otherErrorsAreGeneric() {
        val body = """{ "error": { "code": 400, "message": "Bad request", "status": "INVALID_ARGUMENT" } }"""
        val failure = mapErrorResponse(400, body)
        assertEquals("Route request failed", failure.message)
    }
}
