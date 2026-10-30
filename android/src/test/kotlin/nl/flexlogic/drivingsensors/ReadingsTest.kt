package nl.flexlogic.drivingsensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingsTest {
    private fun sample(ts: Long, ax: Double, ay: Double = 0.0) = MotionReading.of(ts, ax, ay, 0.0, 0.0, 0.0, 9.81)

    @Test
    fun emptyWindowHasNoResult() {
        assertNull(MotionWindow().result())
    }

    @Test
    fun windowReportsLastSamplePeakAndCount() {
        val w = MotionWindow()
        w.add(sample(1, 1.0))
        w.add(sample(2, 4.0, 3.0)) // horizontal 5.0
        w.add(sample(3, 2.0))
        val r = w.result()!!
        assertEquals(3L, r.ts)
        assertEquals(2.0, r.ax, 0.0)
        assertEquals(2.0, r.horizontalMps2, 1e-9)
        assertEquals(5.0, r.peakHorizontalMps2!!, 1e-9)
        assertEquals(3, r.samples)
    }

    @Test
    fun horizontalUsesGravityProjection() {
        // Vertical acceleration along gravity is excluded.
        val m = MotionReading.of(0, 0.0, 0.0, 5.0, 0.0, 0.0, 9.81)
        assertEquals(0.0, m.horizontalMps2, 1e-9)
    }

    @Test
    fun locationMapOmitsNullFields() {
        val m = LocationReading(10, 52.0, 5.0).toMap()
        assertEquals(setOf("ts", "lat", "lon"), m.keys)
        val full = LocationReading(10, 52.0, 5.0, 3.5, 8.0).toMap()
        assertEquals(3.5, full["speedMps"])
        assertEquals(8.0, full["accuracyM"])
    }

    @Test
    fun motionMapOmitsWindowFieldsUnlessSet() {
        assertFalse(sample(1, 1.0).toMap().containsKey("peakHorizontalMps2"))
        val w = MotionWindow().apply { add(sample(1, 1.0)) }.result()!!.toMap()
        assertEquals(1, w["samples"])
        assertTrue(w.containsKey("peakHorizontalMps2"))
    }

    @Test
    fun sensorReadingsMapOmitsAbsentObjects() {
        val none = sensorReadingsMap(null, null)
        assertEquals(mapOf<String, Any>("hasLocation" to false, "hasMotion" to false), none)
        val both = sensorReadingsMap(LocationReading(1, 1.0, 2.0), sample(1, 1.0))
        assertEquals(true, both["hasLocation"])
        assertEquals(true, both["hasMotion"])
        assertTrue(both["location"] is Map<*, *>)
        assertTrue(both["motion"] is Map<*, *>)
    }

    @Test
    fun locationFromFixCopiesFields() {
        val r = LocationReading.from(Fix(5, 1.0, 2.0, null, 4.0))
        assertNull(r.speedMps)
        assertEquals(4.0, r.accuracyM!!, 0.0)
    }
}
