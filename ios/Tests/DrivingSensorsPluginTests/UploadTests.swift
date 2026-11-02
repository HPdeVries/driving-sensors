import XCTest
@testable import DrivingSensorsPlugin

final class FakeTokens: TokenStoring {
    var tokens: [String: String] = [:]
    func save(token: String, account: String) -> Bool { tokens = [account: token]; return true }
    func load(account: String) -> String? { tokens[account] }
    func deleteAll() { tokens = [:] }
}

final class FakeTransport: HTTPTransport {
    struct Request { var url: URL; var headers: [String: String]; var json: [String: Any] }
    var requests: [Request] = []
    var handler: (Request) -> HTTPOutcome = { _ in .response(200) }
    func post(url: URL, headers: [String: String], body: Data) -> HTTPOutcome {
        let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] ?? [:]
        let r = Request(url: url, headers: headers, json: json)
        requests.append(r)
        return handler(r)
    }
    func finish() {}
    var paths: [String] { requests.map { $0.url.lastPathComponent } }
}

final class FakeSource: UploadDataSource {
    var events: [[String: Any]] = []
    var chunks: [String] = []
    var acked: [Int64] = []
    var ackedChunks: [String] = []
    var rejected: [String] = []

    func readEvents(limit: Int) -> [[String: Any]] { Array(events.prefix(limit)) }
    func ackEvents(upTo id: Int64) {
        acked.append(id)
        events.removeAll { (Uploader.eventId($0) ?? 0) <= id }
    }
    func listChunkIds(limit: Int) -> [String] { Array(chunks.prefix(limit)) }
    func chunk(id: String) -> [String: Any]? {
        ["chunkId": id, "format": "csv-v1", "encoding": "gzip+base64", "platform": "ios", "data": "H4sIAAAA",
         "sensorInfo": ["acc": "x"]]
    }
    func ackChunk(id: String) { ackedChunks.append(id); chunks.removeAll { $0 == id } }
    func rejectChunk(id: String) { rejected.append(id); chunks.removeAll { $0 == id } }
}

final class UploadTests: XCTestCase {
    var store: UploadConfigStore!
    var source: FakeSource!
    var transport: FakeTransport!
    var tokens: FakeTokens!
    var clock: Int64 = 1_000_000
    var uploader: Uploader!
    private var suite = ""

    override func setUp() {
        suite = "ds-test-\(UUID().uuidString)"
        tokens = FakeTokens()
        store = UploadConfigStore(defaults: UserDefaults(suiteName: suite)!, tokens: tokens)
        source = FakeSource()
        transport = FakeTransport()
        clock = 1_000_000
        uploader = Uploader(config: store, source: source, makeTransport: { _ in self.transport }, now: { self.clock })
    }

    override func tearDown() { UserDefaults.standard.removePersistentDomain(forName: suite) }

    private func configure(_ extra: [String: Any] = [:]) throws {
        var d: [String: Any] = ["endpointBaseUrl": "https://example.com/rest/T/", "deviceId": "dev-1", "token": "sekret"]
        d.merge(extra) { $1 }
        let (s, t) = try UploadSettings.parse(d)
        try store.configure(s, token: t)
    }

    private func ev(_ id: Int64) -> [String: Any] { ["id": id, "ts": id * 10, "type": "LOCATION", "sessionId": "s"] }

    // MARK: validation

    func testValidation() throws {
        func parse(_ d: [String: Any]) throws -> UploadSettings { try UploadSettings.parse(d).0 }
        let ok: [String: Any] = ["endpointBaseUrl": "https://a.b/c//", "deviceId": "d", "token": "t"]
        XCTAssertEqual(try parse(ok).baseUrl, "https://a.b/c")
        XCTAssertEqual(try parse(ok).eventBatchSize, 500)
        XCTAssertTrue(try parse(ok).uploadEvents)
        XCTAssertThrowsError(try parse(ok.merging(["endpointBaseUrl": "http://a.b"]) { $1 })) {
            XCTAssertEqual(($0 as? DrivingSensorsError)?.code, "INVALID_ARGUMENT")
        }
        XCTAssertThrowsError(try parse(ok.merging(["deviceId": ""]) { $1 }))
        XCTAssertThrowsError(try parse(ok.merging(["token": " "]) { $1 }))
        XCTAssertThrowsError(try parse(ok.merging(["wifiOnly": "yes"]) { $1 }))
        XCTAssertEqual(try parse(ok.merging(["eventBatchSize": 5000]) { $1 }).eventBatchSize, 1000)
        XCTAssertEqual(try parse(ok.merging(["eventBatchSize": 0]) { $1 }).eventBatchSize, 1)
    }

