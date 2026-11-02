package nl.flexlogic.drivingsensors

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

data class HeartbeatState(val intervalSec: Int, val seq: Long)

/** Persisted heartbeat state; the foreground service runs the timer. */
object HeartbeatCoordinator {
    private const val PREFS = "driving_sensors_heartbeat"
    const val MIN_INTERVAL_SEC = 10
    const val MAX_INTERVAL_SEC = 3600
    const val DEFAULT_INTERVAL_SEC = 30

    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clampInterval(v: Double?): Int = (v ?: DEFAULT_INTERVAL_SEC.toDouble()).toInt().coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)

    fun loadState(context: Context): HeartbeatState? = parseState(prefs(context))

    internal fun parseState(p: SharedPreferences): HeartbeatState? {
        if (!p.getBoolean("running", false)) return null
        return HeartbeatState(p.getInt("intervalSec", DEFAULT_INTERVAL_SEC), p.getLong("seq", 0))
    }

    /** Persists running state (seq continues if already running) and starts the service. */
    @SuppressLint("ApplySharedPref")
    fun begin(context: Context, intervalSec: Int) {
        synchronized(lock) {
            val wasRunning = loadState(context) != null
            val e = prefs(context).edit().putBoolean("running", true).putInt("intervalSec", intervalSec)
            if (!wasRunning) e.putLong("seq", 0)
            e.commit()
            if (!wasRunning) UploadRuntime.heartbeat(context).resetCounters()
        }
        try {
            TrackingCoordinator.startService(context)
        } catch (e: Exception) {
            prefs(context).edit().putBoolean("running", false).commit()
            throw e
        }
    }

    @SuppressLint("ApplySharedPref")
    fun end(context: Context) {
        val svc: TrackingService?
        synchronized(lock) {
            prefs(context).edit().putBoolean("running", false).commit()
            svc = TrackingCoordinator.service
        }
        svc?.requestHeartbeatStop()
    }

    /** Increments and returns the next sequence number (first beat is 1). */
    fun nextSeq(context: Context): Long = synchronized(lock) {
        val p = prefs(context)
        val n = p.getLong("seq", 0) + 1
        p.edit().putLong("seq", n).apply()
        n
    }
}
