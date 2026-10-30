import Foundation
import CoreLocation
import CoreMotion
import UIKit

private let gToMps2 = 9.80665

func dsLog(_ message: String) {
    NSLog("[DrivingSensors] %@", message)
}

/// Owns location + motion updates, trip/harsh detection, permissions and the event buffer.
///
/// Threading: CLLocationManager and permission state live on the main thread. Detector state
/// and the persisted-session fields are only touched on `work`. `main` never blocks on
/// `work` while `work` waits on `main` (work only dispatches to main asynchronously).
@objc public class DrivingSensors: NSObject, CLLocationManagerDelegate, @unchecked Sendable {
    private struct PersistedState: Codable {
        var active: Bool
        var sessionId: String
        var options: TrackingOptions
    }

    private static let stateKey = "DrivingSensors.state"
    private static let askedAlwaysKey = "DrivingSensors.askedAlways"
    private static let tickInterval: TimeInterval = 10

    private(set) var store: EventStore?
    private let defaults = UserDefaults.standard
    private let work = DispatchQueue(label: "driving-sensors.work")
    private let motionQueue: OperationQueue = {
        let q = OperationQueue()
        q.name = "driving-sensors.motion"
        q.maxConcurrentOperationCount = 1
        return q
    }()
    private let motionManager = CMMotionManager()
    private lazy var manager: CLLocationManager = {
        let m = CLLocationManager()
        m.delegate = self
        return m
    }()

    // Guarded by `work`.
    private var active = false
    private var sessionId: String?
    private var options = TrackingOptions()
    private var trip = TripDetector(startSpeedMps: 4.2, endIdleMs: 180_000)
    private var harsh: HarshEventDetector?
    private var estimator = SpeedEstimator()
    private var lastLocationTs: Int64?
    private var timer: DispatchSourceTimer?

    // Main thread only.
    private var pendingAuth: (() -> Void)?
    private var activeObserver: NSObjectProtocol?

    @objc public override init() {
        super.init()
        do {
            store = try EventStore.openDefault()
        } catch {
            dsLog("could not open event store: \(error)")
        }
    }

    // MARK: - Lifecycle

    /// Called from the plugin's load(): resume if state says active and permission allows.
    func resumeIfNeeded() {
        DispatchQueue.main.async {
            guard let data = self.defaults.data(forKey: DrivingSensors.stateKey),
                  let state = try? JSONDecoder().decode(PersistedState.self, from: data),
                  state.active else { return }
            guard self.hasLocationPermission() else {
                dsLog("persisted tracking not resumed: location permission missing")
                return
            }
            self.begin(sessionId: state.sessionId, options: state.options)
        }
    }

    func startTracking(options: TrackingOptions, completion: @escaping (Result<String, DrivingSensorsError>) -> Void) {
        DispatchQueue.main.async {
            guard self.store != nil else {
                return completion(.failure(.init(message: "Event store unavailable", code: "INTERNAL")))
            }
            if let existing = self.work.sync(execute: { self.active ? self.sessionId : nil }) {
                return completion(.success(existing))
            }
            guard CLLocationManager.locationServicesEnabled() else {
                return completion(.failure(.init(message: "Location services are disabled", code: "LOCATION_DISABLED")))
            }
            guard self.hasLocationPermission() else {
                return completion(.failure(.init(message: "Location permission not granted", code: "PERMISSION_DENIED")))
            }
            if self.manager.authorizationStatus != .authorizedAlways {
                dsLog("background location (Always) not granted; tracking may stop in the background")
            }
            let id = UUID().uuidString
            self.persist(PersistedState(active: true, sessionId: id, options: options))
            self.begin(sessionId: id, options: options)
            completion(.success(id))
        }
    }

    func stopTracking(completion: @escaping () -> Void) {
        DispatchQueue.main.async {
            self.work.sync {
                self.handle(self.trip.forceEnd())
                self.timer?.cancel()
                self.timer = nil
                self.harsh = nil
                self.active = false
                self.sessionId = nil
            }
            self.manager.stopUpdatingLocation()
            self.motionManager.stopDeviceMotionUpdates()
            self.defaults.removeObject(forKey: DrivingSensors.stateKey)
            completion()
        }
    }

    func status() -> (active: Bool, sessionId: String?, inTrip: Bool, buffered: Int) {
        let s = work.sync { (active, sessionId, trip.inTrip) }
        return (s.0, s.1, s.2, store?.count() ?? 0)
    }

