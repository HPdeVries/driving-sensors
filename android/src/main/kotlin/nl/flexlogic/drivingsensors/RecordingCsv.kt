package nl.flexlogic.drivingsensors

/** csv-v1 line formatting. Locale independent (always '.'), no per-sample allocation. */
object RecordingCsv {
    const val HEADER = "t_ms,sensor,x,y,z,v4,v5"

    private val POW10 = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)

    /** Appends [v] with up to [decimals] decimals, trailing zeros trimmed. NaN/Infinity appends nothing. */
    fun appendNumber(sb: StringBuilder, v: Double, decimals: Int) {
        if (v.isNaN() || v.isInfinite()) return
        val scale = POW10[decimals]
        val r = Math.round(Math.abs(v) * scale)
        if (r == 0L) {
            sb.append('0')
            return
        }
        if (v < 0) sb.append('-')
        sb.append(r / scale)
        var frac = r % scale
        if (frac == 0L) return
        var digits = decimals
        while (frac % 10 == 0L) {
            frac /= 10
            digits--
        }
        sb.append('.')
        var pad = digits - digitCount(frac)
        while (pad-- > 0) sb.append('0')
        sb.append(frac)
    }

    private fun digitCount(n: Long): Int {
        var c = 1
        var x = n
        while (x >= 10) {
            x /= 10
            c++
        }
        return c
    }

    /** Appends one sample line (with trailing '\n'). Unknown values are NaN and become empty fields. */
    fun appendSample(sb: StringBuilder, tMs: Long, sensor: Int, x: Double, y: Double, z: Double, v4: Double, v5: Double) {
        val loc = sensor == RecordingSensors.LOC
        sb.append(tMs).append(',').append(RecordingSensors.NAMES[sensor]).append(',')
        appendNumber(sb, x, if (loc) 8 else 6)
        sb.append(',')
        appendNumber(sb, y, if (loc) 8 else 6)
        sb.append(',')
        appendNumber(sb, z, if (loc) 3 else 6)
        sb.append(',')
        appendNumber(sb, v4, if (loc) 3 else 6)
        sb.append(',')
        appendNumber(sb, v5, if (loc) 3 else 6)
        sb.append('\n')
    }
}

/** Rolling 5-second per-sensor sample counter used for `measuredHz`. Thread safe. */
class RateWindow(private val sensorCount: Int = RecordingSensors.NAMES.size, private val startMs: Long) {
    private val counts = Array(sensorCount) { LongArray(BUCKETS) }
    private val stamps = LongArray(BUCKETS) { -1 }

    @Synchronized
    fun record(sensor: Int, nowMs: Long) {
        val idx = nowMs / BUCKET_MS
        val slot = (idx % BUCKETS).toInt()
        if (idx < stamps[slot]) return // late sample from a bucket that was already recycled
        if (stamps[slot] != idx) {
            stamps[slot] = idx
            for (s in 0 until sensorCount) counts[s][slot] = 0
        }
        counts[sensor][slot]++
    }

    /** Samples per second over the last 5 s (or since start when shorter). 0 when under 0.5 s of data. */
    @Synchronized
    fun measuredHz(sensor: Int, nowMs: Long): Double {
        val nowIdx = nowMs / BUCKET_MS
        var sum = 0L
        for (slot in 0 until BUCKETS) {
            val st = stamps[slot]
            if (st in (nowIdx - BUCKETS + 1)..nowIdx) sum += counts[sensor][slot]
        }
        val seconds = minOf(BUCKETS * BUCKET_MS, nowMs - startMs) / 1000.0
        if (seconds < 0.5) return 0.0
        return sum / seconds
    }

    companion object {
        const val BUCKET_MS = 100L
        const val BUCKETS = 50
    }
}
