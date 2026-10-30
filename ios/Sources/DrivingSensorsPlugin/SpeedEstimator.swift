import Foundation

/// A location fix reduced to what the pure-logic detectors need.
struct Fix: Equatable {
    var ts: Int64 // epoch ms
    var lat: Double
    var lon: Double
    var speedMps: Double?
    var accuracyM: Double?

    init(ts: Int64, lat: Double = 0, lon: Double = 0, speedMps: Double? = nil, accuracyM: Double? = nil) {
        self.ts = ts
        self.lat = lat
        self.lon = lon
        self.speedMps = speedMps
        self.accuracyM = accuracyM
    }
}

/// Uses the platform speed when valid (>= 0), else derives it from the previous fix (haversine)
/// when the time gap is between 0.5 s and 10 s.
struct SpeedEstimator {
    private var previous: (ts: Int64, lat: Double, lon: Double)?

    mutating func speed(ts: Int64, lat: Double, lon: Double, platformSpeed: Double?) -> Double? {
        defer { previous = (ts, lat, lon) }
        if let s = platformSpeed, s >= 0 { return s }
        guard let p = previous else { return nil }
        let gapMs = ts - p.ts
        guard gapMs >= 500, gapMs <= 10_000 else { return nil }
        return SpeedEstimator.haversineMeters(p.lat, p.lon, lat, lon) / (Double(gapMs) / 1000.0)
    }

    static func haversineMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let r = 6_371_000.0
        let p1 = lat1 * .pi / 180
        let p2 = lat2 * .pi / 180
        let dp = (lat2 - lat1) * .pi / 180
        let dl = (lon2 - lon1) * .pi / 180
        let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(min(1, sqrt(a)))
    }
}
