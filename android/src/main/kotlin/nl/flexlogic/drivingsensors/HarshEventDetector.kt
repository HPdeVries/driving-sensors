package nl.flexlogic.drivingsensors

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Harsh brake / acceleration detection from horizontal linear acceleration, classified by GPS
 * speed change. Pure logic, see SPEC.md.
 */
class HarshEventDetector(
    private val harshBrakeMps2: Double,
    private val harshAccelMps2: Double,
    private val minEventGapMs: Long,
    private val log: (String) -> Unit = {},
) {
    enum class Type { HARSH_BRAKE, HARSH_ACCEL }

    data class Event(
        val type: Type,
        val ts: Long,
        val peakMps2: Double,
        val speedMps: Double,
        val lat: Double,
        val lon: Double,
    )

    private class Candidate(val start: Long, val end: Long, val peak: Double)

    private val triggerMps2 = minOf(harshBrakeMps2, harshAccelMps2)
    private val releaseMps2 = 0.7 * triggerMps2

    private val windowTs = ArrayDeque<Long>()
    private val windowValues = ArrayDeque<Double>()
    private var windowSum = 0.0

    private var active = false
    private var activeStart = 0L
    private var activePeak = 0.0

    private val pending = ArrayList<Candidate>()
    private val fixes = ArrayList<Fix>()
    private var lastEmittedEnd: Long? = null

    /** Feed one linear-acceleration sample (m/s², gravity removed) with the latest gravity vector. */
    fun onAccel(ts: Long, ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double) {
        val m = horizontalMagnitude(ax, ay, az, gx, gy, gz)
        windowTs.addLast(ts)
        windowValues.addLast(m)
        windowSum += m
        while (windowTs.size > 1 && windowTs.first() <= ts - SMOOTHING_WINDOW_MS) {
            windowTs.removeFirst()
            windowSum -= windowValues.removeFirst()
        }
        val smoothed = windowSum / windowTs.size

        if (!active) {
            if (smoothed > triggerMps2) {
                active = true
                activeStart = ts
                activePeak = smoothed
            }
        } else {
            activePeak = max(activePeak, smoothed)
            if (smoothed < releaseMps2) {
                active = false
                finishCandidate(activeStart, ts, activePeak)
            }
        }
        expirePending(ts)
    }

    /** Returns events whose classification became possible with this fix. */
    fun onFix(fix: Fix): List<Event> {
        fixes.add(fix)
        val cutoff = fix.ts - FIX_HISTORY_MS
        while (fixes.size > 1 && fixes[0].ts < cutoff) fixes.removeAt(0)
        expirePending(fix.ts)

        val out = ArrayList<Event>()
        val it = pending.iterator()
        while (it.hasNext()) {
            val c = it.next()
            val v1Fix = fixes.firstOrNull { f -> f.ts >= c.end + CLASSIFY_DELAY_MS } ?: continue
            it.remove()
            classify(c, v1Fix)?.let { e -> out.add(e) }
        }
        return out
    }

    /** Call periodically so pending candidates expire when no data arrives. */
    fun tick(nowMs: Long) = expirePending(nowMs)

    fun reset() {
        windowTs.clear()
        windowValues.clear()
        windowSum = 0.0
        active = false
        pending.clear()
        fixes.clear()
        lastEmittedEnd = null
    }

    private fun finishCandidate(start: Long, end: Long, peak: Double) {
        if (end - start < MIN_DURATION_MS) {
            log("discard candidate start=$start: duration ${end - start} ms < $MIN_DURATION_MS ms")
            return
        }
        pending.add(Candidate(start, end, peak))
    }

    private fun expirePending(now: Long) {
        val it = pending.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (now - c.end > GIVE_UP_MS) {
                it.remove()
                log("discard candidate start=${c.start}: no qualifying fix within $GIVE_UP_MS ms")
            }
        }
    }

    private fun classify(c: Candidate, v1Fix: Fix): Event? {
        val prevEnd = lastEmittedEnd
        if (prevEnd != null && c.start - prevEnd < minEventGapMs) {
            log("discard candidate start=${c.start}: ${c.start - prevEnd} ms after previous event < $minEventGapMs ms")
            return null
        }
        val v0Fix = fixes.lastOrNull { it.ts <= c.start }
        val v0 = v0Fix?.speedMps
        val v1 = v1Fix.speedMps
        if (v0Fix == null || v0 == null || v1 == null) {
            log("discard candidate start=${c.start}: unknown speed v0=$v0 v1=$v1")
            return null
        }
        val delta = v1 - v0
        if (abs(delta) < MIN_SPEED_DELTA_MPS) {
            log("discard candidate start=${c.start}: |dv|=${abs(delta)} < $MIN_SPEED_DELTA_MPS (v0=$v0 v1=$v1 peak=${c.peak})")
            return null
        }
        val type = if (delta < 0) Type.HARSH_BRAKE else Type.HARSH_ACCEL
        val required = if (type == Type.HARSH_BRAKE) harshBrakeMps2 else harshAccelMps2
        if (c.peak < required) {
            log("discard candidate start=${c.start}: peak ${c.peak} < $required for $type (v0=$v0 v1=$v1)")
            return null
        }
        lastEmittedEnd = c.end
        return Event(type, c.start, c.peak, v0, v0Fix.lat, v0Fix.lon)
    }

    companion object {
        const val SMOOTHING_WINDOW_MS = 250L
        const val MIN_DURATION_MS = 200L
        const val CLASSIFY_DELAY_MS = 1000L
        const val GIVE_UP_MS = 5000L
        const val MIN_SPEED_DELTA_MPS = 0.5
        private const val FIX_HISTORY_MS = 60_000L

        /** Magnitude of the acceleration component perpendicular to gravity. */
        fun horizontalMagnitude(
            ax: Double, ay: Double, az: Double,
            gx: Double, gy: Double, gz: Double,
        ): Double {
            val gNorm = sqrt(gx * gx + gy * gy + gz * gz)
            if (gNorm < 1.0) return sqrt(ax * ax + ay * ay + az * az)
            val ux = gx / gNorm
            val uy = gy / gNorm
            val uz = gz / gNorm
            val dot = ax * ux + ay * uy + az * uz
            val hx = ax - dot * ux
            val hy = ay - dot * uy
            val hz = az - dot * uz
            return sqrt(hx * hx + hy * hy + hz * hz)
        }
    }
}
