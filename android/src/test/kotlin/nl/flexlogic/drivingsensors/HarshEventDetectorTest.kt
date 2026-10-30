package nl.flexlogic.drivingsensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarshEventDetectorTest {
    private val logs = ArrayList<String>()

    private fun detector(brake: Double = 3.0, accel: Double = 3.0, gapMs: Long = 3000) =
        HarshEventDetector(brake, accel, gapMs) { logs.add(it) }

    private fun fix(ts: Long, speed: Double?) = Fix(ts, 52.0, 5.0 + ts / 1e7, speed)

    /** Feeds 50 Hz samples of horizontal magnitude [mag] (x axis, gravity on z) for [fromTs, toTs). */
    private fun feed(d: HarshEventDetector, fromTs: Long, toTs: Long, mag: Double) {
        var t = fromTs
        while (t < toTs) {
            d.onAccel(t, mag, 0.0, 0.0, 0.0, 0.0, 9.81)
            t += 20
        }
    }

    /** A 600 ms burst at [start] followed by quiet samples until [quietUntil]. */
    private fun burst(d: HarshEventDetector, start: Long, mag: Double, quietUntil: Long) {
        feed(d, start, start + 600, mag)
        feed(d, start + 600, quietUntil, 0.0)
    }

    private fun assertLogged(fragment: String) =
        assertTrue("expected log containing '$fragment', got $logs", logs.any { it.contains(fragment) })

    // ---- horizontal projection ----

    @Test
    fun projectionRemovesGravityAxisOnZ() {
        val m = HarshEventDetector.horizontalMagnitude(3.0, 4.0, 2.0, 0.0, 0.0, 9.81)
        assertEquals(5.0, m, 1e-9)
    }

    @Test
    fun projectionRemovesGravityAxisOnY() {
        val m = HarshEventDetector.horizontalMagnitude(3.0, 2.0, 4.0, 0.0, -9.81, 0.0)
        assertEquals(5.0, m, 1e-9)
    }

    @Test
    fun projectionHandlesTiltedPhone() {
        // g along (0, 0.6, 0.8); a = 3 on x plus 7 along gravity axis.
        val m = HarshEventDetector.horizontalMagnitude(3.0, 7 * 0.6, 7 * 0.8, 0.0, 6.0, 8.0)
        assertEquals(3.0, m, 1e-9)
    }

    @Test
    fun projectionPureGravityAxisIsZero() {
        val m = HarshEventDetector.horizontalMagnitude(0.0, 0.0, 12.0, 0.0, 0.0, 9.81)
        assertEquals(0.0, m, 1e-9)
    }

    @Test
    fun projectionFallsBackToFullMagnitudeWithoutGravity() {
        val m = HarshEventDetector.horizontalMagnitude(1.0, 2.0, 2.0, 0.0, 0.0, 0.5)
        assertEquals(3.0, m, 1e-9)
    }

    @Test
    fun gravityAxisAccelerationAloneTriggersNothing() {
        val d = detector()
        d.onFix(fix(500, 15.0))
        var t = 1000L
        while (t < 2000) {
            d.onAccel(t, 0.0, 9.0, 0.0, 0.0, 9.81, 0.0) // bump along gravity axis (y)
            t += 20
        }
        feed(d, 2000, 3500, 0.0)
        assertTrue(d.onFix(fix(3500, 5.0)).isEmpty())
        assertTrue(logs.isEmpty())
    }

    // ---- classification ----

    @Test
    fun brakingIsClassifiedViaSpeedDrop() {
        val d = detector()
        d.onFix(fix(500, 15.0))
        burst(d, 1000, 5.0, 2800)
        val events = d.onFix(fix(3500, 8.0))
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(HarshEventDetector.Type.HARSH_BRAKE, e.type)
        assertEquals(1000L, e.ts)
        assertEquals(5.0, e.peakMps2, 1e-9)
        assertEquals(15.0, e.speedMps, 0.0)
        assertEquals(52.0, e.lat, 0.0)
        assertEquals(5.0 + 500 / 1e7, e.lon, 1e-12)
    }

    @Test
    fun accelerationIsClassifiedViaSpeedGain() {
        val d = detector()
        d.onFix(fix(500, 5.0))
        burst(d, 1000, 5.0, 2800)
        val events = d.onFix(fix(3500, 12.0))
        assertEquals(1, events.size)
        assertEquals(HarshEventDetector.Type.HARSH_ACCEL, events[0].type)
        assertEquals(5.0, events[0].speedMps, 0.0)
    }

    @Test
    fun v1IsFirstFixAtLeastOneSecondAfterEnd() {
        val d = detector()
        d.onFix(fix(500, 15.0))
        burst(d, 1000, 5.0, 2800)
        // Candidate ends before 2000 ms + ~170 ms; a fix at 2000 is too early.
        assertTrue(d.onFix(fix(2000, 15.0)).isEmpty())
        val events = d.onFix(fix(3500, 8.0))
        assertEquals(1, events.size)
        assertEquals(HarshEventDetector.Type.HARSH_BRAKE, events[0].type)
    }

    @Test
    fun shortCandidateIsDiscarded() {
        val d = detector()
        d.onFix(fix(500, 15.0))
        d.onAccel(1000, 5.0, 0.0, 0.0, 0.0, 0.0, 9.81) // single spike: smoothed 5, then 2.5, 1.67
        feed(d, 1020, 2800, 0.0)
        assertTrue(d.onFix(fix(3500, 8.0)).isEmpty())
        assertLogged("duration")
    }

    @Test
    fun smallSpeedDeltaIsDiscarded() {
        val d = detector()
        d.onFix(fix(500, 10.0))
        burst(d, 1000, 5.0, 2800)
        assertTrue(d.onFix(fix(3500, 10.3)).isEmpty())
        assertLogged("|dv|")
    }

    @Test
    fun unknownSpeedIsDiscarded() {
        val d = detector()
        d.onFix(fix(500, null))
        burst(d, 1000, 5.0, 2800)
        assertTrue(d.onFix(fix(3500, 5.0)).isEmpty())
        assertLogged("unknown speed")

        val d2 = detector()
        d2.onFix(fix(500, 15.0))
        burst(d2, 1000, 5.0, 2800)
        assertTrue(d2.onFix(fix(3500, null)).isEmpty())
    }

    @Test
    fun peakBelowClassSpecificThresholdIsDiscarded() {
        // harshBrake=4, harshAccel=2: trigger at 2, a peak of 3 is enough for accel but not for braking.
        val brake = detector(brake = 4.0, accel = 2.0)
        brake.onFix(fix(500, 15.0))
        burst(brake, 1000, 3.0, 2800)
        assertTrue(brake.onFix(fix(3500, 8.0)).isEmpty())
        assertLogged("HARSH_BRAKE")

        val accel = detector(brake = 4.0, accel = 2.0)
        accel.onFix(fix(500, 5.0))
        burst(accel, 1000, 3.0, 2800)
        val events = accel.onFix(fix(3500, 12.0))
        assertEquals(1, events.size)
        assertEquals(HarshEventDetector.Type.HARSH_ACCEL, events[0].type)
    }

    @Test
    fun minGapSuppressesSecondEvent() {
        val d = detector()
        d.onFix(fix(500, 15.0))
        burst(d, 1000, 5.0, 3500)
        assertEquals(1, d.onFix(fix(3000, 8.0)).size)
        // Second burst starts 1.7 s after the first one ended.
        burst(d, 3500, 5.0, 5400)
        assertTrue(d.onFix(fix(5500, 3.0)).isEmpty())
        assertLogged("previous event")
    }

    @Test
    fun eventAfterMinGapIsEmitted() {
        val d = detector()
        d.onFix(fix(500, 15.0))
        burst(d, 1000, 5.0, 3500)
        assertEquals(1, d.onFix(fix(3000, 8.0)).size)
        burst(d, 6000, 5.0, 8400)
        val events = d.onFix(fix(8500, 3.0))
        assertEquals(1, events.size)
        assertEquals(6000L, events[0].ts)
        assertEquals(8.0, events[0].speedMps, 0.0)
    }

    @Test
    fun pendingCandidateIsDiscardedAfterFiveSecondsWithoutFix() {
        val d = detector()
        d.onFix(fix(500, 15.0))
        burst(d, 1000, 5.0, 8000) // accel keeps flowing, no fix
        assertLogged("no qualifying fix")
        assertTrue(d.onFix(fix(8500, 8.0)).isEmpty())
    }

    @Test
    fun pendingCandidateExpiresViaTick() {
        val d = detector()
        d.onFix(fix(500, 15.0))
        burst(d, 1000, 5.0, 2000)
        d.tick(30_000)
        assertLogged("no qualifying fix")
        assertTrue(d.onFix(fix(30_500, 8.0)).isEmpty())
    }
}
