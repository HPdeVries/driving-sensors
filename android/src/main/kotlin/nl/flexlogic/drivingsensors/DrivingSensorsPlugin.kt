package nl.flexlogic.drivingsensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
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
            if (TrackingCoordinator.loadState(context) != null && isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                TrackingCoordinator.startService(context)
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

    // ---- helpers ----

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

    private fun toJson(e: StoredEvent): JSObject {
        val r = e.record
        val o = JSObject()
        o.put("id", e.id)
        o.put("ts", r.ts)
        o.put("type", r.type)
        o.put("sessionId", r.sessionId)
        r.lat?.let { o.put("lat", it) }
        r.lon?.let { o.put("lon", it) }
        r.speedMps?.let { o.put("speedMps", it) }
        r.accuracyM?.let { o.put("accuracyM", it) }
        r.peakMps2?.let { o.put("peakMps2", it) }
        return o
    }

    companion object {
        private const val TAG = "DrivingSensors"
        private const val DEFAULT_LIMIT = 500
        const val ALIAS_LOCATION = "location"
        const val ALIAS_BACKGROUND = "backgroundLocation"
        const val ALIAS_NOTIFICATIONS = "notifications"
    }
}
