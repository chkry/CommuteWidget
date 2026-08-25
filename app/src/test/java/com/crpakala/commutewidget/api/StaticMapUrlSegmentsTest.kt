package com.crpakala.commutewidget.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaticMapUrlSegmentsTest {
    private val points = listOf(
        LatLng(0.0, 0.0),
        LatLng(1.0, 0.0),
        LatLng(2.0, 0.0),
        LatLng(3.0, 0.0),
        LatLng(4.0, 0.0),
        LatLng(5.0, 0.0),
    )

    @Test
    fun buildColorSegments_emptyIntervalsProducesSingleDefaultSegment() {
        val segments = buildColorSegments(points, emptyList())
        assertEquals(1, segments.size)
        assertNull(segments[0].speed)
        assertEquals(points, segments[0].points)
    }

    @Test
    fun buildColorSegments_mergesAdjacentIntervalsOfSameSpeed() {
        // 0..2 and 2..3 are both NORMAL and should merge into a single 0..3 run.
        val intervals = listOf(
            SpeedInterval(0, 2, SpeedClass.NORMAL),
            SpeedInterval(2, 3, SpeedClass.NORMAL),
            SpeedInterval(3, 4, SpeedClass.SLOW),
            SpeedInterval(4, 5, SpeedClass.TRAFFIC_JAM),
        )

        val segments = buildColorSegments(points, intervals)

        assertEquals(3, segments.size)

        assertEquals(SpeedClass.NORMAL, segments[0].speed)
        assertEquals(points.subList(0, 4), segments[0].points)

        assertEquals(SpeedClass.SLOW, segments[1].speed)
        assertEquals(points.subList(3, 5), segments[1].points)

        assertEquals(SpeedClass.TRAFFIC_JAM, segments[2].speed)
        assertEquals(points.subList(4, 6), segments[2].points)
    }

    @Test
    fun buildColorSegments_consecutiveSegmentsShareBoundaryPoint() {
        val intervals = listOf(
            SpeedInterval(0, 2, SpeedClass.NORMAL),
            SpeedInterval(2, 5, SpeedClass.SLOW),
        )

        val segments = buildColorSegments(points, intervals)

        assertEquals(2, segments.size)
        assertEquals(segments[0].points.last(), segments[1].points.first())
    }

    @Test
    fun buildColorSegments_doesNotMergeSameSpeedAcrossAGap() {
        val intervals = listOf(
            SpeedInterval(0, 1, SpeedClass.NORMAL),
            SpeedInterval(2, 3, SpeedClass.NORMAL),
        )

        val segments = buildColorSegments(points, intervals)

        assertEquals(2, segments.size)
    }

    @Test
    fun buildColorSegments_emptyPointsProducesNoSegments() {
        assertEquals(emptyList<ColorSegment>(), buildColorSegments(emptyList(), emptyList()))
    }
}