    // MARK: runs

    func testSuccessDeletesChunksAcksEventsAndEventsGoFirst() throws {
        try configure()
        source.events = [ev(1), ev(2), ev(7)]
        source.chunks = ["a-000001", "a-000002"]
        uploader.run(force: false)
        XCTAssertEqual(transport.paths, ["events", "chunks", "chunks"])
        XCTAssertEqual(source.acked, [7])
        XCTAssertEqual(source.ackedChunks, ["a-000001", "a-000002"])
        let rt = store.loadRuntime()
        XCTAssertEqual(rt.state, "idle")
        XCTAssertEqual(rt.uploadedEvents, 3)
        XCTAssertEqual(rt.uploadedChunks, 2)
        XCTAssertNotNil(rt.lastSuccessAt)
    }

    func testRequestsContainExpectedBodiesAndHeaders() throws {
        try configure()
        source.events = [ev(1)]
        source.chunks = ["a-000001"]
        uploader.run(force: false)
        let e = transport.requests[0]
        XCTAssertEqual(e.url.absoluteString, "https://example.com/rest/T/events")
        XCTAssertEqual(e.headers["Authorization"], "Bearer sekret")
        XCTAssertEqual(e.headers["X-Device-Id"], "dev-1")
        XCTAssertEqual(e.headers["X-Platform"], "ios")
        XCTAssertEqual(e.headers["X-Plugin-Version"], DrivingSensorsVersion.current)
        XCTAssertEqual(e.headers["Content-Type"], "application/json; charset=utf-8")
        XCTAssertEqual(e.json["deviceId"] as? String, "dev-1")
        XCTAssertEqual(e.json["platform"] as? String, "ios")
        XCTAssertEqual(e.json["pluginVersion"] as? String, DrivingSensorsVersion.current)
        XCTAssertEqual((e.json["events"] as? [[String: Any]])?.count, 1)
        let c = transport.requests[1]
        XCTAssertEqual(c.url.absoluteString, "https://example.com/rest/T/chunks")
        XCTAssertEqual(c.json["deviceId"] as? String, "dev-1")
        XCTAssertEqual(c.json["chunkId"] as? String, "a-000001")
        XCTAssertEqual(c.json["data"] as? String, "H4sIAAAA")
        XCTAssertNotNil(c.json["sensorInfo"])
    }

    func testDuplicate409IsSuccess() throws {
        try configure()
        source.chunks = ["a-000001"]
        transport.handler = { _ in .response(409) }
        uploader.run(force: false)
        XCTAssertEqual(source.ackedChunks, ["a-000001"])
        XCTAssertEqual(store.loadRuntime().state, "idle")
    }

    func testAuthFailureStopsEverything() throws {
        try configure()
        source.events = [ev(1)]
        source.chunks = ["a-000001", "a-000002"]
        transport.handler = { _ in .response(401) }
        uploader.run(force: true)
        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertEqual(store.loadRuntime().state, "auth_failed")
        XCTAssertTrue(source.acked.isEmpty)
        uploader.run(force: true)
        XCTAssertEqual(transport.requests.count, 1)
        // configureUpload clears it
        try configure()
        XCTAssertEqual(store.loadRuntime().state, "idle")
        uploader.run(force: false)
        XCTAssertGreaterThan(transport.requests.count, 1)
    }

    func testRejectedChunkIsSkippedAndNextStillSent() throws {
        try configure()
        source.chunks = ["a-000001", "a-000002"]
        transport.handler = { $0.json["chunkId"] as? String == "a-000001" ? .response(413) : .response(200) }
        uploader.run(force: false)
        XCTAssertEqual(source.rejected, ["a-000001"])
        XCTAssertEqual(source.ackedChunks, ["a-000002"])
        XCTAssertEqual(store.loadRuntime().state, "idle")
        XCTAssertEqual(store.loadRuntime().uploadedChunks, 1)
    }

