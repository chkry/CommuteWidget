package com.crpakala.commutewidget.api

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Google encoded polyline algorithm (1e-5 precision) and a Douglas-Peucker simplifier used to
 * keep Static Maps URLs under the length limit.
 *
 * https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 */
object Polylines {
    private const val PRECISION = 1e5

    fun decode(encoded: String): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()

        val points = ArrayList<LatLng>()
        var index = 0
        var lat = 0L
        var lng = 0L

        while (index < encoded.length) {
            val (deltaLat, afterLat) = decodeSignedValue(encoded, index)
            lat += deltaLat
            val (deltaLng, afterLng) = decodeSignedValue(encoded, afterLat)
            lng += deltaLng
            index = afterLng
            points.add(LatLng(lat / PRECISION, lng / PRECISION))
        }

        return points
    }

    private fun decodeSignedValue(encoded: String, startIndex: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var index = startIndex
        var chunk: Int
        do {
            chunk = encoded[index].code - 63
            index++
            result = result or ((chunk.toLong() and 0x1f) shl shift)
            shift += 5
        } while (chunk >= 0x20)
        val delta = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
        return delta to index
    }

    fun encode(points: List<LatLng>): String {
        val builder = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L

        for (point in points) {
            val lat = round(point.lat * PRECISION).toLong()
            val lng = round(point.lng * PRECISION).toLong()
            encodeSignedValue(lat - prevLat, builder)
            encodeSignedValue(lng - prevLng, builder)
            prevLat = lat
            prevLng = lng
        }

        return builder.toString()
    }

    private fun encodeSignedValue(value: Long, builder: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            builder.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        builder.append((v.toInt() + 63).toChar())
    }

    /** Ramer-Douglas-Peucker simplification. [tolerance] is in the same units as lat/lng degrees. */
    fun simplify(points: List<LatLng>, tolerance: Double): List<LatLng> {
        if (points.size < 3 || tolerance <= 0.0) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        simplifyRange(points, 0, points.size - 1, tolerance, keep)

        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun simplifyRange(points: List<LatLng>, start: Int, end: Int, tolerance: Double, keep: BooleanArray) {
        if (end <= start + 1) return

        var maxDistance = -1.0
        var maxIndex = -1
        for (i in start + 1 until end) {
            val distance = perpendicularDistance(points[i], points[start], points[end])
            if (distance > maxDistance) {
                maxDistance = distance
                maxIndex = i
            }
        }

        if (maxDistance > tolerance) {
            keep[maxIndex] = true
            simplifyRange(points, start, maxIndex, tolerance, keep)
            simplifyRange(points, maxIndex, end, tolerance, keep)
        }
    }

    private fun perpendicularDistance(point: LatLng, lineStart: LatLng, lineEnd: LatLng): Double {
        val dx = lineEnd.lng - lineStart.lng
        val dy = lineEnd.lat - lineStart.lat

        if (dx == 0.0 && dy == 0.0) {
            val ddx = point.lng - lineStart.lng
            val ddy = point.lat - lineStart.lat
            return sqrt(ddx * ddx + ddy * ddy)
        }

        val numerator = abs(
            dy * point.lng - dx * point.lat + lineEnd.lng * lineStart.lat - lineEnd.lat * lineStart.lng,
        )
        val denominator = sqrt(dx * dx + dy * dy)
        return numerator / denominator
    }
}
