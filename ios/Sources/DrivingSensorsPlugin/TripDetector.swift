import Foundation

enum TripTransition: Equatable {
    case start(Fix) // first of the 3 consecutive fast fixes
    case end(Fix)   // last fix at or above the start speed
}

/// Pure trip state machine. See SPEC.md "Trip detection".
struct TripDetector {
    static let requiredFastFixes = 3

    let startSpeedMps: Double
    let endIdleMs: Int64
    private(set) var inTrip = false
    private var candidates: [Fix] = []
    private var lastFast: Fix?

    init(startSpeedMps: Double, endIdleMs: Int64) {
        self.startSpeedMps = startSpeedMps
        self.endIdleMs = endIdleMs
    }

    mutating func onFix(_ fix: Fix) -> TripTransition? {
        // Unknown speed neither starts nor extends a trip.
        if let s = fix.speedMps {
            let fast = s >= startSpeedMps
            if inTrip {
                if fast { lastFast = fix }
            } else if fast {
                candidates.append(fix)
                if candidates.count >= TripDetector.requiredFastFixes {
                    let first = candidates[0]
                    inTrip = true
                    lastFast = fix
                    candidates.removeAll()
                    return .start(first)
                }
            } else {
                candidates.removeAll()
            }
        }
        return inTrip ? checkEnd(now: fix.ts) : nil
    }

    /// Periodic evaluation so a stationary phone with no fixes still ends the trip.
    mutating func tick(now: Int64) -> TripTransition? {
        inTrip ? checkEnd(now: now) : nil
    }

    /// Ends the trip immediately (used by stopTracking). Returns the last fast fix.
    mutating func forceEnd() -> TripTransition? {
        guard inTrip, let last = lastFast else { return nil }
        reset()
        return .end(last)
    }

    private mutating func checkEnd(now: Int64) -> TripTransition? {
        guard let last = lastFast, now - last.ts >= endIdleMs else { return nil }
        reset()
        return .end(last)
    }

    private mutating func reset() {
        inTrip = false
        lastFast = nil
        candidates.removeAll()
    }
}