    func testBackoffDoublesCapsAndResets() throws {
        try configure()
        source.chunks = ["a-000001"]
        transport.handler = { _ in .response(500) }
        var expected = 30
        for _ in 0..<9 {
            uploader.run(force: true)
            let rt = store.loadRuntime()
            XCTAssertEqual(rt.state, "backoff")
            XCTAssertEqual(rt.backoffSeconds, expected)
            XCTAssertEqual(rt.nextAttemptAt, clock + Int64(expected) * 1000)
            expected = min(expected * 2, 1800)
        }
        XCTAssertEqual(store.loadRuntime().backoffSeconds, 1800)
        // not forced: respects nextAttemptAt
        let n = transport.requests.count
        uploader.run(force: false)
        XCTAssertEqual(transport.requests.count, n)
        // after the delay it tries again, succeeds, and backoff resets
        clock += 1_800_001
        transport.handler = { _ in .response(200) }
        uploader.run(force: false)
        let rt = store.loadRuntime()
        XCTAssertEqual(rt.state, "idle")
        XCTAssertEqual(rt.backoffSeconds, 0)
        XCTAssertNil(rt.nextAttemptAt)
        // next failure starts at 30 again
        source.chunks = ["a-000002"]
        transport.handler = { _ in .failure("timeout") }
        uploader.run(force: true)
        XCTAssertEqual(store.loadRuntime().backoffSeconds, 30)
    }

    func testEventsRejectedHalvesBatch() throws {
        try configure(["eventBatchSize": 8])
        source.events = (1...8).map { ev(Int64($0)) }
        transport.handler = { r in
            (r.json["events"] as? [Any])?.count ?? 0 > 2 ? .response(400) : .response(200)
        }
        uploader.run(force: false)
        let sizes = transport.requests.map { ($0.json["events"] as? [Any])?.count ?? 0 }
        XCTAssertEqual(Array(sizes.prefix(3)), [8, 4, 2])
        XCTAssertTrue(source.events.isEmpty)
        XCTAssertEqual(store.loadRuntime().state, "idle")
    }

    func testSingleRejectedEventGoesToBackoffWithoutDropping() throws {
        try configure()
        source.events = [ev(1)]
        transport.handler = { _ in .response(422) }
        uploader.run(force: false)
        XCTAssertEqual(store.loadRuntime().state, "backoff")
        XCTAssertEqual(source.events.count, 1)
        XCTAssertNotNil(store.loadRuntime().lastError)
    }

    func testFlagsDisableEventsOrChunks() throws {
        try configure(["uploadEvents": false])
        source.events = [ev(1)]
        source.chunks = ["a-000001"]
        uploader.run(force: false)
        XCTAssertEqual(transport.paths, ["chunks"])
    }

    func testClearRemovesTokenAndConfig() throws {
        try configure()
        store.clear()
        XCTAssertNil(store.loadSettings())
        XCTAssertNil(store.loadToken())
        XCTAssertTrue(tokens.tokens.isEmpty)
        uploader.run(force: true)
        XCTAssertTrue(transport.requests.isEmpty)
    }

    // MARK: chunk files

    func testRejectedChunkFiles() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ds-rej-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let csv = RecordingCSV.header + RecordingCSV.line(RecordingSample(t: 5, sensor: .acc, x: 1))
        let part = dir.appendingPathComponent("rec-000001.csv.part")
        try Data(csv.utf8).write(to: part)
        XCTAssertNotNil(try RecordingFiles.finalizePart(part, sensorInfo: nil))
        XCTAssertEqual(RecordingFiles.listChunks(in: dir, limit: 10).count, 1)
        RecordingFiles.rejectChunk("rec-000001", in: dir)
        XCTAssertEqual(RecordingFiles.listChunks(in: dir, limit: 10).count, 0)
        XCTAssertEqual(RecordingFiles.rejectedCount(in: dir), 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.appendingPathComponent("rec-000001.csv.gz").path))
        XCTAssertGreaterThan(RecordingFiles.totalBytes(in: dir), 0)
        XCTAssertTrue(RecordingFiles.finalizeLeftovers(in: dir).isEmpty)
    }
}

final class HeartbeatTests: XCTestCase {
    var store: UploadConfigStore!
    var transport: FakeTransport!
    var sender: HeartbeatSender!
    private var suite = ""

    override func setUpWithError() throws {
        suite = "ds-test-\(UUID().uuidString)"
        store = UploadConfigStore(defaults: UserDefaults(suiteName: suite)!, tokens: FakeTokens())
        transport = FakeTransport()
        sender = HeartbeatSender(config: store, makeTransport: { self.transport }, snapshot: {
            HeartbeatSnapshot(processUptimeSec: 42, appState: "background", batteryLevel: 81, charging: true,
                              network: "wifi", tracking: true, inTrip: false, recording: false,
                              pendingEvents: 3, pendingChunks: 2)
        }, now: { 5_000 })
        let (s, t) = try UploadSettings.parse(["endpointBaseUrl": "https://example.com/r", "deviceId": "dev", "token": "tok"])
        try store.configure(s, token: t)
    }

    override func tearDown() { UserDefaults.standard.removePersistentDomain(forName: suite) }

    private func startState(seq: Int = 0) {
        store.saveHeartbeat(HeartbeatState(running: true, intervalSec: 30, seq: seq))
    }

