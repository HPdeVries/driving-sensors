package nl.flexlogic.drivingsensors

import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePrefs(val map: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(k: String, d: String?) = map[k] as? String ?: d
    override fun getStringSet(k: String, d: MutableSet<String>?) = d
    override fun getInt(k: String, d: Int) = map[k] as? Int ?: d
    override fun getLong(k: String, d: Long) = map[k] as? Long ?: d
    override fun getFloat(k: String, d: Float) = map[k] as? Float ?: d
    override fun getBoolean(k: String, d: Boolean) = map[k] as? Boolean ?: d
    override fun contains(k: String) = map.containsKey(k)
    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class ResumeTest {
    private val q = 29

    @Test
    fun noConsumerMeansNothing() {
        assertEquals(ResumeAction.NOTHING, ResumeDecision.decide(false, true, true, 35))
        assertEquals(ResumeAction.NOTHING, ResumeDecision.decide(false, false, false, 35))
    }

    @Test
    fun missingFineLocationIsUploadOnly() {
        assertEquals(ResumeAction.UPLOAD_ONLY, ResumeDecision.decide(true, false, true, 35))
        assertEquals(ResumeAction.UPLOAD_ONLY, ResumeDecision.decide(true, false, false, 26))
    }

    @Test
    fun backgroundLocationRequiredFromApi29WhenInBackground() {
        assertEquals(ResumeAction.UPLOAD_ONLY, ResumeDecision.decide(true, true, false, q))
        assertEquals(ResumeAction.UPLOAD_ONLY, ResumeDecision.decide(true, true, false, 35))
        assertEquals(ResumeAction.START_SERVICE, ResumeDecision.decide(true, true, true, 35))
        assertEquals(ResumeAction.START_SERVICE, ResumeDecision.decide(true, true, true, q))
    }

    @Test
    fun preApi29NeedsNoBackgroundLocation() {
        assertEquals(ResumeAction.START_SERVICE, ResumeDecision.decide(true, true, false, 28))
        assertEquals(ResumeAction.START_SERVICE, ResumeDecision.decide(true, true, false, 24))
    }

    @Test
    fun foregroundStartNeedsOnlyFineLocation() {
        assertEquals(ResumeAction.START_SERVICE, ResumeDecision.decide(true, true, false, 35, fromBackground = false))
    }

    @Test
    fun onlyExplicitCausesClearState() {
        val clearing = StopCause.values().filter { ResumeDecision.clearsPersistedState(it) }.toSet()
        assertEquals(
            setOf(StopCause.EXPLICIT_STOP, StopCause.STORAGE_FULL, StopCause.PERMISSION_LOST, StopCause.ERROR),
            clearing,
        )
        assertFalse(ResumeDecision.clearsPersistedState(StopCause.SERVICE_DESTROY))
        assertFalse(ResumeDecision.clearsPersistedState(StopCause.TIMEOUT))
        assertFalse(ResumeDecision.clearsPersistedState(StopCause.PERMISSION_MISSING_AT_RESUME))
    }

    @Test
    fun fgsStartNotAllowedDetectedByNameOnApi31Plus() {
        class Fake : RuntimeException()
        assertFalse(ResumeDecision.isFgsStartNotAllowed(Fake(), 35))
        assertFalse(ResumeDecision.isFgsStartNotAllowed(IllegalStateException(), 30))
    }

    @Test
    fun persistedStateSurvivesWithoutStopCall() {
        // Persisted flags are only ever written by begin/end; service lifecycle never touches them,
        // so a freshly parsed state equals the persisted one.
        val tracking = FakePrefs(mutableMapOf("active" to true, "sessionId" to "s1"))
        val recording = FakePrefs(mutableMapOf("recording" to true, "recordingId" to "r1", "nextSeq" to 4))
        val hb = FakePrefs(mutableMapOf("running" to true, "intervalSec" to 20, "seq" to 7L))
        assertEquals("s1", TrackingCoordinator.parseState(tracking)?.sessionId)
        assertEquals(4, RecordingCoordinator.parseState(recording)?.nextSeq)
        assertEquals(20, HeartbeatCoordinator.parseState(hb)?.intervalSec)
        assertNull(TrackingCoordinator.parseState(FakePrefs(mutableMapOf("active" to false, "sessionId" to "s1"))))
        assertNotNull(HeartbeatCoordinator.parseState(hb))
    }

    private fun serviceSource(): String =
        File("src/main/kotlin/nl/flexlogic/drivingsensors/TrackingService.kt").readText()

    /** Text of the function whose declaration line contains [signature], up to the next 4-space-indented "}". */
    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val end = src.indexOf("\n    }\n", start)
        return src.substring(start, end)
    }

    @Test
    fun serviceLifecyclePathsNeverClearPersistedState() {
        val src = serviceSource()
        val forbidden = listOf("RecordingCoordinator.end(", "TrackingCoordinator.end(", "HeartbeatCoordinator.end(", ".edit()", "putBoolean")
        for (sig in listOf("override fun onDestroy", "private fun teardown()", "private fun teardownRecording", "override fun onTimeout", "override fun onStartCommand", "private fun beginSession", "private fun beginRecording")) {
            val b = body(src, sig)
            for (f in forbidden) assertFalse("$sig must not contain $f", b.contains(f))
        }
        // startRecorder may only end the recording for explicit reasons (writer callbacks).
        val rec = body(src, "private fun startRecorder")
        assertFalse(rec.contains("PERMISSION_LOST"))
        assertTrue(rec.contains("STORAGE_FULL"))
    }
}
