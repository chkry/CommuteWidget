package com.crpakala.commutewidget.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PolylinesTest {
    // The canonical example from Google's polyline algorithm documentation.
    private val encodedExample = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
    private val decodedExample = listOf(
        LatLng(38.5, -120.2),
        LatLng(40.7, -120.95),
        LatLng(43.252, -126.453),
    )

    @Test
    fun decode_documentedExample() {
        val decoded = Polylines.decode(encodedExample)
        assertEquals(decodedExample.size, decoded.size)
        decodedExample.zip(decoded).forEach { (expected, actual) ->
            assertTrue("lat mismatch: $expected vs $actual", abs(expected.lat - actual.lat) < 1e-6)
            assertTrue("lng mismatch: $expected vs $actual", abs(expected.lng - actual.lng) < 1e-6)
        }
    }

    @Test
    fun encode_documentedExample() {
        assertEquals(encodedExample, Polylines.encode(decodedExample))
    }

    @Test
    fun decodeEncode_roundTrip() {
        val decoded = Polylines.decode(encodedExample)
        assertEquals(encodedExample, Polylines.encode(decoded))
    }

    @Test
    fun decode_emptyStringReturnsEmptyList() {
        assertEquals(emptyList<LatLng>(), Polylines.decode(""))
    }

    @Test
    fun simplify_dropsSmallBumpWhenWithinTolerance() {
        // A shallow bump off the P0-P2 straight line (perpendicular distance 0.5).
        val points = listOf(LatLng(0.0, 0.0), LatLng(0.5, 1.0), LatLng(0.0, 2.0))

        assertEquals(listOf(points[0], points[2]), Polylines.simplify(points, tolerance = 1.0))
    }

    @Test
    fun simplify_keepsBumpExceedingTolerance() {
        val points = listOf(LatLng(0.0, 0.0), LatLng(0.5, 1.0), LatLng(0.0, 2.0))

        assertEquals(points, Polylines.simplify(points, tolerance = 0.1))
    }

    @Test
    fun simplify_keepsAllPointsWhenToleranceIsZero() {
        val points = listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(0.0, 2.0))
        assertEquals(points, Polylines.simplify(points, tolerance = 0.0))
    }

    @Test
    fun simplify_shortListsAreUnchanged() {
        val points = listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0))
        assertEquals(points, Polylines.simplify(points, tolerance = 10.0))
    }
}
