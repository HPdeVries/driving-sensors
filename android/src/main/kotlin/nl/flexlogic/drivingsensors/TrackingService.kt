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

    private var sensorsRegistered = false

    // Raw recording (independent of the tracking session); touched on [handler] only.
    private var recorder: RawRecorder? = null
    private var recorderId: String? = null

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
        val recState = RecordingCoordinator.loadState(this)
        val hbState = HeartbeatCoordinator.loadState(this)
        try {
            startForegroundCompat(state?.config, recState?.config, hbState != null)
        } catch (e: Exception) {
            Log.e(TAG, "cannot start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (state == null && recState == null && hbState == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (state != null) handler.post { beginSession(state) }
        if (recState != null) handler.post { beginRecording(recState) }
        if (hbState != null) handler.post { beginHeartbeat(hbState) }
        handler.post { startUploadTick() }
        return START_STICKY
    }

    override fun onDestroy() {
        if (TrackingCoordinator.service === this) TrackingCoordinator.service = null
        handler.post {
            handler.removeCallbacks(heartbeatRunnable)
            handler.removeCallbacks(uploadTickRunnable)
            hbExecutor.shutdown()
            teardown()
            teardownRecording()
        }
        thread.quitSafely()
        super.onDestroy()
    }

    /** Called by the coordinator after the persisted active flag was cleared. */
    fun requestStop(onDone: () -> Unit) {
        handler.post {
            session?.let { s -> s.trip.forceEnd()?.let { writeTripEnd(s, it.fix) } }
            teardown()
            TrackingCoordinator.store(this).whenIdle(onDone)
            stopOrRefresh()
        }
    }

    /** Called by [RecordingCoordinator] after the persisted recording flag was cleared. */
    fun requestRecordingStop(onDone: () -> Unit) {
        handler.post {
            val r = recorder
            recorder = null
            recorderId = null
            RecordingCoordinator.recordingRunning = false
            RecordingCoordinator.rates = null
            if (r == null) {
                RecordingCoordinator.io.execute {
                    try {
                        RecordingFiles.finalizeLeftovers(RecordingCoordinator.dir(this))
                    } finally {
                        onDone()
                    }
                }
                handler.post { stopOrRefresh() }
            } else {
                r.stop {
                    handler.post { stopWriterThen(r, onDone) }
                }
            }
        }
    }

    // Writer reference kept alongside the recorder so stop can flush it.
    private var writer: RecordingWriter? = null

    private fun stopWriterThen(@Suppress("UNUSED_PARAMETER") r: RawRecorder, onDone: () -> Unit) {
        val w = writer
        writer = null
        if (w == null) {
            onDone()
            stopOrRefresh()
        } else {
            w.stop {
                try {
                    onDone()
                } finally {
                    handler.post { stopOrRefresh() }
                }
            }
        }
    }

    /** Stops the service when neither tracking nor recording is active, else refreshes the notification. */
    private fun stopOrRefresh() {
        val hb = HeartbeatCoordinator.loadState(this) != null
        if (TrackingCoordinator.loadState(this) == null && RecordingCoordinator.loadState(this) == null && !hb) {
            stopSelf()
        } else {
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, buildNotification(TrackingCoordinator.loadState(this)?.config, RecordingCoordinator.loadState(this)?.config, hb))
            } catch (e: Exception) {
                Log.w(TAG, "could not update notification", e)
            }
        }
    }

    private fun startForegroundCompat(config: TrackingConfig?, recording: RecordingConfig?, heartbeat: Boolean) {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(config, recording, heartbeat), type)
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

    private fun buildNotification(config: TrackingConfig?, recording: RecordingConfig?, heartbeat: Boolean): Notification {
        val (title, text) = ServiceNotificationText.compose(config, recording, heartbeat)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_driving_sensors_notification)
            .setContentTitle(title)
            .setContentText(text)
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

    // ---- upload tick (every 60 s while the service runs) ----

    private val uploadTickRunnable = object : Runnable {
        override fun run() {
            RecordingCoordinator.io.execute { UploadScheduler.triggerIfPending(this@TrackingService) }
            handler.postDelayed(this, UPLOAD_TICK_MS)
        }
    }

    private fun startUploadTick() {
        handler.removeCallbacks(uploadTickRunnable)
        handler.postDelayed(uploadTickRunnable, UPLOAD_TICK_MS)
    }

    // ---- heartbeat ----

    private val hbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "driving-sensors-heartbeat").apply { isDaemon = true }
    }
    private val hbBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var hbIntervalSec = 0
    private var hbRunning = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val state = HeartbeatCoordinator.loadState(this@TrackingService)
            if (state == null) {
                hbRunning = false
                return
            }
            sendHeartbeat(state.intervalSec)
            handler.postDelayed(this, state.intervalSec * 1000L)
        }
    }

    /** Starts the timer (first beat immediately) or applies a changed interval. */
    private fun beginHeartbeat(state: HeartbeatState) {
        if (hbRunning && hbIntervalSec == state.intervalSec) return
        val fresh = !hbRunning
        hbRunning = true
        hbIntervalSec = state.intervalSec
        handler.removeCallbacks(heartbeatRunnable)
        if (fresh) {
            handler.post(heartbeatRunnable)
        } else {
            handler.postDelayed(heartbeatRunnable, state.intervalSec * 1000L)
        }
    }

    /** Heartbeats are never queued: a beat is skipped while the previous one is still being sent. */
    private fun sendHeartbeat(intervalSec: Int) {
        if (!hbBusy.compareAndSet(false, true)) return
        val seq = HeartbeatCoordinator.nextSeq(this)
        try {
            hbExecutor.execute {
                try {
                    UploadRuntime.heartbeat(this).beat(seq, intervalSec)
                } catch (e: Exception) {
                    Log.w(TAG, "heartbeat failed", e)
                } finally {
                    hbBusy.set(false)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            hbBusy.set(false)
        }
    }

    /** Called by [HeartbeatCoordinator] after the persisted running flag was cleared. */
    fun requestHeartbeatStop() {
        handler.post {
            handler.removeCallbacks(heartbeatRunnable)
            hbRunning = false
            hbIntervalSec = 0
            stopOrRefresh()
        }
    }

    // ---- raw recording ----

    private fun beginRecording(state: RecordingState) {
        if (recorderId == state.recordingId) return
        teardownRecording()
        recorderId = state.recordingId // claim, so a second start command does not start twice
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "location permission missing, stopping recording")
            recorderId = null
            RecordingCoordinator.end(this, "PERMISSION_LOST") {}
            return
        }
        val dir = RecordingCoordinator.dir(this)
        RecordingCoordinator.io.execute {
            // Nothing is writing yet, so every .part is a leftover from a crash.
            try {
                RecordingFiles.finalizeLeftovers(dir)
            } catch (e: Exception) {
                Log.w(TAG, "could not finalize leftover chunks", e)
            }
            val nextSeq = RecordingFiles.nextSeq(dir, state.recordingId, state.nextSeq)
            handler.post { startRecorder(state, nextSeq) }
        }
    }

    private fun startRecorder(state: RecordingState, nextSeq: Int) {
        // Stopped or replaced while leftovers were being finalized?
        if (recorderId != state.recordingId || RecordingCoordinator.loadState(this)?.recordingId != state.recordingId) return
        val config = state.config
        val w = RecordingWriter(
            dir = RecordingCoordinator.dir(this),
            recordingId = state.recordingId,
            chunkMs = config.chunkSeconds * 1000L,
            maxBytes = config.maxStorageMb.toLong() * 1024 * 1024,
            startSeq = nextSeq,
            onChunkOpened = { seq -> RecordingCoordinator.updateNextSeq(this, seq + 1) },
            onChunkFinalized = { UploadScheduler.trigger(this) },
            onStorageFull = { RecordingCoordinator.end(this, "STORAGE_FULL") {} },
            onError = { e ->
                Log.e(TAG, "recording writer failed", e)
                RecordingCoordinator.end(this, "ERROR") {}
            },
        )
        val rates = RateWindow(startMs = SystemClock.elapsedRealtime())
        val r = RawRecorder(sensorManager, locationClient, config, w, rates)
        w.start()
        if (!r.start()) {
            r.stop {}
            w.stop {}
            recorderId = null
            RecordingCoordinator.end(this, "PERMISSION_LOST") {}
            return
        }
        writer = w
        recorder = r
        RecordingCoordinator.rates = rates
        RecordingCoordinator.recordingRunning = true
    }

    /** Immediate teardown (replace / destroy): flushes the writer asynchronously. */
    private fun teardownRecording() {
        val r = recorder
        val w = writer
        recorder = null
        writer = null
        recorderId = null
        RecordingCoordinator.recordingRunning = false
        RecordingCoordinator.rates = null
        r?.stop { w?.stop {} }
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
        private const val UPLOAD_TICK_MS = 60_000L
        private const val IDLE_INTERVAL_MS = 5000L
        private const val LOCATION_TOLERANCE_MS = 100L
    }
}
