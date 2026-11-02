package nl.flexlogic.drivingsensors

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WorkManager scheduling of the uploader. All triggers end up here. */
object UploadScheduler {
    private const val TAG = "DrivingSensors"
    const val ONE_TIME = "driving_sensors_upload"
    const val PERIODIC = "driving_sensors_upload_periodic"
    const val KEY_FORCE = "force"

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /**
     * Enqueues the unique one-time upload work. [force] ignores backoff; [replace] restarts a waiting
     * request (configureUpload / uploadNow). While a run is active the new request is appended so the
     * trigger is not lost.
     */
    fun enqueue(context: Context, force: Boolean = false, delayMs: Long = 0, expedited: Boolean = false, replace: Boolean = false) {
        try {
            val cfg = UploadRuntime.store(context).loadConfig() ?: return
            val builder = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints(cfg.wifiOnly))
                .setInputData(Data.Builder().putBoolean(KEY_FORCE, force).build())
            if (delayMs > 0) {
                builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            } else if (expedited) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val policy = when {
                UploadRuntime.uploader(context).isRunning -> ExistingWorkPolicy.APPEND_OR_REPLACE
                replace -> ExistingWorkPolicy.REPLACE
                else -> ExistingWorkPolicy.KEEP
            }
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(ONE_TIME, policy, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "could not schedule upload", e)
        }
    }

    fun ensurePeriodic(context: Context) {
        try {
            val cfg = UploadRuntime.store(context).loadConfig() ?: return
            val req = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints(cfg.wifiOnly))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        } catch (e: Exception) {
            Log.w(TAG, "could not schedule periodic upload", e)
        }
    }

    fun cancelAll(context: Context) {
        try {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelUniqueWork(ONE_TIME)
            wm.cancelUniqueWork(PERIODIC)
        } catch (e: Exception) {
            Log.w(TAG, "could not cancel upload work", e)
        }
    }

    /** Cheap trigger: only enqueues when upload is configured. Safe to call from any thread. */
    fun trigger(context: Context) = enqueue(context)

    /** Enqueues when there is something to upload. Blocks on IO; call from a background thread. */
    fun triggerIfPending(context: Context) {
        try {
            val store = UploadRuntime.store(context)
            val cfg = store.loadConfig() ?: return
            if (store.loadStatus().state == UploadStates.AUTH_FAILED) return
            val pending = (cfg.uploadRecordings && UploadRuntime.chunkSource(context).countPending() > 0) ||
                (cfg.uploadEvents && UploadRuntime.eventSource(context).count() > 0)
            if (pending) enqueue(context)
        } catch (e: Exception) {
            Log.w(TAG, "pending check failed", e)
        }
    }
}

class UploadWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val force = inputData.getBoolean(UploadScheduler.KEY_FORCE, false)
        try {
            val outcome = UploadRuntime.uploader(ctx).run(force)
            when {
                // Another run is active (e.g. the periodic one): look again shortly so nothing is lost.
                outcome.busy -> UploadScheduler.enqueue(ctx, delayMs = 15_000)
                outcome.retryAt != null ->
                    UploadScheduler.enqueue(ctx, delayMs = (outcome.retryAt - System.currentTimeMillis()).coerceAtLeast(1_000))
            }
        } catch (e: Exception) {
            Log.w("DrivingSensors", "upload run failed", e)
        }
        // Backoff is owned by the plugin (nextAttemptAt), not by WorkManager's retry policy.
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Data upload", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_driving_sensors_notification)
            .setContentTitle("Uploading driving data")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL = "driving_sensors_upload"
        private const val NOTIFICATION_ID = 7422
    }
}
