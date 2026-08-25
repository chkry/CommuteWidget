package com.crpakala.commutewidget.api

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [StaticMapUrl.build] end to end (not just [buildColorSegments]) against realistic and
 * adversarial route shapes, since the per-segment simplify-and-retry loop only gets validated by
 * running it against inputs large enough to actually need simplification.
 */
class StaticMapUrlBuildTest {
    private val origin = LatLng(12.9716, 77.5946)
    private val destination = LatLng(12.9352, 77.6146)
    private val apiKey = "test-api-key-1234567890"

    /** A gently winding path of [count] points, dense enough to resemble a real driving route. */
    private fun windingRoute(count: Int): List<LatLng> {
        return (0 until count).map { i ->
            val t = i.toDouble()
            LatLng(
                lat = 12.90 + t * 0.0005 + 0.001 * kotlin.math.sin(t / 7.0),
                lng = 77.55 + t * 0.0006 + 0.001 * kotlin.math.cos(t / 11.0),
            )
        }
    }

    private fun route(points: List<LatLng>, intervals: List<SpeedInterval>): RouteResult = RouteResult(
        durationSeconds = 1800L,
        staticDurationSeconds = 1200L,
        distanceMeters = 12_000L,
        encodedPolyline = Polylines.encode(points),
        speedIntervals = intervals,
    )

    @Test
    fun build_realistic400PointRoute_staysUnderMaxUrlLength() {
        val points = windingRoute(400)
        // A realistic number of traffic-speed transitions along the route (not one per point).
        val boundaries = listOf(0, 40, 90, 140, 200, 260, 310, 360, 399)
        val speeds = listOf(
            SpeedClass.NORMAL,
            SpeedClass.SLOW,
            SpeedClass.NORMAL,
            SpeedClass.TRAFFIC_JAM,
            SpeedClass.SLOW,
            SpeedClass.NORMAL,
            SpeedClass.TRAFFIC_JAM,
            SpeedClass.NORMAL,
        )
        val intervals = boundaries.zipWithNext().mapIndexed { index, (start, end) ->
            SpeedInterval(start, end, speeds[index])
        }

        val url = StaticMapUrl.build(apiKey, 640, 640, route(points, intervals), origin, destination)

        assertTrue("url length was ${url.length}", url.length <= 16384)
        assertTrue(url.startsWith("https://maps.googleapis.com/maps/api/staticmap?"))
    }

    @Test
    fun build_worstCaseOneSegmentPerPoint_stillStaysUnderMaxUrlLength() {
        val points = windingRoute(400)
        // Adversarial: a distinct speed interval for every single point pair, defeating the
        // adjacent-merge in buildColorSegments and forcing the simplify-and-retry fallback path.
        val intervals = (0 until points.size - 1).map { i ->
            val speed = if (i % 2 == 0) SpeedClass.NORMAL else SpeedClass.TRAFFIC_JAM
            SpeedInterval(i, i + 1, speed)
        }

        val url = StaticMapUrl.build(apiKey, 640, 640, route(points, intervals), origin, destination)

        assertTrue("url length was ${url.length}", url.length <= 16384)
    }

    @Test
    fun build_capsDimensionsAt640AndUsesScale2() {
        val points = windingRoute(10)
        val url = StaticMapUrl.build(apiKey, 2000, 2000, route(points, emptyList()), origin, destination)

        assertTrue(url.contains("size=640x640"))
        assertTrue(url.contains("&scale=2"))
    }
}
