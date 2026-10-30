package nl.flexlogic.drivingsensors

/** Speed-threshold trip detection. Pure logic, see SPEC.md. */
class TripDetector(
    private val startSpeedMps: Double,
    private val endIdleMs: Long,
    private val startFixCount: Int = 3,
) {
    enum class Type { TRIP_START, TRIP_END }

    data class Event(val type: Type, val fix: Fix)

    var inTrip = false
        private set

    private var streakFirst: Fix? = null
    private var streakCount = 0
    private var lastFast: Fix? = null

    fun onFix(fix: Fix): List<Event> {
        val speed = fix.speedMps ?: return emptyList()
        val fast = speed >= startSpeedMps
        if (!inTrip) {
            if (!fast) {
                streakCount = 0
                streakFirst = null
                return emptyList()
            }
            if (streakCount == 0) streakFirst = fix
            streakCount++
            lastFast = fix
            if (streakCount < startFixCount) return emptyList()
            val first = streakFirst ?: fix
            inTrip = true
            streakCount = 0
            streakFirst = null
            return listOf(Event(Type.TRIP_START, first))
        }
        if (fast) {
            lastFast = fix
            return emptyList()
        }
        return evaluateEnd(fix.ts)
    }

    /** Call periodically so a trip also ends when no fixes arrive. */
    fun onTick(nowMs: Long): List<Event> = if (inTrip) evaluateEnd(nowMs) else emptyList()

    /** Ends the trip immediately (stopTracking). */
    fun forceEnd(): Event? {
        if (!inTrip) return null
        val last = lastFast ?: return null
        inTrip = false
        lastFast = null
        return Event(Type.TRIP_END, last)
    }

    private fun evaluateEnd(nowMs: Long): List<Event> {
        val last = lastFast ?: return emptyList()
        if (nowMs - last.ts < endIdleMs) return emptyList()
        inTrip = false
        lastFast = null
        return listOf(Event(Type.TRIP_END, last))
    }
}
