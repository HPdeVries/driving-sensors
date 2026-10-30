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
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
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

    private var sensorsRegistered = false

    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) onLocation(location)
        }
    }

    private lateinit var sampler: MotionSampler

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
        sampler = MotionSampler(sensorManager, handler, ::onMotion)
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
            NotificationChannel(CHANNEL_ID, "Trip recording", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
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
            .setSilent(true)
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
        TrackingCoordinator.lastLocation = null
        if (!requestLocation(s, highAccuracy = false)) {
            Log.e(TAG, "location permission missing, stopping")
            session = null
            TrackingCoordinator.sessionRunning = false
            ContextCompat.getMainExecutor(this).execute { stopSelf() }
            return
        }
        TrackingCoordinator.sessionRunning = true
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun teardown() {
        handler.removeCallbacks(tickRunnable)
        locationClient.removeLocationUpdates(locationCallback)
        unregisterSensors()
        session = null
        TrackingCoordinator.sessionRunning = false
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
        TrackingCoordinator.lastLocation = LocationReading.from(fix)

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
        sensorsRegistered = sampler.start(s.config.accelSampleHz)
        if (!sensorsRegistered) Log.w(TAG, "no usable motion sensor, harsh events disabled")
    }

    private fun unregisterSensors() {
        if (!sensorsRegistered) return
        sampler.stop()
        sensorsRegistered = false
    }

    private fun onMotion(m: MotionReading) {
        TrackingCoordinator.lastMotion = m
        val s = session ?: return
        s.harsh.onAccel(m.ts, m.ax, m.ay, m.az, m.gx, m.gy, m.gz)
    }

    companion object {
        private const val TAG = "DrivingSensors"
        private const val CHANNEL_ID = "driving_sensors_tracking_v2"
        private const val NOTIFICATION_ID = 7421
        private const val TICK_MS = 10_000L
        private const val IDLE_INTERVAL_MS = 5000L
        private const val LOCATION_TOLERANCE_MS = 100L
    }
}
