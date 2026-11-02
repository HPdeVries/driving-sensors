package nl.flexlogic.drivingsensors

/** What to do when the runtime wakes up (boot, package replaced, plugin load). */
enum class ResumeAction { START_SERVICE, UPLOAD_ONLY, NOTHING }

/** Why persisted consumer state might be cleared; only some causes are allowed to. */
enum class StopCause {
    EXPLICIT_STOP,
    STORAGE_FULL,
    PERMISSION_LOST,
    ERROR,
    SERVICE_DESTROY,
    TIMEOUT,
    SERVICE_RESTART,
    PROCESS_DEATH,
    PERMISSION_MISSING_AT_RESUME,
}

/** Pure decision logic for resuming, kept free of Android classes so it is JVM-testable. */
object ResumeDecision {
    fun decide(
        anyConsumerPersisted: Boolean,
        fineLocationGranted: Boolean,
        backgroundLocationGranted: Boolean,
        sdkInt: Int,
        fromBackground: Boolean = true,
    ): ResumeAction {
        if (!anyConsumerPersisted) return ResumeAction.NOTHING
        if (!fineLocationGranted) return ResumeAction.UPLOAD_ONLY
        // Android 14+ forbids starting a location FGS from the background without background location.
        val needsBackground = fromBackground && sdkInt >= 29
        if (needsBackground && !backgroundLocationGranted) return ResumeAction.UPLOAD_ONLY
        return ResumeAction.START_SERVICE
    }

    /** Only the matching stop method and explicit stop reasons clear persisted state. */
    fun clearsPersistedState(cause: StopCause): Boolean = when (cause) {
        StopCause.EXPLICIT_STOP, StopCause.STORAGE_FULL, StopCause.PERMISSION_LOST, StopCause.ERROR -> true
        StopCause.SERVICE_DESTROY, StopCause.TIMEOUT, StopCause.SERVICE_RESTART,
        StopCause.PROCESS_DEATH, StopCause.PERMISSION_MISSING_AT_RESUME -> false
    }

    /** True for ForegroundServiceStartNotAllowedException (API 31+), matched by name to stay API-safe. */
    fun isFgsStartNotAllowed(e: Throwable, sdkInt: Int): Boolean =
        sdkInt >= 31 && generateSequence(e) { it.cause }.any { it.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException" }
}