    private func begin(sessionId: String, options: TrackingOptions) {
        let m = manager
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        if modes.contains("location") {
            m.allowsBackgroundLocationUpdates = true
            m.showsBackgroundLocationIndicator = true
        } else {
            dsLog("WARNING: UIBackgroundModes does not contain 'location'; background tracking is disabled")
        }
        m.pausesLocationUpdatesAutomatically = false
        m.activityType = .automotiveNavigation
        m.desiredAccuracy = kCLLocationAccuracyBest
        m.distanceFilter = 10 // idle mode

        work.sync {
            self.active = true
            self.sessionId = sessionId
            self.options = options
            self.trip = TripDetector(startSpeedMps: options.tripStartSpeedMps, endIdleMs: Int64(options.tripEndIdleMs))
            self.harsh = nil
            self.estimator = SpeedEstimator()
            self.lastLocationTs = nil
            let t = DispatchSource.makeTimerSource(queue: self.work)
            t.schedule(deadline: .now() + DrivingSensors.tickInterval, repeating: DrivingSensors.tickInterval)
            t.setEventHandler { [weak self] in self?.onTick() }
            t.resume()
            self.timer?.cancel()
            self.timer = t
        }
        m.startUpdatingLocation()
    }

    private func persist(_ state: PersistedState) {
        if let data = try? JSONEncoder().encode(state) { defaults.set(data, forKey: DrivingSensors.stateKey) }
    }

    // MARK: - Trip mode (main thread)

    private func applyTripMode(_ inTrip: Bool) {
        guard work.sync(execute: { active }) else { return }
        manager.distanceFilter = inTrip ? kCLDistanceFilterNone : 10
        if inTrip {
            startMotion()
        } else {
            motionManager.stopDeviceMotionUpdates()
        }
    }

    private func startMotion() {
        guard motionManager.isDeviceMotionAvailable else {
            dsLog("device motion unavailable; harsh events disabled")
            return
        }
        let hz = work.sync { options.accelSampleHz }
        motionManager.deviceMotionUpdateInterval = 1.0 / hz
        let bootOffset = Date().timeIntervalSince1970 - ProcessInfo.processInfo.systemUptime
        motionManager.startDeviceMotionUpdates(to: motionQueue) { [weak self] motion, _ in
            guard let self, let m = motion else { return }
            let ts = Int64((m.timestamp + bootOffset) * 1000)
            let a = m.userAcceleration, g = m.gravity
            self.work.async {
                self.harsh?.onAccel(
                    ts: ts,
                    ax: a.x * gToMps2, ay: a.y * gToMps2, az: a.z * gToMps2,
                    gx: g.x * gToMps2, gy: g.y * gToMps2, gz: g.z * gToMps2)
            }
        }
    }

    // MARK: - Processing (work queue)

    private func onTick() {
        guard active else { return }
        handle(trip.tick(now: Int64(Date().timeIntervalSince1970 * 1000)))
        harsh?.tick(now: Int64(Date().timeIntervalSince1970 * 1000))
    }

    private func process(_ locations: [CLLocation]) {
        guard active, let session = sessionId else { return }
        for loc in locations where loc.horizontalAccuracy >= 0 {
            let ts = Int64(loc.timestamp.timeIntervalSince1970 * 1000)
            let c = loc.coordinate
            let speed = estimator.speed(ts: ts, lat: c.latitude, lon: c.longitude, platformSpeed: loc.speed)
            let fix = Fix(ts: ts, lat: c.latitude, lon: c.longitude, speedMps: speed, accuracyM: loc.horizontalAccuracy)
            handle(trip.onFix(fix))
            guard trip.inTrip, let detector = harsh else { continue }

            for e in detector.onFix(fix) {
                store?.insert(BufferedEvent(ts: e.ts, type: e.type.rawValue, sessionId: session,
                                            lat: e.lat, lon: e.lon, speedMps: e.speedMps, peakMps2: e.peakMps2))
            }
            if lastLocationTs == nil || ts - lastLocationTs! >= Int64(options.locationIntervalMs) - 100 {
                lastLocationTs = ts
                store?.insert(BufferedEvent(ts: ts, type: "LOCATION", sessionId: session,
                                            lat: fix.lat, lon: fix.lon, speedMps: speed, accuracyM: fix.accuracyM))
            }
        }
    }

