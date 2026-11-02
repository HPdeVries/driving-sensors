package nl.flexlogic.drivingsensors

import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecordingTest {

    private fun line(t: Long, sensor: Int, x: Double, y: Double = Double.NaN, z: Double = Double.NaN, v4: Double = Double.NaN, v5: Double = Double.NaN): String {
        val sb = StringBuilder()
        RecordingCsv.appendSample(sb, t, sensor, x, y, z, v4, v5)
        return sb.toString()
    }

    private fun gunzip(f: File): String = GZIPInputStream(f.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun tempDir(): File = Files.createTempDirectory("rec-test").toFile().also { it.deleteOnExit() }

    // ---- CSV ----

    @Test
    fun csvFormatsSamplesWithEmptyFields() {
        assertEquals("1793367101003,acc,0.123,-0.045,9.812,,\n", line(1793367101003, RecordingSensors.ACC, 0.123f.toDouble(), -0.045f.toDouble(), 9.812f.toDouble()))
        assertEquals("1793367101005,gyr,0.0012,-0.0004,0.0001,,\n", line(1793367101005, RecordingSensors.GYR, 0.0012, -0.0004, 0.0001))
        assertEquals("1793367101050,bar,1013.25,,,,\n", line(1793367101050, RecordingSensors.BAR, 1013.25))
        assertEquals(
            "1793367101000,loc,52.09073741,5.12142212,4.2,13.4,4.8\n",
            line(1793367101000, RecordingSensors.LOC, 52.09073741, 5.12142212, 4.2, 13.4, 4.8),
        )
        // unknown speed / accuracy
        assertEquals("1,loc,52.1,5.1,,,\n", line(1, RecordingSensors.LOC, 52.1, 5.1))
    }

    @Test
    fun csvNumberEdgeCases() {
        fun n(v: Double, d: Int = 6) = StringBuilder().also { RecordingCsv.appendNumber(it, v, d) }.toString()
        assertEquals("0", n(0.0))
        assertEquals("0", n(-0.0000001))
        assertEquals("-1", n(-1.0))
        assertEquals("10", n(10.0))
        assertEquals("0.000001", n(0.000001))
        assertEquals("0.05", n(0.05))
        assertEquals("-9.80665", n(-9.80665))
        assertEquals("", n(Double.NaN))
        assertEquals("", n(Double.POSITIVE_INFINITY))
        assertEquals("1.000001", n(1.0000009999))
        assertEquals("2", n(1.9999999))
    }

    @Test
    fun csvUsesDotUnderGermanLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("5,5", String.format("%.1f", 5.5)) // sanity: the default locale really is German
            val s = line(7, RecordingSensors.LOC, 52.09073741, -5.5, 4.25, 13.4, 4.8)
            assertEquals("7,loc,52.09073741,-5.5,4.25,13.4,4.8\n", s)
            assertFalse(s.substringAfter("loc").contains("5,5"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    // ---- chunk ids ----

    @Test
    fun chunkIdFormat() {
        val rid = "6f1c2a3e-1111-4222-8333-444455556666"
        assertEquals("$rid-000000", RecordingFiles.chunkId(rid, 0))
        assertEquals("$rid-000042", RecordingFiles.chunkId(rid, 42))
        assertEquals("$rid-1234567", RecordingFiles.chunkId(rid, 1234567))
        assertEquals(rid to 42, RecordingFiles.parseChunkId("$rid-000042"))
        assertNull(RecordingFiles.parseChunkId("nonsense"))
        assertTrue(RecordingFiles.isSafeChunkId("$rid-000042"))
        assertFalse(RecordingFiles.isSafeChunkId("../../etc/passwd-000001"))
        assertFalse(RecordingFiles.isSafeChunkId("$rid-000042/../x"))
    }

    // ---- measuredHz ----

    @Test
    fun measuredHzOverRollingWindow() {
        val start = 1_000_000L
        val w = RateWindow(startMs = start)
        // 100 Hz acc for 10 s, 1 Hz loc for 10 s
        for (i in 0 until 1000) {
            w.record(RecordingSensors.ACC, start + i * 10L)
            if (i % 100 == 0) w.record(RecordingSensors.LOC, start + i * 10L)
        }
        val now = start + 10_000
        assertEquals(100.0, w.measuredHz(RecordingSensors.ACC, now), 3.0) // current (empty) bucket costs ~2%
        assertEquals(1.0, w.measuredHz(RecordingSensors.LOC, now), 0.3)
        assertEquals(0.0, w.measuredHz(RecordingSensors.GYR, now), 0.0)
        // silence: window slides past the data
        assertEquals(0.0, w.measuredHz(RecordingSensors.ACC, now + 6000), 0.0)
        // a rate change shows up within the window
        for (i in 0 until 250) w.record(RecordingSensors.ACC, now + 6000 + i * 20L) // 50 Hz for 5 s
        assertEquals(50.0, w.measuredHz(RecordingSensors.ACC, now + 11_000), 3.0)
    }

    @Test
    fun measuredHzShortWindowUsesElapsedTime() {
        val w = RateWindow(startMs = 0)
        for (i in 0 until 100) w.record(RecordingSensors.ACC, i * 10L) // 1 s at 100 Hz
        assertEquals(100.0, w.measuredHz(RecordingSensors.ACC, 1000), 6.0)
        assertEquals(0.0, w.measuredHz(RecordingSensors.ACC, 100), 0.0) // under 0.5 s
    }

    // ---- finalization ----

    @Test
    fun finalizesLeftoverPartIntoGzAndJson() {
        val dir = tempDir()
        val rid = "6f1c2a3e-1111-4222-8333-444455556666"
        val chunkId = RecordingFiles.chunkId(rid, 3)
        val csv = RecordingCsv.HEADER + "\n" +
            "1000,acc,0.1,0.2,9.8,,\n" +
            "1005,gyr,0.01,0.02,0.03,,\n" +
            "1010,acc,0.1,0.2,9.7,,\n" +
            "1020,loc,52.1,5.1,4.2,13.4,4.8\n"
        // trailing partial line as left by a crash
        File(dir, "$chunkId.csv.part").writeText(csv + "1030,acc,0.1,0.", Charsets.UTF_8)

        val metas = RecordingFiles.finalizeLeftovers(dir)
        assertEquals(1, metas.size)
        val m = metas[0]
        assertEquals(chunkId, m.chunkId)
        assertEquals(rid, m.recordingId)
        assertEquals(3, m.seq)
        assertEquals(1000L, m.startTs)
        assertEquals(1020L, m.endTs)
        assertEquals(listOf(2L, 1L, 0L, 0L, 1L), m.counts.toList())

        assertFalse(File(dir, "$chunkId.csv.part").exists())
        val gz = File(dir, "$chunkId.csv.gz")
        assertTrue(gz.exists())
        assertEquals(gz.length(), m.bytes)
        assertEquals(csv, gunzip(gz))
        val json = File(dir, "$chunkId.json").readText()
        assertTrue(json, json.contains("\"chunkId\":\"$chunkId\""))
        assertTrue(json, json.contains("\"seq\":3"))
        assertTrue(json, json.contains("\"startTs\":1000,\"endTs\":1020"))
        assertTrue(json, json.contains("\"counts\":{\"acc\":2,\"gyr\":1,\"mag\":0,\"bar\":0,\"loc\":1}"))
        assertTrue(json, json.contains("\"bytes\":${gz.length()}"))
    }

    @Test
    fun emptyPartIsDroppedAndNextSeqSkipsExisting() {
        val dir = tempDir()
        val rid = "aaaa"
        File(dir, "${RecordingFiles.chunkId(rid, 0)}.csv.part").writeText(RecordingCsv.HEADER + "\n")
        assertTrue(RecordingFiles.finalizeLeftovers(dir).isEmpty())
        assertEquals(0, dir.listFiles()!!.size)

        File(dir, "${RecordingFiles.chunkId(rid, 4)}.json").writeText("{}")
        File(dir, "${RecordingFiles.chunkId("bbbb", 9)}.json").writeText("{}")
        assertEquals(5, RecordingFiles.nextSeq(dir, rid, 2))
        assertEquals(7, RecordingFiles.nextSeq(dir, rid, 7))
    }

    // ---- writer ----

    @Test
    fun writerRollsOverAndFinalizesOnStop() {
        val dir = tempDir()
        val rid = "cccc"
        var clock = 0L
        val opened = ArrayList<Int>()
        val w = RecordingWriter(dir, rid, chunkMs = 10_000, maxBytes = Long.MAX_VALUE, startSeq = 5, now = { clock }, flushPeriodMs = 20, onChunkOpened = { opened.add(it) })
        w.start()
        w.add(100, RecordingSensors.ACC, 1.0, 2.0, 3.0)
        Thread.sleep(150)
        clock = 11_000 // beyond chunk length: next flush rolls over
        Thread.sleep(150)
        w.add(200, RecordingSensors.GYR, 0.5, 0.5, 0.5)
        Thread.sleep(150)
        val latch = java.util.concurrent.CountDownLatch(1)
        w.stop { latch.countDown() }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // chunk 5 holds the acc sample; the gyr sample arrived after the rollover and opened chunk 6
        val ids = dir.listFiles()!!.filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.sorted()
        assertEquals(listOf(RecordingFiles.chunkId(rid, 5), RecordingFiles.chunkId(rid, 6)), ids)
        assertEquals(listOf(5, 6), opened)
        assertEquals(RecordingCsv.HEADER + "\n100,acc,1,2,3,,\n", gunzip(File(dir, "${ids[0]}.csv.gz")))
        assertEquals(RecordingCsv.HEADER + "\n200,gyr,0.5,0.5,0.5,,\n", gunzip(File(dir, "${ids[1]}.csv.gz")))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun writerStopsWhenStorageCapExceeded() {
        val dir = tempDir()
        val full = java.util.concurrent.CountDownLatch(1)
        val w = RecordingWriter(dir, "dddd", chunkMs = 60_000, maxBytes = 200, startSeq = 0, flushPeriodMs = 20, onStorageFull = { full.countDown() })
        w.start()
        repeat(100) { w.add(it.toLong(), RecordingSensors.ACC, 1.0, 2.0, 3.0) }
        assertTrue(full.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // data written so far is finalized, further samples are ignored
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".json") })
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        val done = java.util.concurrent.CountDownLatch(1)
        w.stop { done.countDown() }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    // ---- storage cap / options / notification ----

    @Test
    fun storageCapCheck() {
        assertFalse(RecordingFiles.isOverCap(10L * 1024 * 1024, 10))
        assertTrue(RecordingFiles.isOverCap(10L * 1024 * 1024 + 1, 10))
        assertFalse(RecordingFiles.isOverCap(0, 10))
    }

    @Test
    fun optionDefaultsAndClamping() {
        val d = RecordingConfig.resolve()
        assertEquals(RecordingConfig(), d)
        assertEquals(100.0, d.accelHz, 0.0)
        assertEquals(50.0, d.magHz, 0.0)

        val hi = RecordingConfig.resolve(1000.0, 1000.0, 1000.0, 1000.0, 1e9, 1e9, 1e9)
        assertEquals(200.0, hi.accelHz, 0.0)
        assertEquals(200.0, hi.gyroHz, 0.0)
        assertEquals(200.0, hi.magHz, 0.0)
        assertEquals(50.0, hi.baroHz, 0.0)
        assertEquals(60_000L, hi.locationIntervalMs)
        assertEquals(600, hi.chunkSeconds)
        assertEquals(10_000, hi.maxStorageMb)

        val lo = RecordingConfig.resolve(0.1, 0.1, 0.1, 0.1, 1.0, 1.0, 1.0)
        assertEquals(1.0, lo.accelHz, 0.0)
        assertEquals(1.0, lo.baroHz, 0.0)
        assertEquals(500L, lo.locationIntervalMs)
        assertEquals(10, lo.chunkSeconds)
        assertEquals(10, lo.maxStorageMb)
    }

    @Test
    fun optionValidationRejectsNonPositive() {
        for (bad in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                RecordingConfig.resolve(accelHz = bad)
                fail("accelHz=$bad should be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("accelHz"))
            }
        }
        try {
            RecordingConfig.resolve(chunkSeconds = -1.0)
            fail()
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun notificationTextReflectsWhatRuns() {
        val t = TrackingConfig()
        val r = RecordingConfig()
        assertEquals(t.notificationTitle to t.notificationText, ServiceNotificationText.compose(t, null))
        assertEquals(r.notificationTitle to r.notificationText, ServiceNotificationText.compose(null, r))
        val both = ServiceNotificationText.compose(t, r)
        assertEquals(t.notificationTitle, both.first)
        assertTrue(both.second.contains(t.notificationText) && both.second.contains(r.notificationText))
    }
}
