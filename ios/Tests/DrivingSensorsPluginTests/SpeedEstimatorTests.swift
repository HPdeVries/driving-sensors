import XCTest
@testable import DrivingSensorsPlugin

final class SpeedEstimatorTests: XCTestCase {
    func testPlatformSpeedWins() {
        var e = SpeedEstimator()
        XCTAssertEqual(e.speed(ts: 0, lat: 0, lon: 0, platformSpeed: 7), 7)
        XCTAssertEqual(e.speed(ts: 1000, lat: 1, lon: 1, platformSpeed: 0), 0)
    }

    func testNoPreviousFixAndInvalidSpeedIsNil() {
        var e = SpeedEstimator()
        XCTAssertNil(e.speed(ts: 0, lat: 0, lon: 0, platformSpeed: -1))
    }

    func testHaversineFallback() throws {
        var e = SpeedEstimator()
        _ = e.speed(ts: 0, lat: 52.0, lon: 5.0, platformSpeed: -1)
        // 0.001 deg latitude is ~111.2 m; over 2 s that is ~55.6 m/s.
        let v = try XCTUnwrap(e.speed(ts: 2000, lat: 52.001, lon: 5.0, platformSpeed: -1))
        XCTAssertEqual(v, 55.6, accuracy: 0.3)
    }

    func testGapLimits() {
        func speed(afterGapMs gap: Int64) -> Double? {
            var e = SpeedEstimator()
            _ = e.speed(ts: 0, lat: 0, lon: 0, platformSpeed: -1)
            return e.speed(ts: gap, lat: 0.0001, lon: 0, platformSpeed: -1)
        }
        XCTAssertNil(speed(afterGapMs: 499))
        XCTAssertNotNil(speed(afterGapMs: 500))
        XCTAssertNotNil(speed(afterGapMs: 10_000))
        XCTAssertNil(speed(afterGapMs: 10_001))
    }

    func testPreviousFixUpdatedEvenWhenPlatformSpeedUsed() throws {
        var e = SpeedEstimator()
        _ = e.speed(ts: 0, lat: 0, lon: 0, platformSpeed: 5)
        _ = e.speed(ts: 1000, lat: 0.001, lon: 0, platformSpeed: 5)
        let v = try XCTUnwrap(e.speed(ts: 2000, lat: 0.002, lon: 0, platformSpeed: -1))
        XCTAssertEqual(v, 111.2, accuracy: 0.5)
    }
}
