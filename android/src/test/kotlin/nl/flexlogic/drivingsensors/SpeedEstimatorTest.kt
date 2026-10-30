package nl.flexlogic.drivingsensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedEstimatorTest {
    // 0.001 degrees of latitude is about 111.19 m.
    private val stepLat = 0.001
    private val stepMeters = 111.19

    @Test
    fun usesPlatformSpeedWhenValid() {
        val e = SpeedEstimator()
        assertEquals(7.5, e.estimate(0, 52.0, 5.0, 7.5)!!, 0.0)
        assertEquals(0.0, e.estimate(1000, 52.001, 5.0, 0.0)!!, 0.0)
    }

    @Test
    fun firstFixWithoutPlatformSpeedIsUnknown() {
        assertNull(SpeedEstimator().estimate(0, 52.0, 5.0, null))
    }

    @Test
    fun fallsBackToHaversine() {
        val e = SpeedEstimator()
        e.estimate(0, 52.0, 5.0, null)
        val v = e.estimate(5000, 52.0 + stepLat, 5.0, null)!!
        assertEquals(stepMeters / 5.0, v, 0.1)
    }

    @Test
    fun invalidPlatformSpeedFallsBack() {
        val e = SpeedEstimator()
        e.estimate(0, 52.0, 5.0, 3.0)
        val v = e.estimate(1000, 52.0 + stepLat, 5.0, -1.0)!!
        assertEquals(stepMeters, v, 0.2)
    }

    @Test
    fun gapLimitsAreInclusive() {
        val e = SpeedEstimator()
        e.estimate(0, 52.0, 5.0, null)
        assertNull(e.estimate(499, 52.0 + stepLat, 5.0, null))

        val lower = SpeedEstimator()
        lower.estimate(0, 52.0, 5.0, null)
        assertEquals(stepMeters / 0.5, lower.estimate(500, 52.0 + stepLat, 5.0, null)!!, 0.5)

        val upper = SpeedEstimator()
        upper.estimate(0, 52.0, 5.0, null)
        assertEquals(stepMeters / 10.0, upper.estimate(10_000, 52.0 + stepLat, 5.0, null)!!, 0.05)

        val over = SpeedEstimator()
        over.estimate(0, 52.0, 5.0, null)
        assertNull(over.estimate(10_001, 52.0 + stepLat, 5.0, null))
    }

    @Test
    fun previousFixIsUpdatedEvenWhenSpeedUnknown() {
        val e = SpeedEstimator()
        e.estimate(0, 52.0, 5.0, null)
        assertNull(e.estimate(20_000, 52.0, 5.0, null))
        val v = e.estimate(21_000, 52.0 + stepLat, 5.0, null)!!
        assertEquals(stepMeters, v, 0.2)
    }

    @Test
    fun haversineKnownDistance() {
        // One degree of longitude at the equator is about 111.19 km.
        assertEquals(111_195.0, SpeedEstimator.haversineMeters(0.0, 0.0, 0.0, 1.0), 50.0)
        assertEquals(0.0, SpeedEstimator.haversineMeters(52.0, 5.0, 52.0, 5.0), 0.0)
    }
}