    func testPayloadHasAllKeys() {
        let (s, _) = try! UploadSettings.parse(["endpointBaseUrl": "https://e.com", "deviceId": "dev", "token": "t"])
        let p = HeartbeatSender.payload(settings: s, state: HeartbeatState(running: true, intervalSec: 30, seq: 4),
                                        snap: HeartbeatSnapshot(), uploadState: "idle", ts: 99)
        let keys = ["deviceId", "platform", "pluginVersion", "ts", "seq", "intervalSec", "processUptimeSec", "appState",
                    "batteryLevel", "charging", "network", "tracking", "inTrip", "recording", "pendingEvents",
                    "pendingChunks", "uploadState"]
        XCTAssertEqual(Set(p.keys), Set(keys))
        XCTAssertEqual(p["platform"] as? String, "ios")
        XCTAssertEqual(p["seq"] as? Int, 4)
    }

    func testBeatSendsPayloadWithHeadersAndCounts() {
        startState(seq: 6)
        XCTAssertTrue(sender.claim())
        sender.beat()
        let r = transport.requests[0]
        XCTAssertEqual(r.url.absoluteString, "https://example.com/r/heartbeat")
        XCTAssertEqual(r.headers["Authorization"], "Bearer tok")
        XCTAssertEqual(r.headers["X-Device-Id"], "dev")
        XCTAssertEqual(r.json["seq"] as? Int, 7)
        XCTAssertEqual(r.json["processUptimeSec"] as? Int, 42)
        XCTAssertEqual(r.json["uploadState"] as? String, "idle")
        let st = store.loadHeartbeat()
        XCTAssertEqual(st.sent, 1)
        XCTAssertEqual(st.succeeded, 1)
        XCTAssertEqual(st.seq, 7)
        XCTAssertEqual(sender.statusDictionary()["intervalSec"] as? Int, 30)
    }

    func testFailedBeatIsCountedNotQueued() {
        startState()
        transport.handler = { _ in .response(500) }
        XCTAssertTrue(sender.claim()); sender.beat()
        transport.handler = { _ in .response(200) }
        XCTAssertTrue(sender.claim()); sender.beat()
        XCTAssertEqual(transport.requests.count, 2)
        let st = store.loadHeartbeat()
        XCTAssertEqual(st.sent, 2)
        XCTAssertEqual(st.succeeded, 1)
    }

    func testBusyBeatIsDropped() {
        XCTAssertTrue(sender.claim())
        XCTAssertFalse(sender.claim())
    }

    func test401SetsAuthFailedAndLaterBeatsAreSkipped() {
        startState()
        transport.handler = { _ in .response(401) }
        XCTAssertTrue(sender.claim()); sender.beat()
        XCTAssertEqual(store.loadRuntime().state, "auth_failed")
        XCTAssertEqual(store.loadHeartbeat().lastHttpStatus, 401)
        XCTAssertTrue(sender.claim()); sender.beat()
        XCTAssertEqual(transport.requests.count, 1)
        let st = store.loadHeartbeat()
        XCTAssertEqual(st.sent, 2)
        XCTAssertEqual(st.lastError, "auth_failed")
        XCTAssertTrue(st.running)
    }

    func testIntervalClamp() {
        XCTAssertEqual(HeartbeatScheduler.clamp(1), 10)
        XCTAssertEqual(HeartbeatScheduler.clamp(30), 30)
        XCTAssertEqual(HeartbeatScheduler.clamp(99999), 3600)
    }
}

final class LocationConsumersTests: XCTestCase {
    func testPlan() {
        XCTAssertEqual(LocationConsumers([]).plan, .off)
        XCTAssertEqual(LocationConsumers([.heartbeat]).plan, .keepAlive)
        XCTAssertEqual(LocationConsumers([.tracking]).plan, .full)
        XCTAssertEqual(LocationConsumers([.recording]).plan, .full)
        XCTAssertEqual(LocationConsumers([.tracking, .heartbeat]).plan, .full)
    }

    func testStoppingOneNeverStopsLocationAnotherNeeds() {
        var c: LocationConsumers = [.tracking, .recording, .heartbeat]
        c.remove(.tracking)
        XCTAssertEqual(c.plan, .full)
        c.remove(.recording)
        XCTAssertEqual(c.plan, .keepAlive)
        c.remove(.heartbeat)
        XCTAssertEqual(c.plan, .off)
        var d: LocationConsumers = [.heartbeat, .recording]
        d.remove(.heartbeat)
        XCTAssertEqual(d.plan, .full)
    }
}
