package nl.flexlogic.drivingsensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock

/**
 * Registers linear acceleration + gravity (falling back to a low-pass filtered accelerometer when
 * either is missing) and emits combined samples on [handler]'s thread. Each instance has its own
 * listener and gravity state, so the tracking service and one-shot reads never interfere.
 */
class MotionSampler(
    private val sensorManager: SensorManager,
    private val handler: Handler,
    private val onSample: (MotionReading) -> Unit,
) {
    private val gravity = DoubleArray(3)
    private val lowPassGravity = DoubleArray(3)
    private var hasGravitySensor = false
    private var hasLinearSensor = false
    private var clockOffsetMs = 0L
    private var registered = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = onSensor(event)
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Returns false when no usable sensor exists. */
    fun start(sampleHz: Double): Boolean {
        if (registered) return true
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val grav = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        hasLinearSensor = linear != null
        hasGravitySensor = grav != null
        gravity.fill(0.0)
        lowPassGravity.fill(0.0)
        clockOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val periodUs = (1_000_000.0 / sampleHz).toInt()
        val sensors = buildList {
            linear?.let { add(it) }
            grav?.let { add(it) }
            if ((linear == null || grav == null) && accel != null) add(accel)
        }
        for (sensor in sensors) sensorManager.registerListener(listener, sensor, periodUs, handler)
        registered = sensors.isNotEmpty()
        return registered
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(listener)
        registered = false
    }

    private fun onSensor(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> for (i in 0..2) gravity[i] = v[i].toDouble()
            Sensor.TYPE_LINEAR_ACCELERATION -> emit(event, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) {
                    lowPassGravity[i] = if (lowPassGravity[i] == 0.0) v[i].toDouble() else LOW_PASS * lowPassGravity[i] + (1 - LOW_PASS) * v[i]
                }
                if (!hasGravitySensor) lowPassGravity.copyInto(gravity)
                if (!hasLinearSensor) {
                    emit(event, v[0] - lowPassGravity[0], v[1] - lowPassGravity[1], v[2] - lowPassGravity[2])
                }
            }
        }
    }

    private fun emit(event: SensorEvent, ax: Double, ay: Double, az: Double) {
        val ts = clockOffsetMs + event.timestamp / 1_000_000L
        onSample(MotionReading.of(ts, ax, ay, az, gravity[0], gravity[1], gravity[2]))
    }

    companion object {
        private const val LOW_PASS = 0.9
    }
}
