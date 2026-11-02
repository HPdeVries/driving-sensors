package nl.flexlogic.drivingsensors

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

object UploadHttp {
    const val BACKOFF_START_MS = 30_000L
    const val BACKOFF_MAX_MS = 30 * 60_000L

    fun isSuccess(status: Int) = status in 200..299 || status == 409
    fun isAuthFailure(status: Int) = status == 401 || status == 403
    fun isRejection(status: Int) = status == 400 || status == 413 || status == 422

    fun headers(cfg: UploadSettings, token: String, device: DeviceInfo): Map<String, String> = linkedMapOf(
        "Authorization" to "Bearer $token",
        "X-Device-Id" to cfg.deviceId,
        "X-Plugin-Version" to device.pluginVersion,
        "X-Platform" to device.platform,
        "Content-Type" to "application/json; charset=utf-8",
        "Accept" to "application/json",
    )

    fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
}

/** Result of [Uploader.run]. [retryAt] is set when the run was skipped or ended in backoff. */
data class RunOutcome(val ran: Boolean, val busy: Boolean = false, val retryAt: Long? = null)

/**
 * One upload run per [run] call: events first, then chunks. Only one run at a time per process.
 * Body writers passed to the transport can be invoked repeatedly (length pass + send pass).
 */
class Uploader(
    private val store: UploadStore,
    private val events: EventSource,
    private val chunks: ChunkSource,
    private val transport: HttpTransport,
    private val device: DeviceInfo,
    /** Wraps a stream so that bytes written are base64 encoded; close() must flush the final quantum. */
    private val base64: (OutputStream) -> OutputStream,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    private enum class Flow { CONTINUE, STOP }

    fun run(force: Boolean): RunOutcome {
        if (!running.compareAndSet(false, true)) return RunOutcome(ran = false, busy = true)
        try {
            return runLocked(force)
        } finally {
            running.set(false)
        }
    }

    private fun runLocked(force: Boolean): RunOutcome {
        val cfg = store.loadConfig() ?: return RunOutcome(false)
        val before = store.loadStatus()
        if (before.state == UploadStates.AUTH_FAILED) return RunOutcome(false)
        val next = before.nextAttemptAt
        if (!force && next != null && next > now()) return RunOutcome(false, retryAt = next)
        val token = store.loadToken()
        if (token == null) {
            authFailed(null, "token unavailable")
            return RunOutcome(true)
        }
        val gen = store.generation()
        val headers = UploadHttp.headers(cfg, token, device)
        var flow = Flow.CONTINUE
        try {
            if (cfg.uploadEvents) flow = uploadEvents(cfg, headers, gen)
            if (flow == Flow.CONTINUE && cfg.uploadRecordings) flow = uploadChunks(cfg, headers, gen)
        } catch (e: Exception) {
            backoff(null, UploadHttp.describe(e))
            flow = Flow.STOP
        }
        if (store.generation() != gen) return RunOutcome(true)
        val after = store.loadStatus()
        if (flow == Flow.CONTINUE) {
            store.updateStatus { it.copy(state = UploadStates.IDLE, nextAttemptAt = null, backoffMs = 0) }
            return RunOutcome(true)
        }
        return RunOutcome(true, retryAt = if (after.state == UploadStates.BACKOFF) after.nextAttemptAt else null)
    }

    // ---- events ----

    private fun uploadEvents(cfg: UploadSettings, headers: Map<String, String>, gen: Long): Flow {
        var batch = cfg.eventBatchSize
        while (true) {
            if (store.generation() != gen) return Flow.STOP
            val list = events.read(batch)
            if (list.isEmpty()) return Flow.CONTINUE
            val payload = JSONObject()
                .put("deviceId", cfg.deviceId)
                .put("platform", device.platform)
                .put("pluginVersion", device.pluginVersion)
            val arr = JSONArray()
            list.forEach { arr.put(EventJson.fill(JSONObject(), it)) }
            payload.put("events", arr)
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            val status = try {
                transport.post(cfg.baseUrl + "/events", headers) { it.write(bytes) }
            } catch (e: IOException) {
                backoff(null, UploadHttp.describe(e))
                return Flow.STOP
            }
            when {
                UploadHttp.isSuccess(status) -> {
                    events.ack(list.last().id)
                    success(status) { it.copy(uploadedEvents = it.uploadedEvents + list.size) }
                }
                UploadHttp.isAuthFailure(status) -> {
                    authFailed(status, "HTTP $status")
                    return Flow.STOP
                }
                UploadHttp.isRejection(status) -> {
                    if (list.size > 1) {
                        batch = maxOf(1, list.size / 2)
                    } else {
                        backoff(status, "event ${list[0].id} rejected: HTTP $status")
                        return Flow.STOP
                    }
                }
                else -> {
                    backoff(status, "HTTP $status")
                    return Flow.STOP
                }
            }
        }
    }

    // ---- chunks ----

    private fun uploadChunks(cfg: UploadSettings, headers: Map<String, String>, gen: Long): Flow {
        for (chunk in chunks.pending()) {
            if (store.generation() != gen) return Flow.STOP
            // May have been acked by the app in the meantime.
            if (chunks.openData(chunk.chunkId)?.also { it.close() } == null) continue
            val head = ChunkJson.fillHeader(JSONObject(), chunk.metaJson, device).put("deviceId", cfg.deviceId).toString()
            val prefix = (head.substring(0, head.length - 1) + ",\"data\":\"").toByteArray(Charsets.UTF_8)
            val suffix = "\"}".toByteArray(Charsets.UTF_8)
            val status = try {
                transport.post(cfg.baseUrl + "/chunks", headers) { out ->
                    out.write(prefix)
                    val input = chunks.openData(chunk.chunkId) ?: throw IOException("chunk ${chunk.chunkId} vanished")
                    input.use {
                        val b64 = base64(NonClosingOutputStream(out))
                        it.copyTo(b64, 8192)
                        b64.close()
                    }
                    out.write(suffix)
                }
            } catch (e: IOException) {
                backoff(null, UploadHttp.describe(e))
                return Flow.STOP
            }
            when {
                UploadHttp.isSuccess(status) -> {
                    chunks.delete(chunk.chunkId)
                    success(status) { it.copy(uploadedChunks = it.uploadedChunks + 1) }
                }
                UploadHttp.isAuthFailure(status) -> {
                    authFailed(status, "HTTP $status")
                    return Flow.STOP
                }
                UploadHttp.isRejection(status) -> {
                    chunks.reject(chunk.chunkId)
                    val t = now()
                    store.updateStatus {
                        it.copy(lastErrorAt = t, lastError = "chunk ${chunk.chunkId} rejected: HTTP $status", lastHttpStatus = status)
                    }
                }
                else -> {
                    backoff(status, "HTTP $status")
                    return Flow.STOP
                }
            }
        }
        return Flow.CONTINUE
    }

    // ---- status updates ----

    private fun success(status: Int, f: (UploadStatusData) -> UploadStatusData) {
        val t = now()
        store.updateStatus {
            f(it).copy(
                state = UploadStates.IDLE,
                lastSuccessAt = t,
                lastHttpStatus = status,
                nextAttemptAt = null,
                backoffMs = 0,
            )
        }
    }

    private fun authFailed(status: Int?, message: String) {
        val t = now()
        store.updateStatus {
            it.copy(
                state = UploadStates.AUTH_FAILED,
                lastError = message,
                lastErrorAt = t,
                lastHttpStatus = status ?: it.lastHttpStatus,
                nextAttemptAt = null,
            )
        }
    }

    private fun backoff(status: Int?, message: String) {
        val t = now()
        store.updateStatus {
            val delay = if (it.backoffMs <= 0) UploadHttp.BACKOFF_START_MS else minOf(it.backoffMs * 2, UploadHttp.BACKOFF_MAX_MS)
            it.copy(
                state = UploadStates.BACKOFF,
                backoffMs = delay,
                nextAttemptAt = t + delay,
                lastError = message,
                lastErrorAt = t,
                lastHttpStatus = status ?: it.lastHttpStatus,
            )
        }
    }
}

