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
    /// Plugin-started location updates are running (mirrors startUpdatingLocation/stopUpdatingLocation).
    private var locationRunning = false
    /// Tracking's own device-motion updates are running.
    private var motionRunning = false
    private var lastFix: LocationSnapshot?
    private var lastMotion: MotionSample?
    /// Observers of every device-motion sample (tracking or standalone); used by getCurrentReadings.
    private var motionTaps: [UUID: (MotionSample) -> Void] = [:]

    // Main thread only.
    private var standaloneMotionCount = 0
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
        resumeRecordingIfNeeded()
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
                self.locationRunning = false
                self.motionRunning = false
            }
            if self.currentRecorder() == nil {
                self.manager.stopUpdatingLocation()
            } else {
                self.manager.distanceFilter = kCLDistanceFilterNone
            }
            if self.standaloneMotionCount == 0 { self.motionManager.stopDeviceMotionUpdates() }
            self.defaults.removeObject(forKey: DrivingSensors.stateKey)
            completion()
        }
    }

    struct Status {
        var active: Bool
        var serviceRunning: Bool
        var lastLocation: LocationSnapshot?
        var sessionId: String?
        var inTrip: Bool
        var buffered: Int
    }

    func status() -> Status {
        let s = work.sync { (active, locationRunning, lastFix, sessionId, trip.inTrip) }
        return Status(active: s.0, serviceRunning: s.1, lastLocation: s.2, sessionId: s.3, inTrip: s.4,
                      buffered: store?.count() ?? 0)
    }

    private func begin(sessionId: String, options: TrackingOptions) {
        work.sync {
            self.active = true
            self.sessionId = sessionId
            self.options = options
            self.trip = TripDetector(startSpeedMps: options.tripStartSpeedMps, endIdleMs: Int64(options.tripEndIdleMs))
            self.harsh = nil
            self.estimator = SpeedEstimator()
            self.lastLocationTs = nil
            self.locationRunning = true
            let t = DispatchSource.makeTimerSource(queue: self.work)
            t.schedule(deadline: .now() + DrivingSensors.tickInterval, repeating: DrivingSensors.tickInterval)
            t.setEventHandler { [weak self] in self?.onTick() }
            t.resume()
            self.timer?.cancel()
            self.timer = t
        }
        configureLocation(inTrip: false)
        manager.startUpdatingLocation()
    }

    /// Main thread. Shared by tracking and recording; background settings apply to both and
    /// recording's need (no distance filter) wins while it runs.
    private func configureLocation(inTrip: Bool) {
        let m = manager
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        if modes.contains("location") {
            m.allowsBackgroundLocationUpdates = true
            m.showsBackgroundLocationIndicator = true
        } else {
            dsLog("WARNING: UIBackgroundModes does not contain 'location'; background location is disabled")
        }
        m.pausesLocationUpdatesAutomatically = false
        m.activityType = .automotiveNavigation
        m.desiredAccuracy = kCLLocationAccuracyBest
        m.distanceFilter = (currentRecorder() != nil || inTrip) ? kCLDistanceFilterNone : 10
    }

    private func persist(_ state: PersistedState) {
        if let data = try? JSONEncoder().encode(state) { defaults.set(data, forKey: DrivingSensors.stateKey) }
    }

    // MARK: - Trip mode (main thread)

    private func applyTripMode(_ inTrip: Bool) {
        guard work.sync(execute: { active }) else { return }
        manager.distanceFilter = (inTrip || currentRecorder() != nil) ? kCLDistanceFilterNone : 10
        if inTrip {
            startMotion()
        } else {
            work.sync { motionRunning = false }
            if standaloneMotionCount == 0 { motionManager.stopDeviceMotionUpdates() }
        }
    }

    private static func sample(_ m: CMDeviceMotion, bootOffset: TimeInterval) -> MotionSample {
        let a = m.userAcceleration, g = m.gravity
        return MotionSample(ts: Int64((m.timestamp + bootOffset) * 1000),
                            ax: a.x * gToMps2, ay: a.y * gToMps2, az: a.z * gToMps2,
                            gx: g.x * gToMps2, gy: g.y * gToMps2, gz: g.z * gToMps2)
    }

    /// Work queue: fan a sample out to observers.
    private func notifyTaps(_ s: MotionSample) {
        for tap in motionTaps.values { tap(s) }
    }

    private func startMotion() {
        guard motionManager.isDeviceMotionAvailable else {
            dsLog("device motion unavailable; harsh events disabled")
            return
        }
        let hz = work.sync { options.accelSampleHz }
        motionManager.deviceMotionUpdateInterval = 1.0 / hz
        let bootOffset = Date().timeIntervalSince1970 - ProcessInfo.processInfo.systemUptime
        work.sync { motionRunning = true }
        motionManager.startDeviceMotionUpdates(to: motionQueue) { [weak self] motion, _ in
            guard let self, let m = motion else { return }
            let s = DrivingSensors.sample(m, bootOffset: bootOffset)
            self.work.async {
                self.lastMotion = s
                self.harsh?.onAccel(ts: s.ts, ax: s.ax, ay: s.ay, az: s.az, gx: s.gx, gy: s.gy, gz: s.gz)
                self.notifyTaps(s)
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
            lastFix = LocationSnapshot(loc)
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
        currentRecorder()?.onLocation(locations)
        work.async { self.process(locations) }
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        dsLog("location error: \(error.localizedDescription)")
    }

    // MARK: - Readings

    func lastReadings() -> [String: Any] {
        let r = work.sync { (lastFix, lastMotion) }
        return SensorReadings.dictionary(location: r.0, motion: r.1?.snapshot())
    }

    /// One fresh fix (dedicated manager) plus a short motion sample, in parallel. Does not touch
    /// tracking state and never writes to the buffer. All joining happens on the main thread.
    func currentReadings(timeoutMs: Double?, motionSampleMs: Double?,
                         completion: @escaping (Result<[String: Any], DrivingSensorsError>) -> Void) {
        DispatchQueue.main.async {
            guard CLLocationManager.locationServicesEnabled() else {
                return completion(.failure(.init(message: "Location services are disabled", code: "LOCATION_DISABLED")))
            }
            guard self.hasLocationPermission() else {
                return completion(.failure(.init(message: "Location permission not granted", code: "PERMISSION_DENIED")))
            }
            let timeout = SensorReadings.clamp(timeoutMs, default: 10_000, min: 1_000, max: 60_000)
            let window = SensorReadings.clamp(motionSampleMs, default: 1_000, min: 100, max: 5_000)

            var location: LocationSnapshot?
            var motion: MotionSnapshot?
            var pending = 2
            let partDone = {
                pending -= 1
                if pending == 0 {
                    completion(.success(SensorReadings.dictionary(location: location, motion: motion)))
                }
            }

            // (a) location
            let lm = CLLocationManager()
            var delegate: OneShotLocationDelegate?
            delegate = OneShotLocationDelegate { loc in
                lm.delegate = nil
                location = loc.map { LocationSnapshot($0) }
                partDone()
            }
            lm.delegate = delegate
            lm.desiredAccuracy = kCLLocationAccuracyBest
            lm.requestLocation()
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout / 1000) {
                if let d = delegate {
                    lm.stopUpdatingLocation()
                    d.finish(nil)
                }
                delegate = nil
            }

            // (b) motion
            guard self.motionManager.isDeviceMotionAvailable else { return partDone() }
            final class Box { var window = MotionWindow() }
            let box = Box()
            let tapId = UUID()
            let trackingMotion = self.work.sync { () -> Bool in
                self.motionTaps[tapId] = { box.window.add($0) }
                return self.motionRunning
            }
            self.standaloneMotionCount += 1
            if !trackingMotion && self.standaloneMotionCount == 1 {
                self.motionManager.deviceMotionUpdateInterval = 1.0 / 50
                let bootOffset = Date().timeIntervalSince1970 - ProcessInfo.processInfo.systemUptime
                self.motionManager.startDeviceMotionUpdates(to: self.motionQueue) { [weak self] m, _ in
                    guard let self, let m else { return }
                    let s = DrivingSensors.sample(m, bootOffset: bootOffset)
                    self.work.async { self.notifyTaps(s) }
                }
            }
            self.work.asyncAfter(deadline: .now() + window / 1000) {
                self.motionTaps.removeValue(forKey: tapId)
                let snap = box.window.snapshot
                DispatchQueue.main.async {
                    self.standaloneMotionCount -= 1
                    if self.standaloneMotionCount == 0, !self.work.sync(execute: { self.motionRunning }) {
                        self.motionManager.stopDeviceMotionUpdates()
                    }
                    motion = snap
                    partDone()
                }
            }
        }
    }

    // MARK: - Recording (v0.2.0)

    private struct PersistedRecording: Codable {
        var recordingId: String
        var options: RecordingOptions
        var nextSeq: Int
        var startedAt: Int64
    }

    private static let recordingKey = "DrivingSensors.recording"
    private static let recordingStopReasonKey = "DrivingSensors.recordingStopReason"
    private let recordingLock = NSLock()
    private var recorderRef: SensorRecorder?
    private var recordingDirectory: URL?

    private func currentRecorder() -> SensorRecorder? {
        recordingLock.lock(); defer { recordingLock.unlock() }
        return recorderRef
    }

    private func setRecorder(_ r: SensorRecorder?) {
        recordingLock.lock(); recorderRef = r; recordingLock.unlock()
    }

    private func recordingDir() throws -> URL {
        recordingLock.lock(); defer { recordingLock.unlock() }
        if let d = recordingDirectory { return d }
        let d = try RecordingFiles.defaultDirectory()
        recordingDirectory = d
        return d
    }

    /// Plugin load: finalize leftover parts (before any new writer exists), then resume.
    private func resumeRecordingIfNeeded() {
        DispatchQueue.global(qos: .utility).async {
            guard let dir = try? self.recordingDir() else { return }
            RecordingFiles.finalizeLeftovers(in: dir)
            DispatchQueue.main.async {
                guard let data = self.defaults.data(forKey: DrivingSensors.recordingKey),
                      let st = try? JSONDecoder().decode(PersistedRecording.self, from: data) else { return }
                guard self.currentRecorder() == nil else { return }
                guard self.hasLocationPermission() else {
                    dsLog("persisted recording not resumed: location permission missing")
                    self.defaults.removeObject(forKey: DrivingSensors.recordingKey)
                    self.defaults.set("PERMISSION_LOST", forKey: DrivingSensors.recordingStopReasonKey)
                    return
                }
                self.startRecorder(dir: dir, id: st.recordingId, options: st.options, seq: st.nextSeq, startedAt: st.startedAt)
            }
        }
    }

    func startRecording(options: RecordingOptions, completion: @escaping (Result<String, DrivingSensorsError>) -> Void) {
        DispatchQueue.main.async {
            if let existing = self.currentRecorder() { return completion(.success(existing.recordingId)) }
            guard CLLocationManager.locationServicesEnabled() else {
                return completion(.failure(.init(message: "Location services are disabled", code: "LOCATION_DISABLED")))
            }
            guard self.hasLocationPermission() else {
                return completion(.failure(.init(message: "Location permission not granted", code: "PERMISSION_DENIED")))
            }
            let dir: URL
            do { dir = try self.recordingDir() } catch {
                return completion(.failure(.init(message: "Recording storage unavailable: \(error.localizedDescription)", code: "INTERNAL")))
            }
            let id = UUID().uuidString.lowercased()
            let startedAt = Int64(Date().timeIntervalSince1970 * 1000)
            self.defaults.removeObject(forKey: DrivingSensors.recordingStopReasonKey)
            self.startRecorder(dir: dir, id: id, options: options, seq: 0, startedAt: startedAt)
            completion(.success(id))
        }
    }

    /// Main thread.
    private func startRecorder(dir: URL, id: String, options: RecordingOptions, seq: Int, startedAt: Int64) {
        persistRecording(PersistedRecording(recordingId: id, options: options, nextSeq: seq, startedAt: startedAt))
        let rec = SensorRecorder(
            directory: dir, recordingId: id, options: options, startSeq: seq, startedAt: startedAt,
            motion: motionManager,
            onSeqAdvance: { [weak self] next in
                self?.persistRecording(PersistedRecording(recordingId: id, options: options, nextSeq: next, startedAt: startedAt))
            },
            onStop: { [weak self] reason in
                DispatchQueue.main.async { self?.stopRecordingInternal(reason: reason) {} }
            })
        setRecorder(rec)
        configureLocation(inTrip: work.sync { trip.inTrip })
        manager.startUpdatingLocation()
        rec.start()
    }

    private func persistRecording(_ s: PersistedRecording) {
        if let data = try? JSONEncoder().encode(s) { defaults.set(data, forKey: DrivingSensors.recordingKey) }
    }

    func stopRecording(completion: @escaping () -> Void) {
        DispatchQueue.main.async { self.stopRecordingInternal(reason: nil, completion: completion) }
    }

    /// Main thread. Sensors stop at once; the final chunk is finalized off the main thread.
    private func stopRecordingInternal(reason: String?, completion: @escaping () -> Void) {
        guard let rec = currentRecorder() else { return completion() }
        setRecorder(nil)
        defaults.removeObject(forKey: DrivingSensors.recordingKey)
        if let reason { defaults.set(reason, forKey: DrivingSensors.recordingStopReasonKey) }
        rec.stopSensors()
        if work.sync(execute: { active }) {
            manager.distanceFilter = work.sync { trip.inTrip } ? kCLDistanceFilterNone : 10
        } else {
            manager.stopUpdatingLocation()
        }
        DispatchQueue.global(qos: .utility).async {
            rec.finish()
            completion()
        }
    }

    func recordingStatus() -> [String: Any] {
        let rec = currentRecorder()
        var out: [String: Any] = ["recording": rec != nil]
        let dir = try? recordingDir()
        out["chunksPending"] = dir.map { RecordingFiles.listChunks(in: $0, limit: Int.max).count } ?? 0
        out["bytesPending"] = dir.map { RecordingFiles.totalBytes(in: $0) } ?? 0
        out["available"] = SensorRecorder.availability(motionManager)
        if let rec {
            out["recordingId"] = rec.recordingId
            out["startedAt"] = rec.startedAt
            out["measuredHz"] = rec.measuredHz()
        } else if let reason = defaults.string(forKey: DrivingSensors.recordingStopReasonKey) {
            out["stopReason"] = reason
        }
        return out
    }

    func recordingChunks(limit: Int) -> [[String: Any]] {
        guard let dir = try? recordingDir() else { return [] }
        return RecordingFiles.listChunks(in: dir, limit: limit).map { $0.infoDictionary }
    }

    func recordingChunk(chunkId: String) -> Result<[String: Any], DrivingSensorsError> {
        guard RecordingChunkId.isValid(chunkId), let dir = try? recordingDir(),
              let json = try? Data(contentsOf: dir.appendingPathComponent(chunkId + ".json")),
              let meta = try? JSONDecoder().decode(RecordingChunkMeta.self, from: json),
              let gz = try? Data(contentsOf: dir.appendingPathComponent(chunkId + ".csv.gz")) else {
            return .failure(.invalid("Unknown chunkId"))
        }
        var out = meta.infoDictionary
        out["bytes"] = gz.count
        out["format"] = "csv-v1"
        out["encoding"] = "gzip+base64"
        out["platform"] = "ios"
        out["deviceModel"] = DrivingSensors.deviceModel()
        out["osVersion"] = DrivingSensors.osVersion
        out["pluginVersion"] = DrivingSensorsVersion.current
        out["sensorInfo"] = meta.sensorInfo ?? [:]
        out["data"] = gz.base64EncodedString()
        return .success(out)
    }

    func ackRecordingChunk(chunkId: String) {
        guard RecordingChunkId.isValid(chunkId), let dir = try? recordingDir() else { return }
        try? FileManager.default.removeItem(at: dir.appendingPathComponent(chunkId + ".csv.gz"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent(chunkId + ".json"))
    }

    static func deviceModel() -> String {
        var u = utsname()
        uname(&u)
        return withUnsafePointer(to: &u.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: Int(_SYS_NAMELEN)) { String(cString: $0) }
        }
    }

    /// Same value as UIDevice.systemVersion, without touching UIKit off the main thread.
    static var osVersion: String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return v.patchVersion == 0 ? "\(v.majorVersion).\(v.minorVersion)" : "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
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
        if currentRecorder() != nil, !hasLocationPermission() {
            dsLog("recording stopped: location permission lost")
            stopRecordingInternal(reason: "PERMISSION_LOST") {}
        }
        if pendingAuth != nil, manager.authorizationStatus != .notDetermined {
            finishPendingAuth()
        }
    }
}
