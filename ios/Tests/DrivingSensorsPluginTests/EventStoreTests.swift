import XCTest
@testable import DrivingSensorsPlugin

final class EventStoreTests: XCTestCase {
    private var path = ""
    private var store: EventStore!

    override func setUpWithError() throws {
        path = NSTemporaryDirectory() + "ds-test-\(UUID().uuidString).sqlite"
        store = try EventStore(path: path)
    }

    override func tearDown() {
        store = nil
        try? FileManager.default.removeItem(atPath: path)
    }

    private func ev(_ ts: Int64, _ type: String = "LOCATION") -> BufferedEvent {
        BufferedEvent(ts: ts, type: type, sessionId: "s1", lat: 1.5, lon: 2.5, speedMps: 3, accuracyM: 4)
    }

    func testInsertAndRead() throws {
        XCTAssertEqual(store.insert(ev(100)), 1)
        XCTAssertEqual(store.insert(ev(200, "TRIP_END")), 2)
        let rows = store.read()
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0]["id"] as? Int64, 1)
        XCTAssertEqual(rows[0]["ts"] as? Int64, 100)
        XCTAssertEqual(rows[0]["type"] as? String, "LOCATION")
        XCTAssertEqual(rows[0]["sessionId"] as? String, "s1")
        XCTAssertEqual(rows[0]["lat"] as? Double, 1.5)
        XCTAssertEqual(rows[1]["type"] as? String, "TRIP_END")
        XCTAssertEqual(store.count(), 2)
    }

    func testAfterIdAndLimit() {
        for i in 1...5 { store.insert(ev(Int64(i))) }
        XCTAssertEqual(store.read(afterId: 2).compactMap { $0["id"] as? Int64 }, [3, 4, 5])
        XCTAssertEqual(store.read(afterId: 0, limit: 2).compactMap { $0["id"] as? Int64 }, [1, 2])
        XCTAssertEqual(store.read(afterId: 5).count, 0)
    }

    func testLimitCappedAt1000() {
        for i in 1...1005 { store.insert(ev(Int64(i))) }
        XCTAssertEqual(store.read(afterId: 0, limit: 5000).count, 1000)
    }

    func testAckDeletesUpToId() {
        for i in 1...5 { store.insert(ev(Int64(i))) }
        store.ack(upToId: 3)
        XCTAssertEqual(store.read().compactMap { $0["id"] as? Int64 }, [4, 5])
        // Ids keep increasing after deletes.
        XCTAssertEqual(store.insert(ev(6)), 6)
    }

    func testNilFieldsOmitted() {
        store.insert(BufferedEvent(ts: 1, type: "HARSH_BRAKE", sessionId: "s", peakMps2: 4.5))
        let row = store.read()[0]
        XCTAssertEqual(Set(row.keys), ["id", "ts", "type", "sessionId", "peakMps2"])
        XCTAssertEqual(row["peakMps2"] as? Double, 4.5)
    }

    func testPersistsAcrossReopen() throws {
        store.insert(ev(1))
        store = nil
        store = try EventStore(path: path)
        XCTAssertEqual(store.count(), 1)
    }
}
