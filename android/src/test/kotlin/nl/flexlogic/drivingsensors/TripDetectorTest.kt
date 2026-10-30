package nl.flexlogic.drivingsensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {
    private val idleMs = 10_000L

    private fun detector() = TripDetector(startSpeedMps = 4.2, endIdleMs = idleMs)
    private fun fix(ts: Long, speed: Double?) = Fix(ts, 52.0 + ts / 1e6, 5.0, speed, 5.0)

    @Test
    fun startsAfterThreeFastFixesAndUsesFirstFix() {
        val d = detector()
        assertTrue(d.onFix(fix(1000, 5.0)).isEmpty())
        assertTrue(d.onFix(fix(2000, 6.0)).isEmpty())
        val events = d.onFix(fix(3000, 7.0))
        assertEquals(1, events.size)
        assertEquals(TripDetector.Type.TRIP_START, events[0].type)
        assertEquals(1000L, events[0].fix.ts)
        assertEquals(5.0, events[0].fix.speedMps!!, 0.0)
        assertTrue(d.inTrip)
    }

    @Test
    fun singleFastFixDoesNotStart() {
        val d = detector()
        assertTrue(d.onFix(fix(1000, 20.0)).isEmpty())
        assertTrue(d.onFix(fix(2000, 1.0)).isEmpty())
        assertFalse(d.inTrip)
    }

    @Test
    fun slowFixResetsStreak() {
        val d = detector()
        d.onFix(fix(1000, 5.0))
        d.onFix(fix(2000, 5.0))
        d.onFix(fix(3000, 1.0))
        assertTrue(d.onFix(fix(4000, 5.0)).isEmpty())
        assertFalse(d.inTrip)
    }

    @Test
    fun unknownSpeedIsIgnored() {
        val d = detector()
        d.onFix(fix(1000, 5.0))
        d.onFix(fix(2000, null))
        d.onFix(fix(3000, 5.0))
        assertFalse(d.inTrip)
        assertEquals(1, d.onFix(fix(4000, 5.0)).size)
    }

    @Test
    fun unknownSpeedDoesNotExtendTrip() {
        val d = startedDetector()
        assertTrue(d.onFix(fix(3000 + idleMs - 1, null)).isEmpty())
        // An unknown-speed fix past the idle time still ends the trip? No: it is ignored entirely.
        assertTrue(d.onFix(fix(3000 + idleMs + 5000, null)).isEmpty())
        assertTrue(d.inTrip)
        val end = d.onTick(3000 + idleMs)
        assertEquals(1, end.size)
    }

    private fun startedDetector(): TripDetector {
        val d = detector()
        d.onFix(fix(1000, 5.0))
        d.onFix(fix(2000, 5.0))
        d.onFix(fix(3000, 5.0))
        assertTrue(d.inTrip)
        return d
    }

    @Test
    fun endsAfterIdleOnSlowFixAndCarriesLastFastFix() {
        val d = startedDetector()
        val lastFast = fix(5000, 9.0)
        d.onFix(lastFast)
        assertTrue(d.onFix(fix(5000 + idleMs - 1, 0.0)).isEmpty())
        val events = d.onFix(fix(5000 + idleMs, 0.0))
        assertEquals(1, events.size)
        assertEquals(TripDetector.Type.TRIP_END, events[0].type)
        assertEquals(lastFast, events[0].fix)
        assertFalse(d.inTrip)
    }

    @Test
    fun endsViaTimerWithoutFixes() {
        val d = startedDetector()
        assertTrue(d.onTick(3000 + idleMs - 1).isEmpty())
        val events = d.onTick(3000 + idleMs)
        assertEquals(1, events.size)
        assertEquals(TripDetector.Type.TRIP_END, events[0].type)
        assertEquals(3000L, events[0].fix.ts)
        assertFalse(d.inTrip)
        assertTrue(d.onTick(3000 + 10 * idleMs).isEmpty())
    }

    @Test
    fun fastFixesExtendTrip() {
        val d = startedDetector()
        d.onFix(fix(3000 + idleMs - 1, 6.0))
        assertTrue(d.onTick(3000 + idleMs + 5000).isEmpty())
        assertTrue(d.inTrip)
    }

    @Test
    fun forceEndEmitsTripEndOnlyInTrip() {
        val d = detector()
        assertEquals(null, d.forceEnd())
        val started = startedDetector()
        val end = started.forceEnd()
        assertNotNull(end)
        assertEquals(3000L, end!!.fix.ts)
        assertFalse(started.inTrip)
    }
}
