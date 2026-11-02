package nl.flexlogic.drivingsensors

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class UploaderTest {

    // ---- fakes ----

    class Request(val url: String, val headers: Map<String, String>, val body: String) {
        val json: JSONObject get() = JSONObject(body)
    }

    class FakeTransport : HttpTransport {
        val requests = ArrayList<Request>()
        var respond: (Request) -> Int = { 200 }
        var failWith: IOException? = null

        override fun post(url: String, headers: Map<String, String>, body: (OutputStream) -> Unit): Int {
            val bos = ByteArrayOutputStream()
            body(bos)
            // The body writer must be repeatable (length pass + send pass).
            val again = ByteArrayOutputStream()
            body(again)
            assertEquals(bos.toString("UTF-8"), again.toString("UTF-8"))
            val r = Request(url, headers, bos.toString("UTF-8"))
            requests.add(r)
            failWith?.let { throw it }
            return respond(r)
        }
    }

    class FakeStore : UploadStore {
        var config: UploadSettings? = UploadSettings("https://example.test/api", "dev-1", eventBatchSize = 500)
        var token: String? = "secret-token"
        var status = UploadStatusData()
        var gen = 1L
        override fun loadConfig() = config
        override fun loadToken() = token
        override fun loadStatus() = status
        override fun updateStatus(f: (UploadStatusData) -> UploadStatusData): UploadStatusData {
            status = f(status)
            return status
        }

        override fun generation() = gen
    }

    class FakeEvents(var events: MutableList<StoredEvent>) : EventSource {
        var acked = -1L
        override fun read(limit: Int) = events.take(limit)
        override fun ack(upToId: Long) {
            acked = upToId
            events.removeAll { it.id <= upToId }
        }

        override fun count() = events.size.toLong()
    }

    object Device : DeviceInfo {
        override val model = "Test Phone"
        override val osVersion = "14"
        override fun sensorInfo() = JSONObject().put("acc", "Test acc")
    }

    private lateinit var dir: File
    private lateinit var store: FakeStore
    private lateinit var transport: FakeTransport
    private lateinit var events: FakeEvents
    private var clock = 1_000_000L
    private lateinit var uploader: Uploader

    private fun ev(id: Long) = StoredEvent(id, EventRecord(id * 10, EventType.LOCATION, "s1", 52.0, 5.0, 3.0, null, null))

    private fun addChunk(id: String, startTs: Long, seq: Int, content: String = "csv-$id") {
        File(dir, id + RecordingFiles.GZ_SUFFIX).outputStream().use { GZIPOutputStream(it).use { g -> g.write(content.toByteArray()) } }
        val meta = RecordingChunkMeta(id, "rec", seq, startTs, startTs + 1000, 10, LongArray(RecordingSensors.NAMES.size))
        File(dir, id + RecordingFiles.JSON_SUFFIX).writeText(meta.toJson())
    }

    private fun chunkId(seq: Int) = RecordingFiles.chunkId("rec", seq)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("upl").toFile().also { it.deleteOnExit() }
        store = FakeStore()
        transport = FakeTransport()
        events = FakeEvents(mutableListOf())
        clock = 1_000_000L
        uploader = Uploader(store, events, FileChunkSource(dir), transport, Device, { Base64.getEncoder().wrap(it) }, { clock })
    }

    // ---- tests ----

    @Test
    fun successDeletesChunkAndAcksEventsUpToHighestId() {
        events.events = mutableListOf(ev(3), ev(5), ev(9))
        addChunk(chunkId(0), 100, 0)
        val outcome = uploader.run(false)
        assertTrue(outcome.ran)
        assertEquals(9L, events.acked)
        assertEquals(0, FileChunkSource(dir).countPending())
        assertFalse(File(dir, chunkId(0) + ".csv.gz").exists())
        assertEquals(3L, store.status.uploadedEvents)
        assertEquals(1L, store.status.uploadedChunks)
        assertEquals(UploadStates.IDLE, store.status.state)
        assertEquals(clock, store.status.lastSuccessAt)
        assertEquals(200, store.status.lastHttpStatus)
    }

    @Test
    fun conflict409CountsAsSuccess() {
        events.events = mutableListOf(ev(1))
        addChunk(chunkId(0), 100, 0)
        transport.respond = { 409 }
        uploader.run(false)
        assertEquals(1L, events.acked)
        assertEquals(0, FileChunkSource(dir).countPending())
        assertEquals(UploadStates.IDLE, store.status.state)
    }

    @Test
    fun eventsAreSentBeforeChunks() {
        events.events = mutableListOf(ev(1))
        addChunk(chunkId(0), 100, 0)
        uploader.run(false)
        assertEquals(listOf("https://example.test/api/events", "https://example.test/api/chunks"), transport.requests.map { it.url })
    }

    @Test
    fun headersFollowTheSpec() {
        events.events = mutableListOf(ev(1))
        uploader.run(false)
        val h = transport.requests[0].headers
        assertEquals("Bearer secret-token", h["Authorization"])
        assertEquals("dev-1", h["X-Device-Id"])
        assertEquals(PLUGIN_VERSION, h["X-Plugin-Version"])
        assertEquals("android", h["X-Platform"])
        assertEquals("application/json; charset=utf-8", h["Content-Type"])
    }

    @Test
    fun eventsBodyHasExpectedKeys() {
        events.events = mutableListOf(ev(1), StoredEvent(2, EventRecord(20, EventType.TRIP_START, "s1")))
        uploader.run(false)
        val o = transport.requests[0].json
        assertEquals("dev-1", o.getString("deviceId"))
        assertEquals("android", o.getString("platform"))
        assertEquals(PLUGIN_VERSION, o.getString("pluginVersion"))
        val arr = o.getJSONArray("events")
        assertEquals(2, arr.length())
        val e0 = arr.getJSONObject(0)
        assertEquals(1L, e0.getLong("id"))
        assertEquals("LOCATION", e0.getString("type"))
        assertEquals("s1", e0.getString("sessionId"))
        assertEquals(52.0, e0.getDouble("lat"), 0.0)
        assertFalse(arr.getJSONObject(1).has("lat")) // nulls omitted
        assertEquals(2L, arr.getJSONObject(1).getLong("id"))
    }

    @Test
    fun chunkBodyHasRecordingChunkKeysAndDecodableData() {
        addChunk(chunkId(0), 100, 0, "hello-csv")
        uploader.run(false)
        val o = transport.requests[0].json
        for (k in listOf("chunkId", "recordingId", "seq", "startTs", "endTs", "bytes", "counts", "format", "encoding", "platform", "deviceModel", "osVersion", "pluginVersion", "sensorInfo", "data", "deviceId")) {
            assertTrue("missing $k", o.has(k))
        }
        assertEquals("csv-v1", o.getString("format"))
        assertEquals("gzip+base64", o.getString("encoding"))
        assertEquals("Test Phone", o.getString("deviceModel"))
        assertEquals("dev-1", o.getString("deviceId"))
        assertEquals("Test acc", o.getJSONObject("sensorInfo").getString("acc"))
        val gz = Base64.getDecoder().decode(o.getString("data"))
        val text = java.util.zip.GZIPInputStream(gz.inputStream()).bufferedReader().readText()
        assertEquals("hello-csv", text)
    }

    @Test
    fun chunksAreSentOldestFirst() {
        addChunk(chunkId(1), 200, 1)
        addChunk(chunkId(0), 100, 0)
        uploader.run(false)
        assertEquals(listOf(chunkId(0), chunkId(1)), transport.requests.map { it.json.getString("chunkId") })
    }

    @Test
    fun unauthorizedStopsEverything() {
        events.events = mutableListOf(ev(1))
        addChunk(chunkId(0), 100, 0)
        transport.respond = { 401 }
        uploader.run(false)
        assertEquals(1, transport.requests.size)
        assertEquals(UploadStates.AUTH_FAILED, store.status.state)
        assertEquals(401, store.status.lastHttpStatus)
        assertEquals(1, FileChunkSource(dir).countPending()) // data kept
        assertEquals(1, events.events.size)
        // Later runs, even forced, do nothing until configureUpload resets the state.
        uploader.run(true)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun forbidden403IsAuthFailureToo() {
        events.events = mutableListOf(ev(1))
        transport.respond = { 403 }
        uploader.run(false)
        assertEquals(UploadStates.AUTH_FAILED, store.status.state)
    }

    @Test
    fun rejectedChunkIsRenamedAndNextChunkStillSent() {
        addChunk(chunkId(0), 100, 0)
        addChunk(chunkId(1), 200, 1)
        transport.respond = { if (it.json.getString("chunkId") == chunkId(0)) 413 else 200 }
        uploader.run(false)
        assertEquals(2, transport.requests.size)
        assertTrue(File(dir, chunkId(0) + ".rejected.json").isFile)
        assertTrue(File(dir, chunkId(0) + ".csv.gz").isFile) // gz kept
        assertFalse(File(dir, chunkId(0) + ".json").exists())
        assertEquals(0, FileChunkSource(dir).countPending())
        assertEquals(1, FileChunkSource(dir).countRejected())
        assertEquals(1L, store.status.uploadedChunks)
        assertEquals(UploadStates.IDLE, store.status.state)
        // Not retried.
        uploader.run(false)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun rejectedChunksStillCountForStorageButNotForListing() {
        addChunk(chunkId(0), 100, 0)
        val before = RecordingFiles.totalBytes(dir)
        RecordingFiles.reject(dir, chunkId(0))
        assertEquals(before, RecordingFiles.totalBytes(dir))
        assertTrue(RecordingFiles.listPending(dir).isEmpty())
        assertEquals(1, RecordingFiles.countRejected(dir))
        // nextSeq sees the rejected chunk, so its id is never reused.
        assertEquals(1, RecordingFiles.nextSeq(dir, "rec", 0))
        RecordingFiles.delete(dir, chunkId(0))
        assertEquals(0L, RecordingFiles.totalBytes(dir))
    }

    @Test
    fun serverErrorBacksOffWithDoublingCappedAndResetsAfterSuccess() {
        events.events = mutableListOf(ev(1))
        transport.respond = { 500 }
        uploader.run(false)
        assertEquals(UploadStates.BACKOFF, store.status.state)
        assertEquals(clock + 30_000, store.status.nextAttemptAt)
        assertEquals(500, store.status.lastHttpStatus)
        assertEquals(1L, events.events[0].id) // not acked

        // Within the window nothing is sent, and retryAt is reported.
        val skipped = uploader.run(false)
        assertFalse(skipped.ran)
        assertEquals(clock + 30_000, skipped.retryAt)
        assertEquals(1, transport.requests.size)

        val expected = listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_800_000L, 1_800_000L)
        for (d in expected) {
            clock = store.status.nextAttemptAt!!
            uploader.run(false)
            assertEquals(clock + d, store.status.nextAttemptAt)
        }

        // forced run ignores backoff, and a success resets it
        transport.respond = { 200 }
        uploader.run(true)
        assertEquals(UploadStates.IDLE, store.status.state)
        assertNull(store.status.nextAttemptAt)
        assertEquals(0L, store.status.backoffMs)
        events.events = mutableListOf(ev(2))
        transport.respond = { 503 }
        uploader.run(false)
        assertEquals(clock + 30_000, store.status.nextAttemptAt)
    }

    @Test
    fun networkErrorIsBackoff() {
        events.events = mutableListOf(ev(1))
        transport.failWith = java.net.UnknownHostException("nope.test")
        uploader.run(false)
        assertEquals(UploadStates.BACKOFF, store.status.state)
        assertTrue(store.status.lastError!!.contains("UnknownHostException"))
        assertNull(store.status.lastHttpStatus)
        assertFalse(store.status.lastError!!.contains("secret-token"))
    }

    @Test
    fun redirectIsAnError() {
        events.events = mutableListOf(ev(1))
        transport.respond = { 302 }
        uploader.run(false)
        assertEquals(UploadStates.BACKOFF, store.status.state)
        assertEquals(1, events.events.size)
    }

    @Test
    fun eventBatchRejectedIsHalvedAndRetried() {
        events.events = (1L..8L).map { ev(it) }.toMutableList()
        var calls = 0
        transport.respond = { if (calls++ == 0) 400 else 200 }
        uploader.run(false)
        val sizes = transport.requests.map { it.json.getJSONArray("events").length() }
        assertEquals(listOf(8, 4, 4), sizes.take(3))
        assertEquals(0, events.events.size)
        assertEquals(8L, store.status.uploadedEvents)
    }

    @Test
    fun singleRejectedEventGoesToBackoffAndIsNeverDropped() {
        events.events = mutableListOf(ev(1), ev(2))
        transport.respond = { 422 }
        uploader.run(false)
        // 2 -> 1 -> rejected single event
        assertEquals(listOf(2, 1), transport.requests.map { it.json.getJSONArray("events").length() })
        assertEquals(UploadStates.BACKOFF, store.status.state)
        assertEquals(2, events.events.size)
        assertTrue(store.status.lastError!!.contains("rejected"))
    }

    @Test
    fun eventBatchSizeLimitsRequestSize() {
        store.config = store.config!!.copy(eventBatchSize = 2)
        events.events = (1L..5L).map { ev(it) }.toMutableList()
        uploader.run(false)
        assertEquals(listOf(2, 2, 1), transport.requests.map { it.json.getJSONArray("events").length() })
        assertEquals(5L, events.acked)
    }

    @Test
    fun disabledFlagsSkipTheirData() {
        store.config = store.config!!.copy(uploadEvents = false)
        events.events = mutableListOf(ev(1))
        addChunk(chunkId(0), 100, 0)
        uploader.run(false)
        assertEquals(listOf("https://example.test/api/chunks"), transport.requests.map { it.url })
        assertEquals(1, events.events.size)
    }

    @Test
    fun notConfiguredDoesNothing() {
        store.config = null
        assertFalse(uploader.run(true).ran)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun missingTokenIsAuthFailed() {
        store.token = null
        events.events = mutableListOf(ev(1))
        uploader.run(false)
        assertEquals(UploadStates.AUTH_FAILED, store.status.state)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun onlyOneRunAtATime() {
        events.events = mutableListOf(ev(1))
        var nested: RunOutcome? = null
        transport.respond = {
            nested = uploader.run(true)
            200
        }
        uploader.run(false)
        assertTrue(nested!!.busy)
        assertFalse(uploader.isRunning)
    }

    // ---- validation ----

    private fun validate(url: String? = "https://h.test/rest/T/", id: String? = "d", token: String? = "t", batch: Int? = null) =
        UploadSettings.validate(url, id, token, null, null, null, batch)

    @Test
    fun validationRequiresHttpsAndStripsTrailingSlash() {
        assertEquals("https://h.test/rest/T", validate().baseUrl)
        assertEquals("https://h.test", validate("https://h.test///").baseUrl)
        for (bad in listOf("http://h.test", "ftp://x", "h.test", "", null, "https://")) {
            try {
                validate(bad)
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun validationRequiresIdAndTokenAndClampsBatch() {
        for ((id, tok) in listOf(null to "t", "" to "t", "d" to null, "d" to "", "d\n" to "t", "d" to "t\r\nX: y")) {
            try {
                validate(id = id, token = tok)
                fail("accepted $id/$tok")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(500, validate().eventBatchSize)
        assertEquals(1, validate(batch = 0).eventBatchSize)
        assertEquals(1000, validate(batch = 5000).eventBatchSize)
        val s = validate()
        assertTrue(s.uploadEvents && s.uploadRecordings && !s.wifiOnly)
    }

    // ---- heartbeat ----

    class FakeEnv : HeartbeatEnv {
        override fun processUptimeSec() = 123L
        override fun appState() = "background"
        override fun batteryLevel() = 77
        override fun charging() = true
        override fun network() = "wifi"
        override fun tracking() = true
        override fun inTrip() = true
        override fun recording() = false
        override fun pendingEvents() = 42L
        override fun pendingChunks() = 3
    }

    private fun heartbeat() = HeartbeatSender(store, transport, Device, FakeEnv(), { clock })

    @Test
    fun heartbeatPayloadHasAllFields() {
        val hb = heartbeat()
        assertTrue(hb.beat(7, 30))
        val r = transport.requests[0]
        assertEquals("https://example.test/api/heartbeat", r.url)
        assertEquals("Bearer secret-token", r.headers["Authorization"])
        val o = r.json
        val expectedKeys = setOf(
            "deviceId", "platform", "pluginVersion", "ts", "seq", "intervalSec", "processUptimeSec", "appState",
            "batteryLevel", "charging", "network", "tracking", "inTrip", "recording", "pendingEvents", "pendingChunks", "uploadState",
        )
        assertEquals(expectedKeys, o.keys().asSequence().toSet())
        assertEquals("dev-1", o.getString("deviceId"))
        assertEquals("android", o.getString("platform"))
        assertEquals(clock, o.getLong("ts"))
        assertEquals(7L, o.getLong("seq"))
        assertEquals(30, o.getInt("intervalSec"))
        assertEquals(123L, o.getLong("processUptimeSec"))
        assertEquals("background", o.getString("appState"))
        assertEquals(77, o.getInt("batteryLevel"))
        assertTrue(o.getBoolean("charging"))
        assertEquals("wifi", o.getString("network"))
        assertTrue(o.getBoolean("tracking"))
        assertTrue(o.getBoolean("inTrip"))
        assertFalse(o.getBoolean("recording"))
        assertEquals(42L, o.getLong("pendingEvents"))
        assertEquals(3, o.getInt("pendingChunks"))
        assertEquals("idle", o.getString("uploadState"))
        assertEquals(1L, hb.counters.sent)
        assertEquals(1L, hb.counters.succeeded)
        assertEquals(clock, hb.counters.lastSuccessAt)
    }

    @Test
    fun heartbeat401SetsAuthFailedAndLaterBeatsSkipSending() {
        val hb = heartbeat()
        transport.respond = { 401 }
        assertFalse(hb.beat(1, 30))
        assertEquals(UploadStates.AUTH_FAILED, store.status.state)
        assertEquals("auth_failed", hb.counters.lastError)
        assertEquals(401, hb.counters.lastHttpStatus)
        assertEquals(1, transport.requests.size)
        transport.respond = { 200 }
        assertFalse(hb.beat(2, 30))
        assertEquals(1, transport.requests.size) // skipped
        assertEquals(2L, hb.counters.sent)
        assertEquals(0L, hb.counters.succeeded)
        assertEquals("auth_failed", hb.counters.lastError)
    }

    @Test
    fun heartbeatFailureIsCountedNotQueued() {
        val hb = heartbeat()
        transport.respond = { 500 }
        assertFalse(hb.beat(1, 30))
        assertEquals("HTTP 500", hb.counters.lastError)
        assertEquals(1L, hb.counters.sent)
        transport.failWith = IOException("down")
        assertFalse(hb.beat(2, 30))
        assertTrue(hb.counters.lastError!!.contains("down"))
        assertEquals(2L, hb.counters.sent)
        assertEquals(UploadStates.IDLE, store.status.state) // heartbeat errors do not trigger upload backoff
    }

    @Test
    fun heartbeatUsesUploadStateAndRequiresConfig() {
        store.status = store.status.copy(state = UploadStates.BACKOFF)
        val hb = heartbeat()
        hb.beat(1, 30)
        assertEquals("backoff", transport.requests[0].json.getString("uploadState"))
        store.config = null
        assertFalse(hb.beat(2, 30))
        assertEquals(1, transport.requests.size)
    }
}
