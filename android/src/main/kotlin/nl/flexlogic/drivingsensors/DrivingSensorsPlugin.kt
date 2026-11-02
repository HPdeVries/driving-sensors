package nl.flexlogic.drivingsensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "DrivingSensors",
    permissions = [
        Permission(
            alias = DrivingSensorsPlugin.ALIAS_LOCATION,
            strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION],
        ),
        Permission(
            alias = DrivingSensorsPlugin.ALIAS_BACKGROUND,
            strings = [Manifest.permission.ACCESS_BACKGROUND_LOCATION],
        ),
        Permission(
            alias = DrivingSensorsPlugin.ALIAS_NOTIFICATIONS,
            strings = [Manifest.permission.POST_NOTIFICATIONS],
        ),
    ],
)
class DrivingSensorsPlugin : Plugin() {

    private val prefs by lazy { context.getSharedPreferences("driving_sensors_permissions", Context.MODE_PRIVATE) }

    override fun load() {
        try {
            AppStateTracker.register(context.applicationContext as android.app.Application)
            AppStateTracker.markStarted(activity)
            RecordingCoordinator.io.execute {
                UploadScheduler.ensurePeriodic(context)
                UploadScheduler.triggerIfPending(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not schedule upload", e)
        }
        try {
            val recording = RecordingCoordinator.loadState(context)
            if ((TrackingCoordinator.loadState(context) != null || recording != null || HeartbeatCoordinator.loadState(context) != null) &&
                isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
            ) {
                // The service finalizes leftover chunks itself before it starts writing.
                TrackingCoordinator.startService(context)
            } else if (recording == null) {
                val dir = RecordingCoordinator.dir(context)
                RecordingCoordinator.io.execute { RecordingFiles.finalizeLeftovers(dir) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not resume tracking", e)
        }
    }

    // ---- permissions ----

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun state(granted: Boolean, alias: String): PermissionState = when {
        granted -> PermissionState.GRANTED
        prefs.getBoolean("asked_$alias", false) -> PermissionState.DENIED
        else -> PermissionState.PROMPT
    }

    private fun backgroundGranted() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun notificationsGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        isGranted(Manifest.permission.POST_NOTIFICATIONS)

    private fun permissionResult(): JSObject = JSObject().apply {
        put("location", state(isGranted(Manifest.permission.ACCESS_FINE_LOCATION), ALIAS_LOCATION).toString())
        put("backgroundLocation", state(backgroundGranted(), ALIAS_BACKGROUND).toString())
        put("motion", PermissionState.GRANTED.toString())
        put("notifications", state(notificationsGranted(), ALIAS_NOTIFICATIONS).toString())
    }

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        call.resolve(permissionResult())
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        val requested = call.getArray("permissions")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it, null) }
        } ?: listOf("location", "motion", "notifications")

        val declared = declaredPermissions()
        val missing = LinkedHashSet<String>()
        fun need(type: String, vararg perms: String) {
            if (type in requested) missing.addAll(perms.filter { it !in declared })
        }
        need("location", Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) need("backgroundLocation", Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) need("notifications", Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) {
            call.reject("Missing in AndroidManifest.xml: ${missing.joinToString(", ")}", "NOT_SUPPORTED")
            return
        }

        val aliases = ArrayList<String>()
        if ("location" in requested && !isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) aliases.add(ALIAS_LOCATION)
        // Android 11+ ignores a request that asks for foreground and background location together.
        if ("backgroundLocation" in requested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundGranted() &&
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            aliases.add(ALIAS_BACKGROUND)
        }
        if ("notifications" in requested && !notificationsGranted()) aliases.add(ALIAS_NOTIFICATIONS)

        if (aliases.isEmpty()) {
            call.resolve(permissionResult())
            return
        }
        prefs.edit().apply { aliases.forEach { putBoolean("asked_$it", true) } }.apply()
        requestPermissionForAliases(aliases.toTypedArray(), call, "permissionsCallback")
    }

    @PermissionCallback
    fun permissionsCallback(call: PluginCall) {
        call.resolve(permissionResult())
    }

    // ---- tracking ----

