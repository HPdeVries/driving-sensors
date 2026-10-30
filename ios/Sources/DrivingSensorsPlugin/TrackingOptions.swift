import Foundation

struct DrivingSensorsError: Error {
    let message: String
    let code: String

    static func invalid(_ m: String) -> DrivingSensorsError { .init(message: m, code: "INVALID_ARGUMENT") }
}

/// Resolved tracking options (defaults from definitions.ts). Notification fields are Android-only.
struct TrackingOptions: Codable, Equatable {
    var harshBrakeMps2 = 3.0
    var harshAccelMps2 = 3.0
    var locationIntervalMs = 1000.0
    var accelSampleHz = 50.0
    var tripStartSpeedMps = 4.2
    var tripEndIdleMs = 180_000.0
    var minEventGapMs = 3000.0

    /// Parses the JS options dictionary; every numeric value must be a finite number > 0.
    static func parse(_ dict: [String: Any]) throws -> TrackingOptions {
        var o = TrackingOptions()
        func num(_ key: String, _ target: inout Double) throws {
            guard let raw = dict[key], !(raw is NSNull) else { return }
            guard let n = raw as? NSNumber else { throw DrivingSensorsError.invalid("\(key) must be a number") }
            guard n.doubleValue > 0, n.doubleValue.isFinite else { throw DrivingSensorsError.invalid("\(key) must be > 0") }
            target = n.doubleValue
        }
        try num("harshBrakeMps2", &o.harshBrakeMps2)
        try num("harshAccelMps2", &o.harshAccelMps2)
        try num("locationIntervalMs", &o.locationIntervalMs)
        try num("accelSampleHz", &o.accelSampleHz)
        try num("tripStartSpeedMps", &o.tripStartSpeedMps)
        try num("tripEndIdleMs", &o.tripEndIdleMs)
        try num("minEventGapMs", &o.minEventGapMs)
        return o
    }
}
