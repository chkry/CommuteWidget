package com.crpakala.commutewidget.engine

import com.crpakala.commutewidget.api.LatLng
import com.crpakala.commutewidget.api.RouteResult
import com.crpakala.commutewidget.api.SpeedClass
import com.crpakala.commutewidget.api.SpeedInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MapRenderKeyTest {
    private val origin = LatLng(12.9, 77.6)
    private val destination = LatLng(12.95, 77.72)

    private fun route(
        polyline: String = "abc123",
        intervals: List<SpeedInterval> = listOf(SpeedInterval(0, 5, SpeedClass.NORMAL)),
    ) = RouteResult(
        durationSeconds = 2700L,
        staticDurationSeconds = 2100L,
        distanceMeters = 12_800L,
        encodedPolyline = polyline,
        speedIntervals = intervals,
    )

    @Test
    fun sameInputs_sameKey() {
        assertEquals(
            mapRenderKey(route(), origin, destination, 600, 600),
            mapRenderKey(route(), origin, destination, 600, 600),
        )
    }

    @Test
    fun durationChangeAlone_doesNotChangeKey() {
        val a = route()
        val b = a.copy(durationSeconds = 3000L, staticDurationSeconds = 2000L, distanceMeters = 12_801L)
        assertEquals(
            mapRenderKey(a, origin, destination, 600, 600),
            mapRenderKey(b, origin, destination, 600, 600),
        )
    }

    @Test
    fun polylineChange_changesKey() {
        assertNotEquals(
            mapRenderKey(route(polyline = "abc123"), origin, destination, 600, 600),
            mapRenderKey(route(polyline = "xyz789"), origin, destination, 600, 600),
        )
    }

    @Test
    fun trafficIntervalChange_changesKey() {
        val calm = route(intervals = listOf(SpeedInterval(0, 5, SpeedClass.NORMAL)))
        val jam = route(intervals = listOf(SpeedInterval(0, 5, SpeedClass.TRAFFIC_JAM)))
        assertNotEquals(
            mapRenderKey(calm, origin, destination, 600, 600),
            mapRenderKey(jam, origin, destination, 600, 600),
        )
    }

    @Test
    fun endpointOrDimensionChange_changesKey() {
        val base = mapRenderKey(route(), origin, destination, 600, 600)
        assertNotEquals(base, mapRenderKey(route(), LatLng(13.0, 77.6), destination, 600, 600))
        assertNotEquals(base, mapRenderKey(route(), origin, LatLng(12.95, 77.73), 600, 600))
        assertNotEquals(base, mapRenderKey(route(), origin, destination, 640, 640))
    }
}
