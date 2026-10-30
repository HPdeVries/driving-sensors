package nl.flexlogic.drivingsensors

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import java.util.UUID

/** Persisted tracking state; lets the service resume after a process restart. */
data class TrackingState(val sessionId: String, val config: TrackingConfig)

/**
 * Process-wide entry point shared by the plugin and the service, so plugin calls work whether or
 * not the service is running.
 */
object TrackingCoordinator {
    private const val PREFS = "driving_sensors_state"

    /** Set by the service while it runs. */
    @Volatile
    var service: TrackingService? = null

    @Volatile
    var inTrip = false

    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun store(context: Context): EventStore = EventStore.get(context)

    fun loadState(context: Context): TrackingState? {
        val p = prefs(context)
        if (!p.getBoolean("active", false)) return null
        val sessionId = p.getString("sessionId", null) ?: return null
        val d = TrackingConfig()
        val config = TrackingConfig(
            harshBrakeMps2 = p.getFloat("harshBrakeMps2", d.harshBrakeMps2.toFloat()).toDouble(),
            harshAccelMps2 = p.getFloat("harshAccelMps2", d.harshAccelMps2.toFloat()).toDouble(),
            locationIntervalMs = p.getLong("locationIntervalMs", d.locationIntervalMs),
            accelSampleHz = p.getFloat("accelSampleHz", d.accelSampleHz.toFloat()).toDouble(),
            tripStartSpeedMps = p.getFloat("tripStartSpeedMps", d.tripStartSpeedMps.toFloat()).toDouble(),
            tripEndIdleMs = p.getLong("tripEndIdleMs", d.tripEndIdleMs),
            minEventGapMs = p.getLong("minEventGapMs", d.minEventGapMs),
            notificationTitle = p.getString("notificationTitle", d.notificationTitle) ?: d.notificationTitle,
            notificationText = p.getString("notificationText", d.notificationText) ?: d.notificationText,
        )
        return TrackingState(sessionId, config)
    }

    /** Starts tracking unless already active. Returns the session id. */
    @SuppressLint("ApplySharedPref")
    fun begin(context: Context, config: TrackingConfig): String = synchronized(lock) {
        val existing = loadState(context)
        if (existing != null) {
            startService(context)
            return existing.sessionId
        }
        val sessionId = UUID.randomUUID().toString()
        // Floats are exact enough for thresholds; Prefs has no double type.
        prefs(context).edit()
            .putBoolean("active", true)
            .putString("sessionId", sessionId)
            .putFloat("harshBrakeMps2", config.harshBrakeMps2.toFloat())
            .putFloat("harshAccelMps2", config.harshAccelMps2.toFloat())
            .putLong("locationIntervalMs", config.locationIntervalMs)
            .putFloat("accelSampleHz", config.accelSampleHz.toFloat())
            .putFloat("tripStartSpeedMps", config.tripStartSpeedMps.toFloat())
            .putLong("tripEndIdleMs", config.tripEndIdleMs)
            .putLong("minEventGapMs", config.minEventGapMs)
            .putString("notificationTitle", config.notificationTitle)
            .putString("notificationText", config.notificationText)
            .commit()
        try {
            startService(context)
        } catch (e: Exception) {
            prefs(context).edit().putBoolean("active", false).commit()
            throw e
        }
        sessionId
    }

    /** Stops tracking; [onDone] runs after TRIP_END (if any) is written. */
    @SuppressLint("ApplySharedPref")
    fun end(context: Context, onDone: () -> Unit) {
        val svc: TrackingService?
        synchronized(lock) {
            prefs(context).edit().putBoolean("active", false).commit()
            svc = service
        }
        if (svc != null) svc.requestStop(onDone) else store(context).whenIdle(onDone)
    }

    fun startService(context: Context) {
        val appContext = context.applicationContext
        ContextCompat.startForegroundService(appContext, Intent(appContext, TrackingService::class.java))
    }
}
