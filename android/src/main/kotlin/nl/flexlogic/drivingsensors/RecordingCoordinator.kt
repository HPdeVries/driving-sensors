package nl.flexlogic.drivingsensors

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide entry point for raw sensor recording. State is persisted separately from tracking
 * so each resumes independently after a process restart; the foreground service is shared.
 */
object RecordingCoordinator {
    private const val PREFS = "driving_sensors_recording"
    private val lock = Any()

    /** True while the service has a running recorder. */
    @Volatile
    var recordingRunning = false

    /** Rolling sample-rate window of the running recorder. */
    @Volatile
    var rates: RateWindow? = null

    /** All recording file IO outside the writer thread runs here (never on the main thread). */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "driving-sensors-rec-io").apply { isDaemon = true }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "driving_sensors/recordings")

    fun loadState(context: Context): RecordingState? = parseState(prefs(context))

    internal fun parseState(p: SharedPreferences): RecordingState? {
        if (!p.getBoolean("recording", false)) return null
        val id = p.getString("recordingId", null) ?: return null
        val d = RecordingConfig()
        val config = RecordingConfig(
            accelHz = p.getFloat("accelHz", d.accelHz.toFloat()).toDouble(),
            gyroHz = p.getFloat("gyroHz", d.gyroHz.toFloat()).toDouble(),
            magHz = p.getFloat("magHz", d.magHz.toFloat()).toDouble(),
            baroHz = p.getFloat("baroHz", d.baroHz.toFloat()).toDouble(),
            locationIntervalMs = p.getLong("locationIntervalMs", d.locationIntervalMs),
            chunkSeconds = p.getInt("chunkSeconds", d.chunkSeconds),
            maxStorageMb = p.getInt("maxStorageMb", d.maxStorageMb),
            notificationTitle = p.getString("notificationTitle", d.notificationTitle) ?: d.notificationTitle,
            notificationText = p.getString("notificationText", d.notificationText) ?: d.notificationText,
        )
        return RecordingState(id, config, p.getInt("nextSeq", 0), p.getLong("startedAt", 0))
    }

    fun stopReason(context: Context): String? = prefs(context).getString("stopReason", null)

    /** Starts recording unless already active. Returns the recording id. */
    @SuppressLint("ApplySharedPref")
    fun begin(context: Context, config: RecordingConfig): String = synchronized(lock) {
        val existing = loadState(context)
        if (existing != null) {
            TrackingCoordinator.startService(context)
            return existing.recordingId
        }
        val id = UUID.randomUUID().toString()
        prefs(context).edit()
            .putBoolean("recording", true)
            .putString("recordingId", id)
            .putInt("nextSeq", 0)
            .putLong("startedAt", System.currentTimeMillis())
            .putFloat("accelHz", config.accelHz.toFloat())
            .putFloat("gyroHz", config.gyroHz.toFloat())
            .putFloat("magHz", config.magHz.toFloat())
            .putFloat("baroHz", config.baroHz.toFloat())
            .putLong("locationIntervalMs", config.locationIntervalMs)
            .putInt("chunkSeconds", config.chunkSeconds)
            .putInt("maxStorageMb", config.maxStorageMb)
            .putString("notificationTitle", config.notificationTitle)
            .putString("notificationText", config.notificationText)
            .remove("stopReason")
            .commit()
        try {
            TrackingCoordinator.startService(context)
        } catch (e: Exception) {
            prefs(context).edit().putBoolean("recording", false).commit()
            throw e
        }
        id
    }

    /**
     * Stops recording; [onDone] runs after the current chunk is finalized. [reason] is recorded
     * as stopReason when recording stopped by itself.
     */
    @SuppressLint("ApplySharedPref")
    fun end(context: Context, reason: String? = null, onDone: () -> Unit) {
        val svc: TrackingService?
        synchronized(lock) {
            val e = prefs(context).edit().putBoolean("recording", false)
            if (reason != null) e.putString("stopReason", reason)
            e.commit()
            svc = TrackingCoordinator.service
        }
        if (svc != null) {
            svc.requestRecordingStop(onDone)
        } else {
            val dir = dir(context)
            io.execute {
                try {
                    RecordingFiles.finalizeLeftovers(dir)
                } finally {
                    onDone()
                }
            }
        }
    }

    fun updateNextSeq(context: Context, nextSeq: Int) {
        prefs(context).edit().putInt("nextSeq", nextSeq).apply()
    }
}
