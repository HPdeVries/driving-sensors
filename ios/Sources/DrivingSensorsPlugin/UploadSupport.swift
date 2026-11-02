import Foundation
import Security

// Native upload (v0.3.0): config + secrets, the testable Uploader and the URLSession transport.

// MARK: - Settings

/// Non-secret upload configuration (the token lives in the Keychain).
struct UploadSettings: Codable, Equatable {
    var baseUrl: String
    var deviceId: String
    var uploadRecordings = true
    var uploadEvents = true
    var wifiOnly = false
    var eventBatchSize = 500

    /// Validates a configureUpload payload; returns the settings and the token.
    static func parse(_ dict: [String: Any]) throws -> (UploadSettings, String) {
        func string(_ key: String) throws -> String {
            guard let s = dict[key] as? String, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw DrivingSensorsError.invalid("\(key) is required")
            }
            return s
        }
        func flag(_ key: String, _ def: Bool) throws -> Bool {
            guard let raw = dict[key], !(raw is NSNull) else { return def }
            guard let n = raw as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else {
                throw DrivingSensorsError.invalid("\(key) must be a boolean")
            }
            return n.boolValue
        }
        var base = try string("endpointBaseUrl").trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        guard let comps = URLComponents(string: base), comps.scheme?.lowercased() == "https",
              let host = comps.host, !host.isEmpty else {
            throw DrivingSensorsError.invalid("endpointBaseUrl must be an https:// URL")
        }
        let deviceId = try string("deviceId")
        let token = try string("token")
        var s = UploadSettings(baseUrl: base, deviceId: deviceId)
        s.uploadRecordings = try flag("uploadRecordings", true)
        s.uploadEvents = try flag("uploadEvents", true)
        s.wifiOnly = try flag("wifiOnly", false)
        if let raw = dict["eventBatchSize"], !(raw is NSNull) {
            guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else {
                throw DrivingSensorsError.invalid("eventBatchSize must be a number")
            }
            s.eventBatchSize = Int(min(max(n.doubleValue, 1), 1000))
        }
        return (s, token)
    }

    func url(_ path: String) -> URL? { URL(string: baseUrl + "/" + path) }

    /// Common headers; the token is only ever placed in the Authorization header.
    func headers(token: String) -> [String: String] {
        ["Authorization": "Bearer " + token, "X-Device-Id": deviceId,
         "X-Plugin-Version": DrivingSensorsVersion.current, "X-Platform": "ios",
         "Content-Type": "application/json; charset=utf-8"]
    }
}

/// Counters and state, persisted so they survive restarts.
struct UploadRuntime: Codable, Equatable {
    var state = "idle"
    var uploadedChunks = 0
    var uploadedEvents = 0
    var lastSuccessAt: Int64?
    var lastErrorAt: Int64?
    var lastError: String?
    var lastHttpStatus: Int?
    var nextAttemptAt: Int64?
    var backoffSeconds = 0
}

struct HeartbeatState: Codable, Equatable {
    var running = false
    var intervalSec = 30
    var seq = 0
    var sent = 0
    var succeeded = 0
    var lastSentAt: Int64?
    var lastSuccessAt: Int64?
    var lastError: String?
    var lastHttpStatus: Int?
}

// MARK: - Token storage

protocol TokenStoring: AnyObject {
    func save(token: String, account: String) -> Bool
    func load(account: String) -> String?
    func deleteAll()
}

/// Keychain generic password, readable in the background after the first unlock.
final class KeychainTokenStore: TokenStoring {
    static let service = "nl.flexlogic.drivingsensors.upload"

