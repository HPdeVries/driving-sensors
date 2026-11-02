import Foundation
import Network
import UIKit

extension Notification.Name {
    /// Posted after a recording chunk has been finalized on disk.
    static let drivingSensorsChunkFinalized = Notification.Name("DrivingSensors.chunkFinalized")
}

/// Scheduling around the Uploader: triggers, connectivity, background task, one run at a time.
/// Triggers never block the caller; all work happens on `queue`.
final class UploadManager: UploadDataSource, @unchecked Sendable {
    let store: UploadConfigStore
    private weak var owner: DrivingSensors?
    private let queue = DispatchQueue(label: "driving-sensors.upload")
    private let lock = NSLock()
    private var queued = false
    private var running = false
    private var retryTimer: DispatchSourceTimer?
    private var periodic: DispatchSourceTimer?
    private let monitor = NWPathMonitor()
    private var path: NWPath?
    private var started = false
    private var uploader: Uploader!

    init(owner: DrivingSensors, store: UploadConfigStore = UploadConfigStore()) {
        self.owner = owner
        self.store = store
        self.uploader = Uploader(config: store, source: self) { URLSessionTransport(wifiOnly: $0.wifiOnly) }
    }

    /// Plugin load. Idempotent.
    func start() {
        lock.lock()
        if started { lock.unlock(); return trigger() }
        started = true
        lock.unlock()
        monitor.pathUpdateHandler = { [weak self] p in
            guard let self else { return }
            self.lock.lock()
            let was = self.path
            self.path = p
            self.lock.unlock()
            if p.status == .satisfied, was?.status != .satisfied || was?.isExpensive != p.isExpensive { self.trigger() }
        }
        monitor.start(queue: DispatchQueue(label: "driving-sensors.path"))
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil) { [weak self] _ in self?.trigger() }
        nc.addObserver(forName: .drivingSensorsChunkFinalized, object: nil, queue: nil) { [weak self] _ in self?.trigger() }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 60, repeating: 60)
        t.setEventHandler { [weak self] in
            if self?.owner?.isCapturing == true { self?.trigger() }
        }
        t.resume()
        periodic = t
        trigger()
    }

    /// Any thread. Coalesces triggers while one run is already queued; `force` ignores backoff.
    func trigger(force: Bool = false) {
        guard store.loadSettings() != nil else { return }
        lock.lock()
        if queued && !force { lock.unlock(); return }
        queued = true
        lock.unlock()
        withBackgroundTask("driving-sensors.upload", queue: queue) { [weak self] in self?.runOnce(force: force) }
    }

    func uploadNow() { trigger(force: true) }

    // MARK: - Network

    var networkKind: String {
        lock.lock(); defer { lock.unlock() }
        guard let p = path, p.status == .satisfied else { return "none" }
        if p.usesInterfaceType(.wifi) { return "wifi" }
        if p.usesInterfaceType(.cellular) { return "cellular" }
        return "other"
    }

    private func networkAllowed(_ s: UploadSettings) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let p = path else { return true }
        if p.status != .satisfied { return false }
        return !s.wifiOnly || (!p.isExpensive && !p.isConstrained)
    }

    // MARK: - Run (queue)

    private func runOnce(force: Bool) {
        lock.lock(); queued = false; lock.unlock()
        guard let s = store.loadSettings() else { return }
        var rt = store.loadRuntime()
        if rt.state == "auth_failed" { return }
        if !networkAllowed(s) {
            if hasPending(s) && rt.state != "waiting_network" {
                rt.state = "waiting_network"
                store.saveRuntime(rt)
            }
            return
        }
        lock.lock(); running = true; lock.unlock()
        uploader.run(force: force)
        lock.lock(); running = false; lock.unlock()
        scheduleRetry()
    }

    private func hasPending(_ s: UploadSettings) -> Bool {
        (s.uploadEvents && pendingEvents() > 0) || (s.uploadRecordings && !listChunkIds(limit: 1).isEmpty)
    }

    private func scheduleRetry() {
        retryTimer?.cancel()
        retryTimer = nil
        let rt = store.loadRuntime()
        guard rt.state == "backoff", let next = rt.nextAttemptAt else { return }
        let delay = max(0, Double(next - Int64(Date().timeIntervalSince1970 * 1000)) / 1000)
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + delay)
        t.setEventHandler { [weak self] in self?.trigger() }
        t.resume()
        retryTimer = t
    }

    // MARK: - Status

    func status() -> [String: Any] {
        guard let s = store.loadSettings() else {
            return ["configured": false, "state": "disabled", "pendingChunks": owner?.recordingChunks(limit: Int.max).count ?? 0,
                    "pendingEvents": pendingEvents(), "rejectedChunks": owner?.rejectedChunkCount() ?? 0,
                    "uploadedChunks": 0, "uploadedEvents": 0]
        }
        let rt = store.loadRuntime()
        lock.lock(); let isRunning = running; lock.unlock()
        // A persisted "uploading" without a live run (process was killed) is idle.
        var state = rt.state
        if state == "uploading" && !isRunning { state = "idle" }
        var out: [String: Any] = [
            "configured": true, "state": state, "endpointBaseUrl": s.baseUrl, "deviceId": s.deviceId,
            "uploadRecordings": s.uploadRecordings, "uploadEvents": s.uploadEvents, "wifiOnly": s.wifiOnly,
            "pendingChunks": owner?.recordingChunks(limit: Int.max).count ?? 0, "pendingEvents": pendingEvents(),
            "rejectedChunks": owner?.rejectedChunkCount() ?? 0,
            "uploadedChunks": rt.uploadedChunks, "uploadedEvents": rt.uploadedEvents]
        if let v = rt.lastSuccessAt { out["lastSuccessAt"] = v }
        if let v = rt.lastErrorAt { out["lastErrorAt"] = v }
        if let v = rt.lastError { out["lastError"] = v }
        if let v = rt.lastHttpStatus { out["lastHttpStatus"] = v }
        if state == "backoff", let v = rt.nextAttemptAt { out["nextAttemptAt"] = v }
        return out
    }

    func pendingEvents() -> Int { owner?.store?.count() ?? 0 }

    // MARK: - UploadDataSource

    func readEvents(limit: Int) -> [[String: Any]] { owner?.bufferedEvents(afterId: 0, limit: limit) ?? [] }
    func ackEvents(upTo id: Int64) { owner?.ackEvents(upToId: id) }
    func listChunkIds(limit: Int) -> [String] {
        (owner?.recordingChunks(limit: limit) ?? []).compactMap { $0["chunkId"] as? String }
    }
    func chunk(id: String) -> [String: Any]? {
        if case .success(let r)? = owner?.recordingChunk(chunkId: id) { return r }
        return nil
    }
    func ackChunk(id: String) { owner?.ackRecordingChunk(chunkId: id) }
    func rejectChunk(id: String) { owner?.rejectRecordingChunk(chunkId: id) }
}
