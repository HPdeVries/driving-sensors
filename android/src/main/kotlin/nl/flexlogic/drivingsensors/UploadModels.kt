package nl.flexlogic.drivingsensors

import java.io.InputStream
import java.io.OutputStream

object UploadStates {
    const val DISABLED = "disabled"
    const val IDLE = "idle"
    const val UPLOADING = "uploading"
    const val WAITING_NETWORK = "waiting_network"
    const val BACKOFF = "backoff"
    const val AUTH_FAILED = "auth_failed"
}

/** Non-secret upload configuration. */
data class UploadSettings(
    val baseUrl: String,
    val deviceId: String,
    val uploadRecordings: Boolean = true,
    val uploadEvents: Boolean = true,
    val wifiOnly: Boolean = false,
    val eventBatchSize: Int = 500,
) {
    companion object {
        /** Validates and normalises the user input; throws [IllegalArgumentException] (INVALID_ARGUMENT). */
        fun validate(
            endpointBaseUrl: String?,
            deviceId: String?,
            token: String?,
            uploadRecordings: Boolean?,
            uploadEvents: Boolean?,
            wifiOnly: Boolean?,
            eventBatchSize: Int?,
        ): UploadSettings {
            val url = endpointBaseUrl?.trim().orEmpty()
            require(url.startsWith("https://", ignoreCase = true) && url.length > "https://".length) {
                "endpointBaseUrl must be an https:// URL"
            }
            require(!deviceId.isNullOrEmpty()) { "deviceId is required" }
            require(!token.isNullOrEmpty()) { "token is required" }
            require(deviceId.none { it.code < 0x20 || it.code == 0x7f }) { "deviceId contains control characters" }
            require(token.none { it.code < 0x20 || it.code == 0x7f }) { "token contains control characters" }
            return UploadSettings(
                baseUrl = "https://" + url.substring("https://".length).trimEnd('/'),
                deviceId = deviceId,
                uploadRecordings = uploadRecordings ?: true,
                uploadEvents = uploadEvents ?: true,
                wifiOnly = wifiOnly ?: false,
                eventBatchSize = (eventBatchSize ?: 500).coerceIn(1, 1000),
            )
        }
    }
}

/** Status fields persisted between runs. `uploading` is never persisted (overlaid at read time). */
data class UploadStatusData(
    val state: String = UploadStates.IDLE,
    val uploadedChunks: Long = 0,
    val uploadedEvents: Long = 0,
    val lastSuccessAt: Long? = null,
    val lastErrorAt: Long? = null,
    val lastError: String? = null,
    val lastHttpStatus: Int? = null,
    val nextAttemptAt: Long? = null,
    val backoffMs: Long = 0,
)

/** Persistence of config, token and status (SharedPreferences + Keystore in production). */
interface UploadStore {
    fun loadConfig(): UploadSettings?
    fun loadToken(): String?
    fun loadStatus(): UploadStatusData

    /** Atomically read-modify-write the status. */
    fun updateStatus(f: (UploadStatusData) -> UploadStatusData): UploadStatusData

    /** Changes whenever the config is replaced or cleared. */
    fun generation(): Long
}

/** One HTTPS POST. Throws [java.io.IOException] on network problems. [body] may be invoked more than once. */
interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: (OutputStream) -> Unit): Int
}

interface EventSource {
    /** Oldest events first. */
    fun read(limit: Int): List<StoredEvent>
    fun ack(upToId: Long)
    fun count(): Long
}

interface ChunkSource {
    /** Deliverable (non-rejected) chunks, oldest first. */
    fun pending(): List<RecordingFiles.PendingChunk>
    fun openData(chunkId: String): InputStream?
    fun delete(chunkId: String)
    fun reject(chunkId: String)
    fun countPending(): Int
    fun countRejected(): Int
}

/** Device details for payloads. */
interface DeviceInfo {
    val platform: String get() = "android"
    val pluginVersion: String get() = PLUGIN_VERSION
    val model: String
    val osVersion: String
    fun sensorInfo(): org.json.JSONObject
}