    @PluginMethod
    fun startTracking(call: PluginCall) {
        val config = try {
            parseConfig(call)
        } catch (e: IllegalArgumentException) {
            call.reject(e.message, "INVALID_ARGUMENT")
            return
        }
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            call.reject("Fine location permission is required", "PERMISSION_DENIED")
            return
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            call.reject("Location services are disabled", "LOCATION_DISABLED")
            return
        }
        if (!backgroundGranted()) Log.w(TAG, "background location not granted; tracking may stop in the background")
        try {
            val sessionId = TrackingCoordinator.begin(context, config)
            call.resolve(JSObject().put("sessionId", sessionId))
        } catch (e: Exception) {
            call.reject("Could not start tracking: ${e.message}", "INTERNAL", e)
        }
    }

    @PluginMethod
    fun stopTracking(call: PluginCall) {
        try {
            TrackingCoordinator.end(context) { call.resolve() }
        } catch (e: Exception) {
            call.reject("Could not stop tracking: ${e.message}", "INTERNAL", e)
        }
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val state = TrackingCoordinator.loadState(context)
        TrackingCoordinator.store(context).async({ countNow() }) { result ->
            result.fold(
                onSuccess = { count ->
                    val ret = JSObject()
                    ret.put("active", state != null)
                    ret.put("serviceRunning", TrackingCoordinator.serviceRunning)
                    TrackingCoordinator.lastLocation?.let { ret.put("lastLocation", toJs(it.toMap())) }
                    state?.let { ret.put("sessionId", it.sessionId) }
                    ret.put("inTrip", state != null && TrackingCoordinator.inTrip)
                    ret.put("buffered", count)
                    call.resolve(ret)
                },
                onFailure = { call.reject("Could not read buffer: ${it.message}", "INTERNAL") },
            )
        }
    }

    @PluginMethod
    fun getLastReadings(call: PluginCall) {
        call.resolve(toJs(sensorReadingsMap(TrackingCoordinator.lastLocation, TrackingCoordinator.lastMotion)))
    }

    @PluginMethod
    fun getCurrentReadings(call: PluginCall) {
        fun number(name: String, default: Double, min: Double, max: Double): Double {
            val v = call.getDouble(name) ?: return default
            require(v.isFinite() && v > 0) { "$name must be a positive number" }
            return v.coerceIn(min, max)
        }
        val timeoutMs: Long
        val motionMs: Long
        try {
            timeoutMs = number("timeoutMs", 10_000.0, 1000.0, 60_000.0).toLong()
            motionMs = number("motionSampleMs", 1000.0, 100.0, 5000.0).toLong()
        } catch (e: IllegalArgumentException) {
            call.reject(e.message, "INVALID_ARGUMENT")
            return
        }
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            call.reject("Fine location permission is required", "PERMISSION_DENIED")
            return
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            call.reject("Location services are disabled", "LOCATION_DISABLED")
            return
        }
        try {
            CurrentReadings.read(context, timeoutMs, motionMs) { location, motion ->
                call.resolve(toJs(sensorReadingsMap(location, motion)))
            }
        } catch (e: Exception) {
            call.reject("Could not read sensors: ${e.message}", "INTERNAL", e)
        }
    }

    @PluginMethod
    fun getPluginVersion(call: PluginCall) {
        call.resolve(JSObject().put("version", PLUGIN_VERSION).put("platform", "android"))
    }

    @PluginMethod
    fun getBufferedEvents(call: PluginCall) {
        val afterId = (call.getLong("afterId") ?: 0L).coerceAtLeast(0L)
        val limit = (call.getInt("limit") ?: DEFAULT_LIMIT).coerceIn(1, EventStore.MAX_LIMIT)
        TrackingCoordinator.store(context).async({ queryNow(afterId, limit) }) { result ->
            result.fold(
                onSuccess = { events ->
                    val arr = JSArray()
                    events.forEach { arr.put(toJson(it)) }
                    call.resolve(JSObject().put("events", arr))
                },
                onFailure = { call.reject("Could not read buffer: ${it.message}", "INTERNAL") },
            )
        }
    }

    @PluginMethod
    fun ackEvents(call: PluginCall) {
        val upToId = call.getLong("upToId")
        if (upToId == null) {
            call.reject("upToId is required", "INVALID_ARGUMENT")
            return
        }
        TrackingCoordinator.store(context).async({ ackNow(upToId) }) { result ->
            result.fold(
                onSuccess = { call.resolve() },
                onFailure = { call.reject("Could not delete events: ${it.message}", "INTERNAL") },
            )
        }
    }

    // ---- recording ----

    @PluginMethod
    fun startRecording(call: PluginCall) {
        val config = try {
            RecordingConfig.resolve(
                accelHz = call.getDouble("accelHz"),
                gyroHz = call.getDouble("gyroHz"),
                magHz = call.getDouble("magHz"),
                baroHz = call.getDouble("baroHz"),
                locationIntervalMs = call.getDouble("locationIntervalMs"),
                chunkSeconds = call.getDouble("chunkSeconds"),
                maxStorageMb = call.getDouble("maxStorageMb"),
                notificationTitle = call.getString("notificationTitle"),
                notificationText = call.getString("notificationText"),
            )
        } catch (e: IllegalArgumentException) {
            call.reject(e.message, "INVALID_ARGUMENT")
            return
        }
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            call.reject("Fine location permission is required", "PERMISSION_DENIED")
            return
        }
        if (!backgroundGranted()) Log.w(TAG, "background location not granted; recording may stop in the background")
        try {
            val id = RecordingCoordinator.begin(context, config)
            call.resolve(JSObject().put("recordingId", id))
        } catch (e: Exception) {
            call.reject("Could not start recording: ${e.message}", "INTERNAL", e)
        }
    }

    @PluginMethod
    fun stopRecording(call: PluginCall) {
        try {
            RecordingCoordinator.end(context) { call.resolve() }
        } catch (e: Exception) {
            call.reject("Could not stop recording: ${e.message}", "INTERNAL", e)
        }
    }

    @PluginMethod
    fun getRecordingStatus(call: PluginCall) {
        val state = RecordingCoordinator.loadState(context)
        val stopReason = RecordingCoordinator.stopReason(context)
        val dir = RecordingCoordinator.dir(context)
        val available = JSObject().apply {
            put("acc", hasSensor(Sensor.TYPE_ACCELEROMETER))
            put("gyr", hasSensor(Sensor.TYPE_GYROSCOPE))
            put("mag", hasSensor(Sensor.TYPE_MAGNETIC_FIELD))
            put("bar", hasSensor(Sensor.TYPE_PRESSURE))
            put("loc", context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION))
        }
        RecordingCoordinator.io.execute {
            try {
                val ret = JSObject()
                ret.put("recording", state != null)
                state?.let {
                    ret.put("recordingId", it.recordingId)
                    ret.put("startedAt", it.startedAt)
                }
                ret.put("chunksPending", RecordingFiles.countPending(dir))
                ret.put("bytesPending", RecordingFiles.totalBytes(dir))
                ret.put("available", available)
                val rates = RecordingCoordinator.rates
                if (state != null && rates != null) {
                    val now = SystemClock.elapsedRealtime()
                    val hz = JSObject()
                    for ((i, name) in RecordingSensors.NAMES.withIndex()) {
                        hz.put(name, Math.round(rates.measuredHz(i, now) * 10) / 10.0)
                    }
                    ret.put("measuredHz", hz)
                }
                if (stopReason != null) ret.put("stopReason", stopReason)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Could not read recording status: ${e.message}", "INTERNAL")
            }
        }
    }

    @PluginMethod
    fun listRecordingChunks(call: PluginCall) {
        val limit = (call.getInt("limit") ?: 100).coerceIn(1, 10_000)
        val dir = RecordingCoordinator.dir(context)
        RecordingCoordinator.io.execute {
            try {
                val arr = JSArray()
                RecordingFiles.listPending(dir).take(limit).forEach { arr.put(JSObject(it.metaJson)) }
                call.resolve(JSObject().put("chunks", arr))
            } catch (e: Exception) {
                call.reject("Could not list chunks: ${e.message}", "INTERNAL")
            }
        }
    }

    @PluginMethod
    fun getRecordingChunk(call: PluginCall) {
        val chunkId = call.getString("chunkId")
        if (chunkId == null || !RecordingFiles.isSafeChunkId(chunkId)) {
            call.reject("Unknown chunkId", "INVALID_ARGUMENT")
            return
        }
        val dir = RecordingCoordinator.dir(context)
        RecordingCoordinator.io.execute {
            try {
                val json = RecordingFiles.jsonFile(dir, chunkId)
                val gz = RecordingFiles.gzFile(dir, chunkId)
                if (!json.isFile || !gz.isFile) {
                    call.reject("Unknown chunkId", "INVALID_ARGUMENT")
                    return@execute
                }
                val ret = ChunkJson.fillHeader(JSObject(), json.readText(Charsets.UTF_8), AndroidDeviceInfo(context))
                ret.put("data", Base64.encodeToString(gz.readBytes(), Base64.NO_WRAP))
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Could not read chunk: ${e.message}", "INTERNAL")
            }
        }
    }

    @PluginMethod
    fun ackRecordingChunk(call: PluginCall) {
        val chunkId = call.getString("chunkId")
        if (chunkId == null || !RecordingFiles.isSafeChunkId(chunkId)) {
            call.reject("Unknown chunkId", "INVALID_ARGUMENT")
            return
        }
        val dir = RecordingCoordinator.dir(context)
        RecordingCoordinator.io.execute {
            try {
                // Metadata first: a chunk without .json is no longer listed.
                RecordingFiles.delete(dir, chunkId)
                call.resolve()
            } catch (e: Exception) {
                call.reject("Could not delete chunk: ${e.message}", "INTERNAL")
            }
        }
    }

    // ---- upload ----

    @PluginMethod
    fun configureUpload(call: PluginCall) {
        val settings = try {
            UploadSettings.validate(
                endpointBaseUrl = call.getString("endpointBaseUrl"),
                deviceId = call.getString("deviceId"),
                token = call.getString("token"),
                uploadRecordings = call.getBoolean("uploadRecordings"),
                uploadEvents = call.getBoolean("uploadEvents"),
                wifiOnly = call.getBoolean("wifiOnly"),
                eventBatchSize = call.getInt("eventBatchSize"),
            )
        } catch (e: IllegalArgumentException) {
            call.reject(e.message, "INVALID_ARGUMENT")
            return
        }
        val token = call.getString("token") ?: ""
        RecordingCoordinator.io.execute {
            try {
                UploadRuntime.store(context).save(settings, token)
                UploadScheduler.ensurePeriodic(context)
                UploadScheduler.enqueue(context, force = true, replace = true)
                call.resolve()
            } catch (e: Exception) {
                call.reject("Could not store upload configuration: ${e.javaClass.simpleName}", "INTERNAL")
            }
        }
    }

    @PluginMethod
    fun clearUpload(call: PluginCall) {
        RecordingCoordinator.io.execute {
            try {
                UploadScheduler.cancelAll(context)
                UploadRuntime.store(context).clear()
                call.resolve()
            } catch (e: Exception) {
                call.reject("Could not clear upload configuration: ${e.javaClass.simpleName}", "INTERNAL")
            }
        }
    }

    @PluginMethod
    fun getUploadStatus(call: PluginCall) {
        RecordingCoordinator.io.execute {
            try {
                val store = UploadRuntime.store(context)
                val cfg = store.loadConfig()
                val st = store.loadStatus()
                val chunks = UploadRuntime.chunkSource(context)
                val pendingEvents = UploadRuntime.eventSource(context).count()
                val pendingChunks = chunks.countPending()
                var state = if (cfg == null) UploadStates.DISABLED else st.state
                if (cfg != null && state == UploadStates.IDLE) {
                    if (UploadRuntime.uploader(context).isRunning) {
                        state = UploadStates.UPLOADING
                    } else if (pendingEvents + pendingChunks > 0 &&
                        (NetworkInfoHelper.kind(context) == "none" || (cfg.wifiOnly && !NetworkInfoHelper.isUnmetered(context)))
                    ) {
                        state = UploadStates.WAITING_NETWORK
                    }
                }
                val ret = JSObject()
                ret.put("configured", cfg != null)
                ret.put("state", state)
                if (cfg != null) {
                    ret.put("endpointBaseUrl", cfg.baseUrl)
                    ret.put("deviceId", cfg.deviceId)
                    ret.put("uploadRecordings", cfg.uploadRecordings)
                    ret.put("uploadEvents", cfg.uploadEvents)
                    ret.put("wifiOnly", cfg.wifiOnly)
                }
                ret.put("pendingChunks", pendingChunks)
                ret.put("pendingEvents", pendingEvents)
                ret.put("rejectedChunks", chunks.countRejected())
                ret.put("uploadedChunks", st.uploadedChunks)
                ret.put("uploadedEvents", st.uploadedEvents)
                st.lastSuccessAt?.let { ret.put("lastSuccessAt", it) }
                st.lastErrorAt?.let { ret.put("lastErrorAt", it) }
                st.lastError?.let { ret.put("lastError", it) }
                st.lastHttpStatus?.let { ret.put("lastHttpStatus", it) }
                if (state == UploadStates.BACKOFF) st.nextAttemptAt?.let { ret.put("nextAttemptAt", it) }
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Could not read upload status: ${e.message}", "INTERNAL")
            }
        }
    }

    @PluginMethod
    fun uploadNow(call: PluginCall) {
        RecordingCoordinator.io.execute {
            try {
                val store = UploadRuntime.store(context)
                if (store.loadConfig() == null) {
                    call.reject("configureUpload first", "INVALID_ARGUMENT")
                    return@execute
                }
                if (store.loadStatus().state != UploadStates.AUTH_FAILED) {
                    UploadScheduler.enqueue(context, force = true, expedited = true, replace = true)
                }
                call.resolve()
            } catch (e: Exception) {
                call.reject("Could not start upload: ${e.message}", "INTERNAL")
            }
        }
    }

    // ---- heartbeat ----

    @PluginMethod
    fun startHeartbeat(call: PluginCall) {
        val interval = HeartbeatCoordinator.clampInterval(call.getDouble("intervalSec"))
        if (UploadRuntime.store(context).loadConfig() == null) {
            call.reject("configureUpload first", "INVALID_ARGUMENT")
            return
        }
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            call.reject("Fine location permission is required", "PERMISSION_DENIED")
            return
        }
        try {
            HeartbeatCoordinator.begin(context, interval)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Could not start heartbeat: ${e.message}", "INTERNAL", e)
        }
    }

    @PluginMethod
    fun stopHeartbeat(call: PluginCall) {
        try {
            HeartbeatCoordinator.end(context)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Could not stop heartbeat: ${e.message}", "INTERNAL", e)
        }
    }

    @PluginMethod
    fun getHeartbeatStatus(call: PluginCall) {
        val state = HeartbeatCoordinator.loadState(context)
        val c = UploadRuntime.heartbeat(context).counters
        val ret = JSObject()
        ret.put("running", state != null)
        state?.let { ret.put("intervalSec", it.intervalSec) }
        ret.put("sent", c.sent)
        ret.put("succeeded", c.succeeded)
        c.lastSentAt?.let { ret.put("lastSentAt", it) }
        c.lastSuccessAt?.let { ret.put("lastSuccessAt", it) }
        c.lastError?.let { ret.put("lastError", it) }
        c.lastHttpStatus?.let { ret.put("lastHttpStatus", it) }
        call.resolve(ret)
    }

    private val sensorManager by lazy { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    private fun hasSensor(type: Int) = sensorManager.getDefaultSensor(type) != null

    // ---- helpers ----

    private fun declaredPermissions(): Set<String> = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions?.toSet() ?: emptySet()
    } catch (e: Exception) {
        Log.w(TAG, "could not read declared permissions", e)
        // Unknown: do not block the request.
        setOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    /** Converts nested maps (from the pure reading mappers) to a JSObject. */
    @Suppress("UNCHECKED_CAST")
    private fun toJs(map: Map<String, Any>): JSObject {
        val o = JSObject()
        for ((k, v) in map) o.put(k, if (v is Map<*, *>) toJs(v as Map<String, Any>) else v)
        return o
    }

    private fun parseConfig(call: PluginCall): TrackingConfig {
        val d = TrackingConfig()
        fun positive(name: String, default: Double): Double {
            val v = call.getDouble(name) ?: return default
            require(v.isFinite() && v > 0) { "$name must be a positive number" }
            return v
        }
        return TrackingConfig(
            harshBrakeMps2 = positive("harshBrakeMps2", d.harshBrakeMps2),
            harshAccelMps2 = positive("harshAccelMps2", d.harshAccelMps2),
            locationIntervalMs = positive("locationIntervalMs", d.locationIntervalMs.toDouble()).toLong(),
            accelSampleHz = positive("accelSampleHz", d.accelSampleHz),
            tripStartSpeedMps = positive("tripStartSpeedMps", d.tripStartSpeedMps),
            tripEndIdleMs = positive("tripEndIdleMs", d.tripEndIdleMs.toDouble()).toLong(),
            minEventGapMs = positive("minEventGapMs", d.minEventGapMs.toDouble()).toLong(),
            notificationTitle = call.getString("notificationTitle") ?: d.notificationTitle,
            notificationText = call.getString("notificationText") ?: d.notificationText,
        )
    }

    private fun toJson(e: StoredEvent): JSObject = EventJson.fill(JSObject(), e)

    companion object {
        private const val TAG = "DrivingSensors"
        private const val DEFAULT_LIMIT = 500
        const val ALIAS_LOCATION = "location"
        const val ALIAS_BACKGROUND = "backgroundLocation"
        const val ALIAS_NOTIFICATIONS = "notifications"
    }
}
