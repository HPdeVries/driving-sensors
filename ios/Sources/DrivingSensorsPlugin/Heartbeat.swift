import Foundation
import UIKit

/// Who needs the shared CLLocationManager. Tracking and recording need full-accuracy updates and
/// win over the heartbeat's low-power keep-alive; updates stop only when nobody needs them.
struct LocationConsumers: OptionSet, Equatable {
    let rawValue: Int
    static let tracking = LocationConsumers(rawValue: 1)
    static let recording = LocationConsumers(rawValue: 2)
    static let heartbeat = LocationConsumers(rawValue: 4)

    enum Plan: Equatable {
        case off
        /// ThreeKilometers accuracy, distanceFilter 3000, fixes ignored.
        case keepAlive
        /// Tracking/recording settings.
        case full
    }

    var plan: Plan {
        if contains(.tracking) || contains(.recording) { return .full }
        return contains(.heartbeat) ? .keepAlive : .off
    }
}

/// Runs a block on `queue` inside a UIApplication background task (begun on the main thread).
func withBackgroundTask(_ name: String, queue: DispatchQueue, _ body: @escaping () -> Void) {
    DispatchQueue.main.async {
        let app = UIApplication.shared
        var id = UIBackgroundTaskIdentifier.invalid
        id = app.beginBackgroundTask(withName: name) {
            app.endBackgroundTask(id)
            id = .invalid
        }
        queue.async {
            body()
            DispatchQueue.main.async {
                if id != .invalid { app.endBackgroundTask(id) }
                id = .invalid
            }
        }
    }
}

/// Values sampled at send time.
struct HeartbeatSnapshot {
    var processUptimeSec = 0
    var appState = "background"
    var batteryLevel = -1
    var charging = false
    var network = "none"
    var tracking = false
    var inTrip = false
    var recording = false
    var pendingEvents = 0
    var pendingChunks = 0
}

/// Builds and sends heartbeats (testable); the timer lives in `HeartbeatScheduler`.
final class HeartbeatSender: @unchecked Sendable {
    private let config: UploadConfigStore
    private let makeTransport: () -> HTTPTransport
    private let snapshot: () -> HeartbeatSnapshot
    private let now: () -> Int64
    private let lock = NSLock()
    private var busy = false

    init(config: UploadConfigStore, makeTransport: @escaping () -> HTTPTransport,
         snapshot: @escaping () -> HeartbeatSnapshot,
         now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.config = config
        self.makeTransport = makeTransport
        self.snapshot = snapshot
        self.now = now
    }

    /// Every key of HeartbeatPayload.
    static func payload(settings: UploadSettings, state: HeartbeatState, snap: HeartbeatSnapshot,
                        uploadState: String, ts: Int64) -> [String: Any] {
        ["deviceId": settings.deviceId, "platform": "ios", "pluginVersion": DrivingSensorsVersion.current,
         "ts": ts, "seq": state.seq, "intervalSec": state.intervalSec,
         "processUptimeSec": snap.processUptimeSec, "appState": snap.appState,
         "batteryLevel": snap.batteryLevel, "charging": snap.charging, "network": snap.network,
         "tracking": snap.tracking, "inTrip": snap.inTrip, "recording": snap.recording,
         "pendingEvents": snap.pendingEvents, "pendingChunks": snap.pendingChunks,
         "uploadState": uploadState]
    }

    /// Heartbeats are not queued: false when the previous send is still in flight.
    func claim() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if busy { return false }
        busy = true
        return true
    }

    /// Blocking. Call after a successful `claim()`.
    func beat() {
        defer { lock.lock(); busy = false; lock.unlock() }
        var st = config.loadHeartbeat()
        guard st.running else { return }
        st.seq += 1
        st.sent += 1
        st.lastSentAt = now()
        func fail(_ msg: String, _ code: Int? = nil) {
            st.lastError = msg
            if let code { st.lastHttpStatus = code }
            config.saveHeartbeat(st)
        }
        guard let settings = config.loadSettings(), let token = config.loadToken() else {
            return fail("upload not configured")
        }
        var runtime = config.loadRuntime()
        if runtime.state == "auth_failed" { return fail("auth_failed") }
        let body = HeartbeatSender.payload(settings: settings, state: st, snap: snapshot(),
                                           uploadState: runtime.state, ts: st.lastSentAt ?? now())
        guard let url = settings.url("heartbeat"), let data = try? JSONSerialization.data(withJSONObject: body) else {
            return fail("could not build request")
        }
        let transport = makeTransport()
        defer { transport.finish() }
        switch transport.post(url: url, headers: settings.headers(token: token), body: data) {
        case .failure(let m): fail(m)
        case .response(let c):
            switch c {
            case 200..<300, 409:
                st.succeeded += 1
                st.lastSuccessAt = now()
                st.lastHttpStatus = c
                st.lastError = nil
                config.saveHeartbeat(st)
            case 401, 403:
                runtime.state = "auth_failed"
                runtime.lastError = "auth_failed"
                runtime.lastErrorAt = now()
                runtime.lastHttpStatus = c
                config.saveRuntime(runtime)
                fail("auth_failed", c)
            default:
                fail("HTTP \(c)", c)
            }
        }
    }

    func statusDictionary() -> [String: Any] {
        let st = config.loadHeartbeat()
        var out: [String: Any] = ["running": st.running, "sent": st.sent, "succeeded": st.succeeded]
        if st.running { out["intervalSec"] = st.intervalSec }
        if let v = st.lastSentAt { out["lastSentAt"] = v }
        if let v = st.lastSuccessAt { out["lastSuccessAt"] = v }
        if let v = st.lastError { out["lastError"] = v }
        if let v = st.lastHttpStatus { out["lastHttpStatus"] = v }
        return out
    }
}

/// Timer around a HeartbeatSender: first beat immediately, then every intervalSec.
final class HeartbeatScheduler: @unchecked Sendable {
    static func clamp(_ sec: Double) -> Int { Int(min(max(sec, 10), 3600)) }

    private let config: UploadConfigStore
    let sender: HeartbeatSender
    private let timerQueue = DispatchQueue(label: "driving-sensors.heartbeat.timer")
    private let sendQueue = DispatchQueue(label: "driving-sensors.heartbeat.send")
    private var timer: DispatchSourceTimer?

    init(config: UploadConfigStore, sender: HeartbeatSender) {
        self.config = config
        self.sender = sender
    }

    var isRunning: Bool { config.loadHeartbeat().running }

    /// Persists and (re)starts the timer. Idempotent: an already running heartbeat keeps its
    /// counters and gets the new interval. `resume` keeps persisted state untouched.
    func start(intervalSec: Int, resume: Bool = false) {
        var st = config.loadHeartbeat()
        if resume {
            guard st.running else { return }
        } else {
            if !st.running { st = HeartbeatState() }
            st.running = true
            st.intervalSec = intervalSec
            config.saveHeartbeat(st)
        }
        let interval = st.intervalSec
        timerQueue.async {
            self.timer?.cancel()
            let t = DispatchSource.makeTimerSource(queue: self.timerQueue)
            t.schedule(deadline: .now(), repeating: .seconds(interval))
            t.setEventHandler { [weak self] in self?.tick() }
            t.resume()
            self.timer = t
        }
    }

    func stop() {
        var st = config.loadHeartbeat()
        st.running = false
        config.saveHeartbeat(st)
        timerQueue.async {
            self.timer?.cancel()
            self.timer = nil
        }
    }

    private func tick() {
        guard sender.claim() else { return }
        withBackgroundTask("driving-sensors.heartbeat", queue: sendQueue) { [sender] in sender.beat() }
    }
}
