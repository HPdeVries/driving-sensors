package nl.flexlogic.drivingsensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * One-shot fresh reading: a high-accuracy fix and a short motion sample, taken in parallel with
 * their own listeners/thread so a running tracking session is untouched. Never writes events.
 */
object CurrentReadings {
    private const val TAG = "DrivingSensors"
    private const val SAMPLE_HZ = 50.0

    @SuppressLint("MissingPermission")
    fun read(context: Context, timeoutMs: Long, motionSampleMs: Long, onDone: (LocationReading?, MotionReading?) -> Unit) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val lock = Any()
        var location: LocationReading? = null
        var motion: MotionReading? = null
        var pending = 2
        var locationFinished = false

        fun partDone() {
            val finished = synchronized(lock) { --pending == 0 }
            if (finished) onDone(location, motion)
        }

        fun finishLocation(l: LocationReading?) {
            synchronized(lock) {
                if (locationFinished) return
                locationFinished = true
                location = l
            }
            partDone()
        }

        // (a) location
        val cts = CancellationTokenSource()
        val timeout = Runnable {
            cts.cancel()
            finishLocation(null)
        }
        try {
            LocationServices.getFusedLocationProviderClient(appContext)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    main.removeCallbacks(timeout)
                    finishLocation(
                        loc?.let {
                            LocationReading(
                                it.time,
                                it.latitude,
                                it.longitude,
                                if (it.hasSpeed()) it.speed.toDouble() else null,
                                if (it.hasAccuracy()) it.accuracy.toDouble() else null,
                            )
                        },
                    )
                }
                .addOnFailureListener {
                    main.removeCallbacks(timeout)
                    finishLocation(null)
                }
            main.postDelayed(timeout, timeoutMs)
        } catch (e: Exception) {
            Log.w(TAG, "current location failed", e)
            finishLocation(null)
        }

        // (b) motion, on its own thread and listener
        val thread = HandlerThread("driving-sensors-oneshot").also { it.start() }
        val handler = Handler(thread.looper)
        val window = MotionWindow()
        val sampler = MotionSampler(appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager, handler) { window.add(it) }
        val started = try {
            sampler.start(SAMPLE_HZ)
        } catch (e: Exception) {
            Log.w(TAG, "motion sampling failed", e)
            false
        }
        if (!started) {
            thread.quitSafely()
            partDone()
        } else {
            handler.postDelayed({
                sampler.stop()
                synchronized(lock) { motion = window.result() }
                thread.quitSafely()
                partDone()
            }, motionSampleMs)
        }
    }
}
