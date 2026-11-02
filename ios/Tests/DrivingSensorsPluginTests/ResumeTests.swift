import XCTest
import CoreLocation
@testable import DrivingSensorsPlugin

final class ResumeTests: XCTestCase {
    func testSignificantChangeDecisionTable() {
        let all: [LocationConsumers] = [[], .tracking, .recording, .heartbeat, [.tracking, .recording, .heartbeat]]
        let auths: [(CLAuthorizationStatus, Bool)] = [
            (.authorizedAlways, true), (.authorizedWhenInUse, false), (.denied, false),
            (.restricted, false), (.notDetermined, false)
        ]
        for c in all {
            for (a, authOk) in auths {
                XCTAssertEqual(SignificantChangePolicy.shouldMonitor(persisted: c, authorization: a),
                               authOk && !c.isEmpty, "consumers \(c.rawValue) auth \(a.rawValue)")
            }
        }
    }

    private func freshDefaults() -> UserDefaults {
        UserDefaults(suiteName: "ResumeTests.\(UUID().uuidString)")!
    }

    func testPersistedConsumersReadsStorageOnly() {
        let d = freshDefaults()
        XCTAssertEqual(PersistedConsumers.load(defaults: d, heartbeatRunning: false), [])
        d.set(Data(#"{"active":true,"sessionId":"s","options":{}}"#.utf8), forKey: PersistedConsumers.trackingKey)
        d.set(Data("{}".utf8), forKey: PersistedConsumers.recordingKey)
        XCTAssertEqual(PersistedConsumers.load(defaults: d, heartbeatRunning: true), [.tracking, .recording, .heartbeat])
        d.set(Data(#"{"active":false,"sessionId":"s","options":{}}"#.utf8), forKey: PersistedConsumers.trackingKey)
        XCTAssertEqual(PersistedConsumers.load(defaults: d, heartbeatRunning: false), [.recording])
    }

    func testPureDecisionsAreIdempotent() {
        let c: LocationConsumers = [.tracking, .heartbeat]
        for _ in 0..<3 {
            XCTAssertEqual(c.plan, .full)
            XCTAssertTrue(SignificantChangePolicy.shouldMonitor(persisted: c, authorization: .authorizedAlways))
        }
    }

    func testHeartbeatResumeTwiceKeepsStateAndCounters() {
        let store = UploadConfigStore(defaults: freshDefaults(), tokens: FakeTokens())
        let sender = HeartbeatSender(config: store, makeTransport: { FakeTransport() }, snapshot: { HeartbeatSnapshot() })
        let hb = HeartbeatScheduler(config: store, sender: sender)
        var st = HeartbeatState()
        st.running = true
        st.intervalSec = 45
        st.sent = 7
        store.saveHeartbeat(st)
        hb.start(intervalSec: 0, resume: true)
        hb.start(intervalSec: 0, resume: true)
        XCTAssertTrue(hb.isRunning)
        XCTAssertEqual(store.loadHeartbeat().intervalSec, 45)
        hb.stop()
    }

    func testStateSurvivesTeardownWithoutStop() {
        let d = freshDefaults()
        do {
            let store = UploadConfigStore(defaults: d, tokens: FakeTokens())
            var st = HeartbeatState()
            st.running = true
            st.intervalSec = 30
            store.saveHeartbeat(st)
            d.set(Data(#"{"active":true,"sessionId":"s","options":{}}"#.utf8), forKey: PersistedConsumers.trackingKey)
            d.set(Data("{}".utf8), forKey: PersistedConsumers.recordingKey)
            let sender = HeartbeatSender(config: store, makeTransport: { FakeTransport() }, snapshot: { HeartbeatSnapshot() })
            _ = HeartbeatScheduler(config: store, sender: sender) // dropped without stop
        }
        // A new runtime sees everything that was persisted.
        let reborn = UploadConfigStore(defaults: d, tokens: FakeTokens())
        let running = reborn.loadHeartbeat().running
        XCTAssertTrue(running)
        XCTAssertEqual(PersistedConsumers.load(defaults: d, heartbeatRunning: running), [.tracking, .recording, .heartbeat])
    }

    func testExplicitStopClearsHeartbeat() {
        let store = UploadConfigStore(defaults: freshDefaults(), tokens: FakeTokens())
        let hb = HeartbeatScheduler(config: store, sender: HeartbeatSender(
            config: store, makeTransport: { FakeTransport() }, snapshot: { HeartbeatSnapshot() }))
        hb.start(intervalSec: 30)
        XCTAssertTrue(hb.isRunning)
        hb.stop()
        XCTAssertFalse(hb.isRunning)
    }
}
