package com.crpakala.commutewidget.api

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Builds Google Static Maps URLs with the route drawn as one or more traffic-colored polyline
 * segments, auto-fit (no center/zoom) with an origin and destination marker.
 */
object StaticMapUrl {
    private const val BASE_URL = "https://maps.googleapis.com/maps/api/staticmap"
    private const val MAX_URL_LENGTH = 16384
    private const val MAX_DIMENSION_PX = 640
    private const val PATH_WEIGHT = 5
    private const val MAX_SIMPLIFY_ITERATIONS = 12
    private const val INITIAL_TOLERANCE = 0.00003

    private const val COLOR_NORMAL = "0x34A853FF"
    private const val COLOR_SLOW = "0xFBBC04FF"
    private const val COLOR_TRAFFIC_JAM = "0xEA4335FF"
    private const val COLOR_UNKNOWN = "0x9AA0A6FF"
    private const val COLOR_DEFAULT = "0x4285F4FF"

    fun build(
        apiKey: String,
        widthPx: Int,
        heightPx: Int,
        route: RouteResult,
        origin: LatLng,
        destination: LatLng,
    ): String {
        val points = Polylines.decode(route.encodedPolyline)
        val segments = buildColorSegments(points, route.speedIntervals)

        var tolerance = 0.0
        repeat(MAX_SIMPLIFY_ITERATIONS) {
            val url = renderUrl(apiKey, widthPx, heightPx, segments, origin, destination, tolerance)
            if (url.length <= MAX_URL_LENGTH) {
                return url
            }
            tolerance = nextTolerance(tolerance)
        }

        var fallbackTolerance = tolerance
        repeat(MAX_SIMPLIFY_ITERATIONS) {
            val simplified = Polylines.simplify(points, fallbackTolerance)
            val fallbackSegments = listOf(ColorSegment(null, simplified))
            val url = renderUrl(apiKey, widthPx, heightPx, fallbackSegments, origin, destination, 0.0)
            if (url.length <= MAX_URL_LENGTH || simplified.size <= 2) {
                return url
            }
            fallbackTolerance = nextTolerance(fallbackTolerance)
        }

        val endpointsOnly = listOfNotNull(points.firstOrNull(), points.lastOrNull())
        return renderUrl(apiKey, widthPx, heightPx, listOf(ColorSegment(null, endpointsOnly)), origin, destination, 0.0)
    }

    private fun nextTolerance(current: Double): Double = if (current <= 0.0) INITIAL_TOLERANCE else current * 2

    private fun renderUrl(
        apiKey: String,
        widthPx: Int,
        heightPx: Int,
        segments: List<ColorSegment>,
        origin: LatLng,
        destination: LatLng,
        tolerance: Double,
    ): String {
        val cappedWidth = widthPx.coerceIn(1, MAX_DIMENSION_PX)
        val cappedHeight = heightPx.coerceIn(1, MAX_DIMENSION_PX)

        val builder = StringBuilder(BASE_URL).append('?')
        builder.append("size=").append(cappedWidth).append('x').append(cappedHeight)
        builder.append("&scale=2")
        builder.append("&maptype=roadmap")
        builder.append("&language=en")

        for (segment in segments) {
            val segmentPoints = if (tolerance > 0.0) Polylines.simplify(segment.points, tolerance) else segment.points
            if (segmentPoints.size < 2) continue
            val encoded = Polylines.encode(segmentPoints)
            builder.append("&path=")
                .append(urlEncode("color:${colorFor(segment.speed)}|weight:$PATH_WEIGHT|enc:$encoded"))
        }

        builder.append("&markers=").append(urlEncode("size:small|color:green|${formatCoordinate(origin)}"))
        builder.append("&markers=").append(urlEncode(formatCoordinate(destination)))
        builder.append("&key=").append(urlEncode(apiKey))

        return builder.toString()
    }

    private fun formatCoordinate(latLng: LatLng): String =
        String.format(Locale.US, "%.6f,%.6f", latLng.lat, latLng.lng)

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun colorFor(speed: SpeedClass?): String = when (speed) {
        SpeedClass.NORMAL -> COLOR_NORMAL
        SpeedClass.SLOW -> COLOR_SLOW
        SpeedClass.TRAFFIC_JAM -> COLOR_TRAFFIC_JAM
        SpeedClass.UNKNOWN -> COLOR_UNKNOWN
        null -> COLOR_DEFAULT
    }
}

internal data class ColorSegment(val speed: SpeedClass?, val points: List<LatLng>)

/**
 * Splits [points] into contiguous runs sharing the same [SpeedClass], merging adjacent intervals
 * of equal speed. Consecutive runs share their boundary point so drawn segments connect.
 * Returns a single run with a null (default) speed when [intervals] is empty.
 */
internal fun buildColorSegments(points: List<LatLng>, intervals: List<SpeedInterval>): List<ColorSegment> {
    if (points.isEmpty()) return emptyList()
    if (intervals.isEmpty()) return listOf(ColorSegment(null, points))

    val sorted = intervals.sortedBy { it.startPolylinePointIndex }
    val merged = mutableListOf<SpeedInterval>()
    for (interval in sorted) {
        val last = merged.lastOrNull()
        if (last != null && last.speed == interval.speed && last.endPolylinePointIndex == interval.startPolylinePointIndex) {
            merged[merged.lastIndex] = last.copy(endPolylinePointIndex = interval.endPolylinePointIndex)
        } else {
            merged.add(interval)
        }
    }

    return merged.mapNotNull { interval ->
        val start = interval.startPolylinePointIndex.coerceIn(0, points.size - 1)
        val end = interval.endPolylinePointIndex.coerceIn(start, points.size - 1)
        if (end < start) return@mapNotNull null
        ColorSegment(interval.speed, points.subList(start, end + 1))
    }
}
