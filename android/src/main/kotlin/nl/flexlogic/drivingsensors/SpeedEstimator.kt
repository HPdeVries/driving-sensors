package nl.flexlogic.drivingsensors

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Uses the platform speed when valid, otherwise derives it from the previous fix (haversine). */
class SpeedEstimator {
    private var prevTs = 0L
    private var prevLat = 0.0
    private var prevLon = 0.0
    private var hasPrev = false

    fun estimate(ts: Long, lat: Double, lon: Double, platformSpeedMps: Double?): Double? {
        var result: Double? = null
        if (platformSpeedMps != null && !platformSpeedMps.isNaN() && platformSpeedMps >= 0) {
            result = platformSpeedMps
        } else if (hasPrev) {
            val dtMs = ts - prevTs
            if (dtMs in MIN_GAP_MS..MAX_GAP_MS) {
                result = haversineMeters(prevLat, prevLon, lat, lon) / (dtMs / 1000.0)
            }
        }
        prevTs = ts
        prevLat = lat
        prevLon = lon
        hasPrev = true
        return result
    }

    fun reset() {
        hasPrev = false
    }

    companion object {
        const val MIN_GAP_MS = 500L
        const val MAX_GAP_MS = 10_000L
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dLat = p2 - p1
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(a))
        }
    }
}