/** Passes writes through but ignores close(), so a base64 wrapper can be finished without closing the request. */
class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
    }

    override fun close() {
        flush()
    }
}

// ---- heartbeat ----

/** Live values for the heartbeat payload (Android implementation reads system services). */
interface HeartbeatEnv {
    fun processUptimeSec(): Long
    fun appState(): String
    fun batteryLevel(): Int
    fun charging(): Boolean
    fun network(): String
    fun tracking(): Boolean
    fun inTrip(): Boolean
    fun recording(): Boolean
    fun pendingEvents(): Long
    fun pendingChunks(): Int
}

data class HeartbeatCounters(
    val sent: Long = 0,
    val succeeded: Long = 0,
    val lastSentAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    val lastHttpStatus: Int? = null,
)

/** Builds and sends heartbeats. Never queues; a failure is only counted. */
class HeartbeatSender(
    private val store: UploadStore,
    private val transport: HttpTransport,
    private val device: DeviceInfo,
    private val env: HeartbeatEnv,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    var counters = HeartbeatCounters()
        private set

    fun resetCounters() {
        counters = HeartbeatCounters()
    }

    fun buildPayload(cfg: UploadSettings, seq: Long, intervalSec: Int, uploadState: String): JSONObject = JSONObject()
        .put("deviceId", cfg.deviceId)
        .put("platform", device.platform)
        .put("pluginVersion", device.pluginVersion)
        .put("ts", now())
        .put("seq", seq)
        .put("intervalSec", intervalSec)
        .put("processUptimeSec", env.processUptimeSec())
        .put("appState", env.appState())
        .put("batteryLevel", env.batteryLevel())
        .put("charging", env.charging())
        .put("network", env.network())
        .put("tracking", env.tracking())
        .put("inTrip", env.inTrip())
        .put("recording", env.recording())
        .put("pendingEvents", env.pendingEvents())
        .put("pendingChunks", env.pendingChunks())
        .put("uploadState", uploadState)

    /** Sends one heartbeat. Returns true when the server accepted it. */
    fun beat(seq: Long, intervalSec: Int): Boolean {
        val t = now()
        val cfg = store.loadConfig()
        val status = store.loadStatus()
        val token = store.loadToken()
        if (cfg == null || token == null) return fail(t, null, "not configured")
        if (status.state == UploadStates.AUTH_FAILED) return fail(t, null, "auth_failed")
        val body = buildPayload(cfg, seq, intervalSec, status.state).toString().toByteArray(Charsets.UTF_8)
        val code = try {
            transport.post(cfg.baseUrl + "/heartbeat", UploadHttp.headers(cfg, token, device)) { it.write(body) }
        } catch (e: Exception) {
            return fail(t, null, UploadHttp.describe(e))
        }
        return when {
            UploadHttp.isSuccess(code) -> {
                val c = counters
                counters = c.copy(sent = c.sent + 1, succeeded = c.succeeded + 1, lastSentAt = t, lastSuccessAt = t, lastHttpStatus = code)
                true
            }
            UploadHttp.isAuthFailure(code) -> {
                store.updateStatus {
                    it.copy(state = UploadStates.AUTH_FAILED, lastError = "HTTP $code", lastErrorAt = t, lastHttpStatus = code, nextAttemptAt = null)
                }
                fail(t, code, "auth_failed")
            }
            else -> fail(t, code, "HTTP $code")
        }
    }

    private fun fail(t: Long, code: Int?, message: String): Boolean {
        val c = counters
        counters = c.copy(sent = c.sent + 1, lastSentAt = t, lastError = message, lastHttpStatus = code ?: c.lastHttpStatus)
        return false
    }
}
