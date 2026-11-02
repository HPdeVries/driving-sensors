import Foundation
import CoreLocation

/// Pure decisions for v0.3.1 (resume after process stop / reboot).
enum SignificantChangePolicy {
    /// Monitor significant location changes while any consumer is persisted and the app has
    /// "Always" authorization: iOS then relaunches a terminated app in the background.
    static func shouldMonitor(persisted: LocationConsumers, authorization: CLAuthorizationStatus) -> Bool {
        !persisted.isEmpty && authorization == .authorizedAlways
    }
}

/// Which consumers are persisted as running, read from storage only (never from runtime state).
enum PersistedConsumers {
    static let trackingKey = "DrivingSensors.state"
    static let recordingKey = "DrivingSensors.recording"

    private struct ActiveFlag: Decodable { var active: Bool }

    static func load(defaults: UserDefaults, heartbeatRunning: Bool) -> LocationConsumers {
        var c: LocationConsumers = []
        if let d = defaults.data(forKey: trackingKey),
           let f = try? JSONDecoder().decode(ActiveFlag.self, from: d), f.active { c.insert(.tracking) }
        if defaults.data(forKey: recordingKey) != nil { c.insert(.recording) }
        if heartbeatRunning { c.insert(.heartbeat) }
        return c
    }
}
