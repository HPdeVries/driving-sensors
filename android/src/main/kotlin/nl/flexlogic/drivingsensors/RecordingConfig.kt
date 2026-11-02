package nl.flexlogic.drivingsensors

/** Resolved recording options (defaults and clamping from SPEC "v0.2.0: raw sensor recording"). */
data class RecordingConfig(
    val accelHz: Double = 100.0,
    val gyroHz: Double = 100.0,
    val magHz: Double = 50.0,
    val baroHz: Double = 10.0,
    val locationIntervalMs: Long = 1000,
    val chunkSeconds: Int = 60,
    val maxStorageMb: Int = 500,
    val notificationTitle: String = "Sensor recording active",
    val notificationText: String = "Recording raw sensor data",
) {
    companion object {
        /**
         * Validates (null = default, otherwise must be finite and positive, else
         * [IllegalArgumentException]) and clamps raw option values.
         */
        fun resolve(
            accelHz: Double? = null,
            gyroHz: Double? = null,
            magHz: Double? = null,
            baroHz: Double? = null,
            locationIntervalMs: Double? = null,
            chunkSeconds: Double? = null,
            maxStorageMb: Double? = null,
            notificationTitle: String? = null,
            notificationText: String? = null,
        ): RecordingConfig {
            val d = RecordingConfig()
            fun v(name: String, value: Double?, default: Double, min: Double, max: Double): Double {
                if (value == null) return default
                require(value.isFinite() && value > 0) { "$name must be a positive number" }
                return value.coerceIn(min, max)
            }
            return RecordingConfig(
                accelHz = v("accelHz", accelHz, d.accelHz, 1.0, 200.0),
                gyroHz = v("gyroHz", gyroHz, d.gyroHz, 1.0, 200.0),
                magHz = v("magHz", magHz, d.magHz, 1.0, 200.0),
                baroHz = v("baroHz", baroHz, d.baroHz, 1.0, 50.0),
                locationIntervalMs = v("locationIntervalMs", locationIntervalMs, d.locationIntervalMs.toDouble(), 500.0, 60_000.0).toLong(),
                chunkSeconds = v("chunkSeconds", chunkSeconds, d.chunkSeconds.toDouble(), 10.0, 600.0).toInt(),
                maxStorageMb = v("maxStorageMb", maxStorageMb, d.maxStorageMb.toDouble(), 10.0, 10_000.0).toInt(),
                notificationTitle = notificationTitle ?: d.notificationTitle,
                notificationText = notificationText ?: d.notificationText,
            )
        }
    }
}

/** Persisted recording state; lets the service resume after a process restart. */
data class RecordingState(
    val recordingId: String,
    val config: RecordingConfig,
    val nextSeq: Int,
    val startedAt: Long,
)

object RecordingSensors {
    const val ACC = 0
    const val GYR = 1
    const val MAG = 2
    const val BAR = 3
    const val LOC = 4
    val NAMES = arrayOf("acc", "gyr", "mag", "bar", "loc")
}

/** Notification title/text for what the shared foreground service is running. */
object ServiceNotificationText {
    fun compose(tracking: TrackingConfig?, recording: RecordingConfig?): Pair<String, String> = when {
        tracking != null && recording != null ->
            tracking.notificationTitle to "${tracking.notificationText} + ${recording.notificationText}"
        recording != null -> recording.notificationTitle to recording.notificationText
        tracking != null -> tracking.notificationTitle to tracking.notificationText
        else -> TrackingConfig().let { it.notificationTitle to it.notificationText }
    }
}