    private func handle(_ transition: TripTransition?) {
        guard let transition, let session = sessionId else { return }
        switch transition {
        case .start(let f):
            harsh = HarshEventDetector(
                harshBrakeMps2: options.harshBrakeMps2, harshAccelMps2: options.harshAccelMps2,
                minEventGapMs: Int64(options.minEventGapMs), log: { dsLog($0) })
            lastLocationTs = nil
            store?.insert(BufferedEvent(ts: f.ts, type: "TRIP_START", sessionId: session,
                                        lat: f.lat, lon: f.lon, speedMps: f.speedMps, accuracyM: f.accuracyM))
            DispatchQueue.main.async { self.applyTripMode(true) }
        case .end(let f):
            harsh = nil
            store?.insert(BufferedEvent(ts: f.ts, type: "TRIP_END", sessionId: session,
                                        lat: f.lat, lon: f.lon, speedMps: f.speedMps, accuracyM: f.accuracyM))
            DispatchQueue.main.async { self.applyTripMode(false) }
        }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        work.async { self.process(locations) }
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        dsLog("location error: \(error.localizedDescription)")
    }

    // MARK: - Buffer

    func bufferedEvents(afterId: Int64, limit: Int) -> [[String: Any]] {
        store?.read(afterId: afterId, limit: limit) ?? []
    }

    func ackEvents(upToId: Int64) {
        store?.ack(upToId: upToId)
    }

    // MARK: - Permissions

    private func hasLocationPermission() -> Bool {
        let s = manager.authorizationStatus
        return s == .authorizedAlways || s == .authorizedWhenInUse
    }

    private func currentPermissions() -> [String: String] {
        let loc = manager.authorizationStatus
        let location: String
        let background: String
        switch loc {
        case .authorizedAlways: (location, background) = ("granted", "granted")
        case .authorizedWhenInUse: (location, background) = ("granted", "prompt")
        case .notDetermined: (location, background) = ("prompt", "prompt")
        default: (location, background) = ("denied", "denied")
        }
        let motion: String
        switch CMMotionActivityManager.authorizationStatus() {
        case .authorized: motion = "granted"
        case .notDetermined: motion = "prompt"
        default: motion = "denied"
        }
        return ["location": location, "backgroundLocation": background, "motion": motion, "notifications": "granted"]
    }

    func checkPermissions(completion: @escaping ([String: String]) -> Void) {
        DispatchQueue.main.async { completion(self.currentPermissions()) }
    }

    func requestPermissions(types: Set<String>, completion: @escaping ([String: String]) -> Void) {
        DispatchQueue.main.async {
            self.requestLocation(types) {
                self.requestBackground(types) {
                    self.requestMotion(types) {
                        completion(self.currentPermissions())
                    }
                }
            }
        }
    }

    private func requestLocation(_ types: Set<String>, next: @escaping () -> Void) {
        guard types.contains("location"), manager.authorizationStatus == .notDetermined else { return next() }
        awaitAuthorization(next) { self.manager.requestWhenInUseAuthorization() }
    }

    private func requestBackground(_ types: Set<String>, next: @escaping () -> Void) {
        guard types.contains("backgroundLocation") else { return next() }
        switch manager.authorizationStatus {
        case .notDetermined:
            break
        case .authorizedWhenInUse where !defaults.bool(forKey: DrivingSensors.askedAlwaysKey):
            break
        default:
            // Already Always, denied, or iOS will not show the upgrade prompt a second time.
            return next()
        }
        defaults.set(true, forKey: DrivingSensors.askedAlwaysKey)
        awaitAuthorization(next) { self.manager.requestAlwaysAuthorization() }
    }

    private func requestMotion(_ types: Set<String>, next: @escaping () -> Void) {
        guard types.contains("motion"), CMMotionActivityManager.isActivityAvailable(),
              CMMotionActivityManager.authorizationStatus() == .notDetermined else { return next() }
        let activity = CMMotionActivityManager()
        let now = Date()
        activity.queryActivityStarting(from: now.addingTimeInterval(-60), to: now, to: .main) { _, _ in
            _ = activity // keep alive until the query returns
            next()
        }
    }

    /// Runs `trigger`, then calls `done` once the authorization delegate fires. Falls back to
    /// the app becoming active again (prompt dismissed) because "keep While Using" may not
    /// produce a delegate callback.
    private func awaitAuthorization(_ done: @escaping () -> Void, trigger: () -> Void) {
        pendingAuth = done
        activeObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self?.finishPendingAuth() }
        }
        trigger()
    }

    private func finishPendingAuth() {
        if let o = activeObserver {
            NotificationCenter.default.removeObserver(o)
            activeObserver = nil
        }
        guard let done = pendingAuth else { return }
        pendingAuth = nil
        done()
    }

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if pendingAuth != nil, manager.authorizationStatus != .notDetermined {
            finishPendingAuth()
        }
    }
}
