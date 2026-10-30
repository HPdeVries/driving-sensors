package nl.flexlogic.drivingsensors

import kotlin.math.max

/** Immutable latest-fix snapshot, safe to publish across threads. */
data class LocationReading(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double? = null,
    val accuracyM: Double? = null,
) {
    /** Omits null sub-fields. */
    fun toMap(): Map<String, Any> = buildMap {
        put("ts", ts)
        put("lat", lat)
        put("lon", lon)
        speedMps?.let { put("speedMps", it) }
        accuracyM?.let { put("accuracyM", it) }
    }

    companion object {
        fun from(fix: Fix) = LocationReading(fix.ts, fix.lat, fix.lon, fix.speedMps, fix.accuracyM)
    }
}

/** Immutable motion snapshot. [peakHorizontalMps2] and [samples] are only set for windowed reads. */
data class MotionReading(
    val ts: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val gx: Double,
    val gy: Double,
    val gz: Double,
    val horizontalMps2: Double,
    val peakHorizontalMps2: Double? = null,
    val samples: Int? = null,
) {
    fun toMap(): Map<String, Any> = buildMap {
        put("ts", ts)
        put("ax", ax)
        put("ay", ay)
        put("az", az)
        put("gx", gx)
        put("gy", gy)
        put("gz", gz)
        put("horizontalMps2", horizontalMps2)
        peakHorizontalMps2?.let { put("peakHorizontalMps2", it) }
        samples?.let { put("samples", it) }
    }

    companion object {
        fun of(ts: Long, ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double) =
            MotionReading(ts, ax, ay, az, gx, gy, gz, HarshEventDetector.horizontalMagnitude(ax, ay, az, gx, gy, gz))
    }
}

/** Maps to the `SensorReadings` shape; omits absent objects. */
fun sensorReadingsMap(location: LocationReading?, motion: MotionReading?): Map<String, Any> = buildMap {
    put("hasLocation", location != null)
    location?.let { put("location", it.toMap()) }
    put("hasMotion", motion != null)
    motion?.let { put("motion", it.toMap()) }
}

/** Aggregates a window of motion samples: last sample, peak horizontal magnitude, count. Pure. */
class MotionWindow {
    private var last: MotionReading? = null
    private var peak = 0.0
    private var count = 0

    fun add(sample: MotionReading) {
        last = sample
        peak = if (count == 0) sample.horizontalMps2 else max(peak, sample.horizontalMps2)
        count++
    }

    /** Null when no samples were added. */
    fun result(): MotionReading? = last?.copy(peakHorizontalMps2 = peak, samples = count)
}