    private func base(_ account: String? = nil) -> [String: Any] {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: KeychainTokenStore.service]
        if let account { q[kSecAttrAccount as String] = account }
        return q
    }

    func save(token: String, account: String) -> Bool {
        deleteAll()
        var q = base(account)
        q[kSecValueData as String] = Data(token.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    func load(account: String) -> String? {
        var q = base(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    func deleteAll() { SecItemDelete(base() as CFDictionary) }
}

final class UploadConfigStore: @unchecked Sendable {
    private static let settingsKey = "DrivingSensors.upload.settings"
    private static let runtimeKey = "DrivingSensors.upload.runtime"
    private static let heartbeatKey = "DrivingSensors.heartbeat.state"
    private let defaults: UserDefaults
    private let tokens: TokenStoring
    private let lock = NSLock()

    init(defaults: UserDefaults = .standard, tokens: TokenStoring = KeychainTokenStore()) {
        self.defaults = defaults
        self.tokens = tokens
    }

    private func decode<T: Decodable>(_ key: String) -> T? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }

    private func encode<T: Encodable>(_ v: T, _ key: String) {
        if let d = try? JSONEncoder().encode(v) { defaults.set(d, forKey: key) }
    }

    func loadSettings() -> UploadSettings? {
        lock.lock(); defer { lock.unlock() }
        return decode(UploadConfigStore.settingsKey)
    }

    /// Never logged or returned to JS.
    func loadToken() -> String? {
        guard let s = loadSettings() else { return nil }
        return tokens.load(account: s.deviceId)
    }

    /// Replaces any previous configuration and resets counters and state (clears auth_failed).
    func configure(_ s: UploadSettings, token: String) throws {
        lock.lock(); defer { lock.unlock() }
        guard tokens.save(token: token, account: s.deviceId) else {
            throw DrivingSensorsError(message: "Could not store the token in the Keychain", code: "INTERNAL")
        }
        encode(s, UploadConfigStore.settingsKey)
        encode(UploadRuntime(), UploadConfigStore.runtimeKey)
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        tokens.deleteAll()
        defaults.removeObject(forKey: UploadConfigStore.settingsKey)
        defaults.removeObject(forKey: UploadConfigStore.runtimeKey)
    }

    func loadRuntime() -> UploadRuntime {
        lock.lock(); defer { lock.unlock() }
        return decode(UploadConfigStore.runtimeKey) ?? UploadRuntime()
    }

    /// Ignored when no configuration exists (a run finishing after clearUpload must not resurrect state).
    func saveRuntime(_ r: UploadRuntime) {
        lock.lock(); defer { lock.unlock() }
        guard defaults.data(forKey: UploadConfigStore.settingsKey) != nil else { return }
        encode(r, UploadConfigStore.runtimeKey)
    }

    func loadHeartbeat() -> HeartbeatState {
        lock.lock(); defer { lock.unlock() }
        return decode(UploadConfigStore.heartbeatKey) ?? HeartbeatState()
    }

    func saveHeartbeat(_ h: HeartbeatState) {
        lock.lock(); defer { lock.unlock() }
        encode(h, UploadConfigStore.heartbeatKey)
    }
}

// MARK: - Transport

enum HTTPOutcome: Equatable {
    case response(Int)
    case failure(String)
}

protocol HTTPTransport: AnyObject {
    /// Blocking; never call on the main thread.
    func post(url: URL, headers: [String: String], body: Data) -> HTTPOutcome
    func finish()
}

final class URLSessionTransport: NSObject, HTTPTransport, URLSessionTaskDelegate, @unchecked Sendable {
    private var session: URLSession!

    init(wifiOnly: Bool, waitsForConnectivity: Bool = true) {
        super.init()
        let c = URLSessionConfiguration.default
        c.waitsForConnectivity = waitsForConnectivity
        c.allowsExpensiveNetworkAccess = !wifiOnly
        c.allowsConstrainedNetworkAccess = !wifiOnly
        c.timeoutIntervalForRequest = 60
        c.timeoutIntervalForResource = 300
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.urlCache = nil
        c.httpCookieStorage = nil
        session = URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }

    func post(url: URL, headers: [String: String], body: Data) -> HTTPOutcome {
        guard url.scheme?.lowercased() == "https" else { return .failure("endpoint is not https") }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpShouldHandleCookies = false
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        let sem = DispatchSemaphore(value: 0)
        let box = OutcomeBox()
        session.uploadTask(with: req, from: body) { _, response, error in
            if let http = response as? HTTPURLResponse {
                box.value = .response(http.statusCode)
            } else {
                box.value = .failure((error as NSError?).map { "\($0.domain) \($0.code)" } ?? "no response")
            }
            sem.signal()
        }.resume()
        sem.wait()
        return box.value
    }

    func finish() { session.finishTasksAndInvalidate() }

    /// Never follow a redirect to a non-https URL; never send the token to another host.
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard request.url?.scheme?.lowercased() == "https" else { return completionHandler(nil) }
        var r = request
        if request.url?.host != task.originalRequest?.url?.host { r.setValue(nil, forHTTPHeaderField: "Authorization") }
        completionHandler(r)
    }

    private final class OutcomeBox: @unchecked Sendable { var value: HTTPOutcome = .failure("no response") }
}

// MARK: - Uploader

protocol UploadDataSource: AnyObject {
    /// Oldest events first, ascending id (same objects as getBufferedEvents).
    func readEvents(limit: Int) -> [[String: Any]]
    func ackEvents(upTo id: Int64)
    /// Finished, non-rejected chunk ids, oldest first.
    func listChunkIds(limit: Int) -> [String]
    /// The RecordingChunk object (incl. base64 data), or nil when unreadable.
    func chunk(id: String) -> [String: Any]?
    func ackChunk(id: String)
    func rejectChunk(id: String)
}

/// One upload run at a time; call from a single serial queue.
final class Uploader {
    static let initialBackoffSeconds = 30
    static let maxBackoffSeconds = 1800

    private let config: UploadConfigStore
    private let source: UploadDataSource
    private let makeTransport: (UploadSettings) -> HTTPTransport
    private let now: () -> Int64

    init(config: UploadConfigStore, source: UploadDataSource,
         makeTransport: @escaping (UploadSettings) -> HTTPTransport,
         now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.config = config
        self.source = source
        self.makeTransport = makeTransport
        self.now = now
    }

    private enum Step {
        case ok(Int)
        case auth(Int)
        case rejected(Int)
        case retry(String, Int?)
    }

    static func eventId(_ e: [String: Any]) -> Int64? { (e["id"] as? NSNumber)?.int64Value }

    func run(force: Bool) {
        guard let settings = config.loadSettings(), let token = config.loadToken() else { return }
        var st = config.loadRuntime()
        if st.state == "auth_failed" { return }
        if !force, let next = st.nextAttemptAt, next > now() { return }
        st.state = "uploading"
        st.nextAttemptAt = nil
        config.saveRuntime(st)
        let transport = makeTransport(settings)
        defer { transport.finish() }
        let headers = settings.headers(token: token)

        func recordError(_ msg: String, _ status: Int?) {
            st.lastError = msg
            st.lastErrorAt = now()
            if let status { st.lastHttpStatus = status }
        }
        func backoff(_ msg: String, _ status: Int?) {
            recordError(msg, status)
            st.backoffSeconds = st.backoffSeconds == 0 ? Uploader.initialBackoffSeconds
                : min(st.backoffSeconds * 2, Uploader.maxBackoffSeconds)
            st.nextAttemptAt = now() + Int64(st.backoffSeconds) * 1000
            st.state = "backoff"
            config.saveRuntime(st)
        }
        func authFailed(_ status: Int) {
            recordError("auth_failed", status)
            st.state = "auth_failed"
            config.saveRuntime(st)
        }
        func succeeded(_ status: Int) {
            st.lastSuccessAt = now()
            st.lastHttpStatus = status
            st.backoffSeconds = 0
            st.nextAttemptAt = nil
        }
        func send(_ path: String, _ json: [String: Any]) -> Step {
            guard let url = settings.url(path), let body = try? JSONSerialization.data(withJSONObject: json) else {
                return .retry("could not build request", nil)
            }
            switch transport.post(url: url, headers: headers, body: body) {
            case .failure(let m): return .retry(m, nil)
            case .response(let c):
                switch c {
                case 200..<300, 409: return .ok(c)
                case 401, 403: return .auth(c)
                case 400, 413, 422: return .rejected(c)
                default: return .retry("HTTP \(c)", c)
                }
            }
        }

        if settings.uploadEvents {
            var limit = settings.eventBatchSize
            while true {
                let events = source.readEvents(limit: limit)
                guard let last = events.last, let maxId = Uploader.eventId(last) else { break }
                let json: [String: Any] = ["deviceId": settings.deviceId, "platform": "ios",
                                           "pluginVersion": DrivingSensorsVersion.current, "events": events]
                switch send("events", json) {
                case .ok(let c):
                    source.ackEvents(upTo: maxId)
                    st.uploadedEvents += events.count
                    succeeded(c)
                    config.saveRuntime(st)
                case .auth(let c): return authFailed(c)
                case .rejected(let c):
                    if events.count > 1 {
                        limit = max(1, events.count / 2)
                    } else {
                        return backoff("event rejected by server (HTTP \(c))", c)
                    }
                case .retry(let m, let c): return backoff(m, c)
                }
            }
        }

        if settings.uploadRecordings {
            var skipped = Set<String>()
            while true {
                let ids = source.listChunkIds(limit: 20 + skipped.count).filter { !skipped.contains($0) }
                if ids.isEmpty { break }
                for id in ids {
                    guard var json = source.chunk(id: id) else {
                        source.rejectChunk(id: id)
                        skipped.insert(id)
                        continue
                    }
                    json["deviceId"] = settings.deviceId
                    switch send("chunks", json) {
                    case .ok(let c):
                        source.ackChunk(id: id)
                        st.uploadedChunks += 1
                        succeeded(c)
                        config.saveRuntime(st)
                    case .auth(let c): return authFailed(c)
                    case .rejected(let c):
                        source.rejectChunk(id: id)
                        skipped.insert(id)
                        recordError("chunk \(id) rejected (HTTP \(c))", c)
                        config.saveRuntime(st)
                    case .retry(let m, let c): return backoff(m, c)
                    }
                }
            }
        }
        st.state = "idle"
        config.saveRuntime(st)
    }
}
