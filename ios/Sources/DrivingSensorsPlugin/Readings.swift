import Foundation
import CoreLocation

/// Latest location fix, as exposed through `getStatus().lastLocation` and `SensorReadings.location`.
struct LocationSnapshot: Equatable {
    var ts: Int64
    var lat: Double
    var lon: Double
    var speedMps: Double?
    var accuracyM: Double?

    var dictionary: [String: Any] {
        var d: [String: Any] = ["ts": ts, "lat": lat, "lon": lon]
        if let speedMps { d["speedMps"] = speedMps }
        if let accuracyM { d["accuracyM"] = accuracyM }
        return d
    }

    /// Maps a CLLocation; negative speed/accuracy mean "unknown" and are left out.
    init(_ loc: CLLocation) {
        ts = Int64(loc.timestamp.timeIntervalSince1970 * 1000)
        lat = loc.coordinate.latitude
        lon = loc.coordinate.longitude
        speedMps = loc.speed >= 0 ? loc.speed : nil
        accuracyM = loc.horizontalAccuracy >= 0 ? loc.horizontalAccuracy : nil
    }

    init(ts: Int64, lat: Double, lon: Double, speedMps: Double? = nil, accuracyM: Double? = nil) {
        self.ts = ts
        self.lat = lat
        self.lon = lon
        self.speedMps = speedMps
        self.accuracyM = accuracyM
    }
}

/// One device-motion sample: linear acceleration and gravity in m/s² (device axes).
struct MotionSample: Equatable {
    var ts: Int64
    var ax: Double, ay: Double, az: Double
    var gx: Double, gy: Double, gz: Double

    var horizontalMps2: Double {
        HarshEventDetector.horizontalMagnitude(ax: ax, ay: ay, az: az, gx: gx, gy: gy, gz: gz)
    }

    func snapshot(peakHorizontalMps2: Double? = nil, samples: Int? = nil) -> MotionSnapshot {
        MotionSnapshot(sample: self, horizontalMps2: horizontalMps2,
                       peakHorizontalMps2: peakHorizontalMps2, samples: samples)
    }
}

struct MotionSnapshot: Equatable {
    var sample: MotionSample
    var horizontalMps2: Double
    var peakHorizontalMps2: Double?
    var samples: Int?

    var dictionary: [String: Any] {
        let s = sample
        var d: [String: Any] = [
            "ts": s.ts, "ax": s.ax, "ay": s.ay, "az": s.az,
            "gx": s.gx, "gy": s.gy, "gz": s.gz, "horizontalMps2": horizontalMps2
        ]
        if let peakHorizontalMps2 { d["peakHorizontalMps2"] = peakHorizontalMps2 }
        if let samples { d["samples"] = samples }
        return d
    }
}

/// Aggregates the samples of a short sampling window: last sample, peak horizontal, count.
struct MotionWindow {
    private(set) var last: MotionSample?
    private(set) var peakHorizontalMps2 = 0.0
    private(set) var count = 0

    mutating func add(_ sample: MotionSample) {
        last = sample
        peakHorizontalMps2 = max(peakHorizontalMps2, sample.horizontalMps2)
        count += 1
    }

    /// nil when no samples arrived.
    var snapshot: MotionSnapshot? {
        guard let last else { return nil }
        return last.snapshot(peakHorizontalMps2: peakHorizontalMps2, samples: count)
    }
}

enum SensorReadings {
    /// Builds the `SensorReadings` dictionary; `location`/`motion` are omitted when nil.
    static func dictionary(location: LocationSnapshot?, motion: MotionSnapshot?) -> [String: Any] {
        var d: [String: Any] = ["hasLocation": location != nil, "hasMotion": motion != nil]
        if let location { d["location"] = location.dictionary }
        if let motion { d["motion"] = motion.dictionary }
        return d
    }

    static func clamp(_ value: Double?, default def: Double, min lo: Double, max hi: Double) -> Double {
        Swift.min(hi, Swift.max(lo, value ?? def))
    }
}

/// Receives the result of a one-shot `requestLocation()` on a dedicated CLLocationManager.
/// Used on the main thread only.
final class OneShotLocationDelegate: NSObject, CLLocationManagerDelegate {
    private var completion: ((CLLocation?) -> Void)?

    init(completion: @escaping (CLLocation?) -> Void) {
        self.completion = completion
    }

    func finish(_ location: CLLocation?) {
        guard let c = completion else { return }
        completion = nil
        c(location)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        finish(locations.last)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish(nil)
    }
}
