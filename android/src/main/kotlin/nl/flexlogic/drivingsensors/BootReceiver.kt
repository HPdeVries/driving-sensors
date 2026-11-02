package nl.flexlogic.drivingsensors

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/** Resumes persisted consumers after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        RecordingCoordinator.io.execute {
            try {
                resume(app, action)
            } catch (e: Exception) {
                Log.w(TAG, "boot resume failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun resume(context: Context, action: String) {
        UploadScheduler.ensurePeriodic(context)
        val persisted = TrackingCoordinator.loadState(context) != null ||
            RecordingCoordinator.loadState(context) != null ||
            HeartbeatCoordinator.loadState(context) != null
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        when (ResumeDecision.decide(persisted, fine, bg, Build.VERSION.SDK_INT)) {
            ResumeAction.START_SERVICE -> {
                Log.i(TAG, "$action: consumers persisted, starting service")
                TrackingCoordinator.startServiceSafely(context, "boot")
            }
            ResumeAction.UPLOAD_ONLY -> {
                Log.i(TAG, "$action: consumers persisted but location (fine=$fine, background=$bg) not sufficient; not starting service, upload only")
                UploadScheduler.triggerIfPending(context)
            }
            ResumeAction.NOTHING -> {
                Log.i(TAG, "$action: no persisted consumers; upload only")
                UploadScheduler.triggerIfPending(context)
            }
        }
    }

    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "DrivingSensors"
    }
}
