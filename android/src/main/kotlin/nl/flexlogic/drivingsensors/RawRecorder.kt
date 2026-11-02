package nl.flexlogic.drivingsensors

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority

/**
 * Registers the raw sensors and a dedicated fused-location request on its own handler thread
 * (so 270+ events/s never compete with trip logic) and feeds every sample to [writer].
 */
class RawRecorder(
    private val sensorManager: SensorManager,
    private val locationClient: FusedLocationProviderClient,
    private val config: RecordingConfig,
    private val writer: RecordingWriter,
    private val rates: RateWindow,
) {
    private val thread = HandlerThread("driving-sensors-rec").also { it.start() }
    private val handler = Handler(thread.looper)
    private var clockOffsetMs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensor = when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> RecordingSensors.ACC
                Sensor.TYPE_GYROSCOPE -> RecordingSensors.GYR
                Sensor.TYPE_MAGNETIC_FIELD -> RecordingSensors.MAG
                Sensor.TYPE_PRESSURE -> RecordingSensors.BAR
                else -> return
            }
            val t = clockOffsetMs + event.timestamp / 1_000_000L
            val v = event.values
            if (sensor == RecordingSensors.BAR) {
                writer.add(t, sensor, v[0].toDouble())
            } else {
                writer.add(t, sensor, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            }
            rates.record(sensor, SystemClock.elapsedRealtime())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (l in result.locations) onLocation(l)
        }
    }

    private fun onLocation(l: Location) {
        writer.add(
            l.time,
            RecordingSensors.LOC,
            l.latitude,
            l.longitude,
            if (l.hasAltitude()) l.altitude else Double.NaN,
            if (l.hasSpeed()) l.speed.toDouble() else Double.NaN,
            if (l.hasAccuracy()) l.accuracy.toDouble() else Double.NaN,
        )
        rates.record(RecordingSensors.LOC, SystemClock.elapsedRealtime())
    }

    /** Registers everything. Returns false when the location request could not be made. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        clockOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        register(Sensor.TYPE_ACCELEROMETER, config.accelHz)
        register(Sensor.TYPE_GYROSCOPE, config.gyroHz)
        register(Sensor.TYPE_MAGNETIC_FIELD, config.magHz)
        register(Sensor.TYPE_PRESSURE, config.baroHz)
        return try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, config.locationIntervalMs)
                .setMinUpdateIntervalMillis(config.locationIntervalMs / 2)
                .build()
            locationClient.requestLocationUpdates(request, locationCallback, thread.looper)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "recording location permission missing", e)
            false
        }
    }

    private fun register(type: Int, hz: Double) {
        val sensor = sensorManager.getDefaultSensor(type)
        if (sensor == null) {
            Log.i(TAG, "sensor type $type not available, skipped")
            return
        }
        val periodUs = (1_000_000.0 / hz).toInt()
        if (!sensorManager.registerListener(listener, sensor, periodUs, handler)) {
            Log.w(TAG, "could not register sensor type $type")
        }
    }

    /** Unregisters on the recorder thread (after queued events), then runs [onStopped] and quits the thread. */
    fun stop(onStopped: () -> Unit) {
        val task = Runnable {
            try {
                sensorManager.unregisterListener(listener)
                locationClient.removeLocationUpdates(locationCallback)
            } finally {
                thread.quitSafely()
                onStopped()
            }
        }
        if (!handler.post(task)) {
            sensorManager.unregisterListener(listener)
            locationClient.removeLocationUpdates(locationCallback)
            onStopped()
        }
    }

    companion object {
        private const val TAG = "DrivingSensors"
    }
}
