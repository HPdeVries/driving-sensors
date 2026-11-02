package nl.flexlogic.drivingsensors

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Base64OutputStream
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Callable
import org.json.JSONObject

/** Chunk files on disk behind the [ChunkSource] interface. */
class FileChunkSource(private val dir: File) : ChunkSource {
    override fun pending() = RecordingFiles.listPending(dir)

    override fun openData(chunkId: String): InputStream? {
        val gz = RecordingFiles.gzFile(dir, chunkId)
        return if (gz.isFile && RecordingFiles.jsonFile(dir, chunkId).isFile) gz.inputStream() else null
    }

    override fun delete(chunkId: String) = RecordingFiles.delete(dir, chunkId)
    override fun reject(chunkId: String) {
        RecordingFiles.reject(dir, chunkId)
    }

    override fun countPending() = RecordingFiles.countPending(dir)
    override fun countRejected() = RecordingFiles.countRejected(dir)
}

/** Reads and acks events through the single database thread (blocks the caller). */
class StoreEventSource(private val store: EventStore) : EventSource {
    override fun read(limit: Int) = store.sync { queryNow(0, limit) }
    override fun ack(upToId: Long) = store.sync { ackNow(upToId) }
    override fun count() = store.sync { countNow() }
}

class AndroidDeviceInfo(private val context: Context) : DeviceInfo {
    override val model: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    override val osVersion: String get() = Build.VERSION.RELEASE

    override fun sensorInfo(): JSONObject {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val o = JSONObject()
        fun describe(key: String, type: Int) {
            sm.getDefaultSensor(type)?.let { o.put(key, "${it.vendor} ${it.name}, calibrated") }
        }
        describe("acc", Sensor.TYPE_ACCELEROMETER)
        describe("gyr", Sensor.TYPE_GYROSCOPE)
        describe("mag", Sensor.TYPE_MAGNETIC_FIELD)
        describe("bar", Sensor.TYPE_PRESSURE)
        o.put("loc", "Fused location provider, high accuracy")
        return o
    }
}

/** Counts started activities to tell foreground from background. */
object AppStateTracker {
    private val started: MutableSet<Activity> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private var registered = false

    @Synchronized
    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started.add(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                started.remove(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** The plugin loads while its activity is already visible. */
    fun markStarted(activity: Activity?) {
        if (activity != null) started.add(activity)
    }

    val foreground: Boolean get() = started.isNotEmpty()
}

class AndroidHeartbeatEnv(
    private val context: Context,
    private val events: EventSource,
    private val chunks: ChunkSource,
) : HeartbeatEnv {
    override fun processUptimeSec(): Long = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
    override fun appState() = if (AppStateTracker.foreground) "foreground" else "background"

    private fun battery(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    override fun batteryLevel(): Int {
        val i = battery() ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else Math.round(level * 100f / scale)
    }

    override fun charging(): Boolean {
        val status = battery()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun network(): String = NetworkInfoHelper.kind(context)
    override fun tracking() = TrackingCoordinator.serviceRunning
    override fun inTrip() = TrackingCoordinator.inTrip
    override fun recording() = RecordingCoordinator.recordingRunning
    override fun pendingEvents() = events.count()
    override fun pendingChunks() = chunks.countPending()
}

object NetworkInfoHelper {
    @SuppressLint("MissingPermission")
    fun kind(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = if (active == null) null else cm.getNetworkCapabilities(active)
        when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    } catch (e: Exception) {
        "none"
    }

    @SuppressLint("MissingPermission")
    fun isUnmetered(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.isActiveNetworkMetered.not()
    } catch (e: Exception) {
        false
    }
}

/** Process-wide wiring of the uploader and heartbeat sender. */
object UploadRuntime {
    @Volatile
    private var store: UploadConfigStore? = null
    @Volatile
    private var uploader: Uploader? = null
    @Volatile
    private var heartbeat: HeartbeatSender? = null

    fun store(context: Context): UploadConfigStore =
        store ?: synchronized(this) { store ?: UploadConfigStore(context).also { store = it } }

    fun chunkSource(context: Context): ChunkSource = FileChunkSource(RecordingCoordinator.dir(context))

    fun eventSource(context: Context): EventSource = StoreEventSource(EventStore.get(context))

    fun uploader(context: Context): Uploader =
        uploader ?: synchronized(this) {
            uploader ?: Uploader(
                store(context),
                eventSource(context),
                chunkSource(context),
                HttpURLConnectionTransport(),
                AndroidDeviceInfo(context.applicationContext),
                { out -> Base64OutputStream(out, Base64.NO_WRAP) },
            ).also { uploader = it }
        }

    fun heartbeat(context: Context): HeartbeatSender =
        heartbeat ?: synchronized(this) {
            heartbeat ?: HeartbeatSender(
                store(context),
                HttpURLConnectionTransport(),
                AndroidDeviceInfo(context.applicationContext),
                AndroidHeartbeatEnv(context.applicationContext, eventSource(context), chunkSource(context)),
            ).also { heartbeat = it }
        }
}
