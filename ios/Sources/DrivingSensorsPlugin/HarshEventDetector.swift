import Foundation

enum HarshType: String {
    case brake = "HARSH_BRAKE"
    case accel = "HARSH_ACCEL"
}

struct HarshEvent: Equatable {
    var type: HarshType
    var ts: Int64
    var peakMps2: Double
    var speedMps: Double
    var lat: Double
    var lon: Double
}

/// Pure harsh-event detector. Accelerometer input is linear acceleration in m/s² (gravity
/// removed) plus the latest gravity vector. See SPEC.md "Harsh event detection".
final class HarshEventDetector {
    static let smoothingWindowMs: Int64 = 250
    static let minDurationMs: Int64 = 200
    static let classifyDelayMs: Int64 = 1000
    static let giveUpMs: Int64 = 5000
    static let minSpeedDeltaMps = 0.5

    private struct Candidate {
        var start: Int64
        var peak: Double
        var v0: Fix?
    }

    private struct Pending {
        var start: Int64
        var end: Int64
        var peak: Double
        var v0: Fix?
    }

    private let brakeThreshold: Double
    private let accelThreshold: Double
    private let minEventGapMs: Int64
    private let trigger: Double
    private let log: ((String) -> Void)?

    private var window: [(ts: Int64, m: Double)] = []
    private var windowSum = 0.0
    private var fixes: [Fix] = []
    private var candidate: Candidate?
    private var pending: [Pending] = []
    private var lastEmittedEnd: Int64?

    init(harshBrakeMps2: Double, harshAccelMps2: Double, minEventGapMs: Int64, log: ((String) -> Void)? = nil) {
        brakeThreshold = harshBrakeMps2
        accelThreshold = harshAccelMps2
        self.minEventGapMs = minEventGapMs
        trigger = min(harshBrakeMps2, harshAccelMps2)
        self.log = log
    }

    /// Horizontal magnitude: removes the gravity-axis component of `a`.
    static func horizontalMagnitude(ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double) -> Double {
        let gn = (gx * gx + gy * gy + gz * gz).squareRoot()
        if gn < 1 { return (ax * ax + ay * ay + az * az).squareRoot() }
        let ux = gx / gn, uy = gy / gn, uz = gz / gn
        let dot = ax * ux + ay * uy + az * uz
        let hx = ax - dot * ux, hy = ay - dot * uy, hz = az - dot * uz
        return (hx * hx + hy * hy + hz * hz).squareRoot()
    }

    func onAccel(ts: Int64, ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double) {
        let m = HarshEventDetector.horizontalMagnitude(ax: ax, ay: ay, az: az, gx: gx, gy: gy, gz: gz)
        window.append((ts, m))
        windowSum += m
        while let first = window.first, first.ts <= ts - HarshEventDetector.smoothingWindowMs {
            windowSum -= first.m
            window.removeFirst()
        }
        let smoothed = max(0, windowSum / Double(window.count))

        if var c = candidate {
            c.peak = max(c.peak, smoothed)
            if smoothed < 0.7 * trigger {
                candidate = nil
                if ts - c.start < HarshEventDetector.minDurationMs {
                    log?("discard candidate start=\(c.start): too short (\(ts - c.start) ms)")
                } else {
                    pending.append(Pending(start: c.start, end: ts, peak: c.peak, v0: c.v0))
                }
            } else {
                candidate = c
            }
        } else if smoothed > trigger {
            candidate = Candidate(start: ts, peak: smoothed, v0: latestFix(atOrBefore: ts))
        }
        expire(now: ts)
    }

    /// Feeds a fix; returns events classified by it.
    func onFix(_ fix: Fix) -> [HarshEvent] {
        fixes.append(fix)
        if fixes.count > 120 { fixes.removeFirst(fixes.count - 120) }
        expire(now: fix.ts)

        var out: [HarshEvent] = []
        var remaining: [Pending] = []
        for p in pending {
            if fix.ts >= p.end + HarshEventDetector.classifyDelayMs {
                if let e = classify(p, v1: fix) { out.append(e) }
            } else {
                remaining.append(p)
            }
        }
        pending = remaining
        return out
    }

    /// Drops pending candidates that waited too long for a fix.
    func tick(now: Int64) { expire(now: now) }

    private func expire(now: Int64) {
        pending.removeAll { p in
            let gone = now - p.end > HarshEventDetector.giveUpMs
            if gone { log?("discard candidate start=\(p.start): no qualifying fix within 5 s") }
            return gone
        }
    }

    private func latestFix(atOrBefore ts: Int64) -> Fix? {
        fixes.last { $0.ts <= ts }
    }

    private func classify(_ p: Pending, v1 fix: Fix) -> HarshEvent? {
        guard let f0 = p.v0, let v0 = f0.speedMps, let v1 = fix.speedMps else {
            log?("discard candidate start=\(p.start): speed unknown")
            return nil
        }
        let delta = v1 - v0
        if abs(delta) < HarshEventDetector.minSpeedDeltaMps {
            log?("discard candidate start=\(p.start): speed delta \(delta) too small")
            return nil
        }
        let type: HarshType = delta < 0 ? .brake : .accel
        let needed = type == .brake ? brakeThreshold : accelThreshold
        if p.peak < needed {
            log?("discard candidate start=\(p.start): peak \(p.peak) below \(type.rawValue) threshold \(needed)")
            return nil
        }
        if let last = lastEmittedEnd, p.start - last < minEventGapMs {
            log?("discard candidate start=\(p.start): within min gap of previous event")
            return nil
        }
        lastEmittedEnd = p.end
        return HarshEvent(type: type, ts: p.start, peakMps2: p.peak, speedMps: v0, lat: f0.lat, lon: f0.lon)
    }
}
