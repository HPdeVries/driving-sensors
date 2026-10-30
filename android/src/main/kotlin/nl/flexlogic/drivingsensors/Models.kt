package nl.flexlogic.drivingsensors

/** A location fix with platform-independent fields. [speedMps] is null when unknown. */
data class Fix(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double?,
    val accuracyM: Double? = null,
)

/** Resolved tracking options (defaults from `definitions.ts`). */
data class TrackingConfig(
    val harshBrakeMps2: Double = 3.0,
    val harshAccelMps2: Double = 3.0,
    val locationIntervalMs: Long = 1000,
    val accelSampleHz: Double = 50.0,
    val tripStartSpeedMps: Double = 4.2,
    val tripEndIdleMs: Long = 180_000,
    val minEventGapMs: Long = 3000,
    val notificationTitle: String = "Trip recording active",
    val notificationText: String = "Recording driving data",
)

object EventType {
    const val LOCATION = "LOCATION"
    const val HARSH_BRAKE = "HARSH_BRAKE"
    const val HARSH_ACCEL = "HARSH_ACCEL"
    const val TRIP_START = "TRIP_START"
    const val TRIP_END = "TRIP_END"
}

data class EventRecord(
    val ts: Long,
    val type: String,
    val sessionId: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val speedMps: Double? = null,
    val accuracyM: Double? = null,
    val peakMps2: Double? = null,
)

data class StoredEvent(val id: Long, val record: EventRecord)
