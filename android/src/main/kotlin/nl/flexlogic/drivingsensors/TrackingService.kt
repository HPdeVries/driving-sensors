package nl.flexlogic.drivingsensors

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Foreground service that owns location updates and sensors. All state is touched on the
 * service's own handler thread.
 */
class TrackingService : Service() {

    private class Session(val id: String, val config: TrackingConfig) {
        val trip = TripDetector(config.tripStartSpeedMps, config.tripEndIdleMs)
        val harsh = HarshEventDetector(config.harshBrakeMps2, config.harshAccelMps2, config.minEventGapMs) {
            Log.d(TAG, it)
        }
        val speed = SpeedEstimator()
        var lastLocationEventTs = 0L
        var lastFix: Fix? = null
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var session: Session? = null

    // Sensor state (handler thread only).
    private val gravity = DoubleArray(3)
    private val lowPassGravity = DoubleArray(3)
    private var hasGravitySensor = false
    private var hasLinearSensor = false
    private var clockOffsetMs = 0L
    private var sensorsRegistered = false

    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) onLocation(location)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = onSensor(event)
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val s = session ?: return
            val now = System.currentTimeMillis()
            s.trip.onTick(now).forEach { applyTripEvent(s, it) }
            s.harsh.tick(now)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("driving-sensors").also { it.start() }
        handler = Handler(thread.looper)
        TrackingCoordinator.service = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = TrackingCoordinator.loadState(this)
        try {
            startForegroundCompat(state?.config ?: TrackingConfig())
        } catch (e: Exception) {
            Log.e(TAG, "cannot start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (state == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.post { beginSession(state) }
        return START_STICKY
    }

    override fun onDestroy() {
        if (TrackingCoordinator.service === this) TrackingCoordinator.service = null
        handler.post { teardown() }
        thread.quitSafely()
        super.onDestroy()
    }

    /** Called by the coordinator after the persisted active flag was cleared. */
    fun requestStop(onDone: () -> Unit) {
        handler.post {
            session?.let { s -> s.trip.forceEnd()?.let { writeTripEnd(s, it.fix) } }
            teardown()
            TrackingCoordinator.store(this).whenIdle(onDone)
            stopSelf()
        }
    }

    private fun startForegroundCompat(config: TrackingConfig) {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(config), type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Trip recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(config: TrackingConfig): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_driving_sensors_notification)
            .setContentTitle(config.notificationTitle)
            .setContentText(config.notificationText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    // ---- session ----

    private fun beginSession(state: TrackingState) {
        if (session?.id == state.sessionId) return
        teardown()
        val s = Session(state.sessionId, state.config)
        session = s
        TrackingCoordinator.inTrip = false
        if (!requestLocation(s, highAccuracy = false)) {
            Log.e(TAG, "location permission missing, stopping")
            session = null
            ContextCompat.getMainExecutor(this).execute { stopSelf() }
            return
        }
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun teardown() {
        handler.removeCallbacks(tickRunnable)
        locationClient.removeLocationUpdates(locationCallback)
        unregisterSensors()
        session = null
        TrackingCoordinator.inTrip = false
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation(s: Session, highAccuracy: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val request = if (highAccuracy) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, s.config.locationIntervalMs)
                .setMinUpdateIntervalMillis(s.config.locationIntervalMs / 2)
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, IDLE_INTERVAL_MS)
        }.build()
        locationClient.requestLocationUpdates(request, locationCallback, thread.looper)
        return true
    }

    // ---- location / trip ----

    private fun onLocation(location: Location) {
        val s = session ?: return
        val platformSpeed = if (location.hasSpeed()) location.speed.toDouble() else null
        val speed = s.speed.estimate(location.time, location.latitude, location.longitude, platformSpeed)
        val fix = Fix(
            location.time,
            location.latitude,
            location.longitude,
            speed,
            if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        )
        s.lastFix = fix

        s.trip.onFix(fix).forEach { applyTripEvent(s, it) }
        if (!s.trip.inTrip) return

        for (e in s.harsh.onFix(fix)) {
            val type = if (e.type == HarshEventDetector.Type.HARSH_BRAKE) EventType.HARSH_BRAKE else EventType.HARSH_ACCEL
            insert(s, EventRecord(e.ts, type, s.id, e.lat, e.lon, e.speedMps, null, e.peakMps2))
        }
        if (fix.ts - s.lastLocationEventTs >= s.config.locationIntervalMs - LOCATION_TOLERANCE_MS) {
            s.lastLocationEventTs = fix.ts
            insert(s, EventRecord(fix.ts, EventType.LOCATION, s.id, fix.lat, fix.lon, fix.speedMps, fix.accuracyM))
        }
    }

    private fun applyTripEvent(s: Session, e: TripDetector.Event) {
        when (e.type) {
            TripDetector.Type.TRIP_START -> {
                insert(s, EventRecord(e.fix.ts, EventType.TRIP_START, s.id, e.fix.lat, e.fix.lon, e.fix.speedMps, e.fix.accuracyM))
                TrackingCoordinator.inTrip = true
                s.harsh.reset()
                s.lastLocationEventTs = 0
                requestLocation(s, highAccuracy = true)
                registerSensors(s)
            }
            TripDetector.Type.TRIP_END -> {
                writeTripEnd(s, e.fix)
                TrackingCoordinator.inTrip = false
                s.harsh.reset()
                unregisterSensors()
                requestLocation(s, highAccuracy = false)
            }
        }
    }

    private fun writeTripEnd(s: Session, fix: Fix) {
        insert(s, EventRecord(fix.ts, EventType.TRIP_END, s.id, fix.lat, fix.lon, fix.speedMps, fix.accuracyM))
    }

    private fun insert(s: Session, record: EventRecord) {
        if (record.sessionId != s.id) return
        TrackingCoordinator.store(this).insert(record)
    }

    // ---- sensors ----

    private fun registerSensors(s: Session) {
        if (sensorsRegistered) return
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val grav = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        hasLinearSensor = linear != null
        hasGravitySensor = grav != null
        gravity.fill(0.0)
        lowPassGravity.fill(0.0)
        clockOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val periodUs = (1_000_000.0 / s.config.accelSampleHz).toInt()
        val sensors = buildList {
            linear?.let { add(it) }
            grav?.let { add(it) }
            if ((linear == null || grav == null) && accel != null) add(accel)
        }
        for (sensor in sensors) sensorManager.registerListener(sensorListener, sensor, periodUs, handler)
        sensorsRegistered = sensors.isNotEmpty()
        if (!sensorsRegistered) Log.w(TAG, "no usable motion sensor, harsh events disabled")
    }

    private fun unregisterSensors() {
        if (!sensorsRegistered) return
        sensorManager.unregisterListener(sensorListener)
        sensorsRegistered = false
    }

    private fun onSensor(event: SensorEvent) {
        val s = session ?: return
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> for (i in 0..2) gravity[i] = v[i].toDouble()
            Sensor.TYPE_LINEAR_ACCELERATION -> feedAccel(s, event, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) {
                    lowPassGravity[i] = if (lowPassGravity[i] == 0.0) v[i].toDouble() else LOW_PASS * lowPassGravity[i] + (1 - LOW_PASS) * v[i]
                }
                if (!hasGravitySensor) lowPassGravity.copyInto(gravity)
                if (!hasLinearSensor) {
                    feedAccel(s, event, v[0] - lowPassGravity[0], v[1] - lowPassGravity[1], v[2] - lowPassGravity[2])
                }
            }
        }
    }

    private fun feedAccel(s: Session, event: SensorEvent, ax: Double, ay: Double, az: Double) {
        val ts = clockOffsetMs + event.timestamp / 1_000_000L
        s.harsh.onAccel(ts, ax, ay, az, gravity[0], gravity[1], gravity[2])
    }

    companion object {
        private const val TAG = "DrivingSensors"
        private const val CHANNEL_ID = "driving_sensors_tracking"
        private const val NOTIFICATION_ID = 7421
        private const val TICK_MS = 10_000L
        private const val IDLE_INTERVAL_MS = 5000L
        private const val LOCATION_TOLERANCE_MS = 100L
        private const val LOW_PASS = 0.9
    }
}
