import XCTest
@testable import DrivingSensorsPlugin

final class TripDetectorTests: XCTestCase {
    private func detector() -> TripDetector {
        TripDetector(startSpeedMps: 4.2, endIdleMs: 180_000)
    }

    private func fast(_ ts: Int64) -> Fix { Fix(ts: ts, lat: Double(ts), lon: 1, speedMps: 10) }

    func testStartsAfterThreeFastFixesUsingTheFirst() {
        var d = detector()
        XCTAssertNil(d.onFix(fast(1000)))
        XCTAssertNil(d.onFix(fast(2000)))
        XCTAssertEqual(d.onFix(fast(3000)), .start(fast(1000)))
        XCTAssertTrue(d.inTrip)
    }

    func testSingleFastFixDoesNotStart() {
        var d = detector()
        XCTAssertNil(d.onFix(fast(1000)))
        XCTAssertNil(d.onFix(Fix(ts: 2000, speedMps: 1)))
        XCTAssertNil(d.onFix(fast(3000)))
        XCTAssertNil(d.onFix(fast(4000)))
        XCTAssertFalse(d.inTrip)
    }

    func testUnknownSpeedIsIgnored() {
        var d = detector()
        XCTAssertNil(d.onFix(fast(1000)))
        XCTAssertNil(d.onFix(Fix(ts: 2000, speedMps: nil)))
        XCTAssertNil(d.onFix(fast(3000)))
        XCTAssertEqual(d.onFix(fast(4000)), .start(fast(1000)))
    }

    func testUnknownSpeedDoesNotExtendTrip() {
        var d = TripDetector(startSpeedMps: 4.2, endIdleMs: 10_000)
        _ = d.onFix(fast(1000)); _ = d.onFix(fast(2000)); _ = d.onFix(fast(3000))
        XCTAssertNil(d.onFix(Fix(ts: 8000, speedMps: nil)))
        XCTAssertEqual(d.onFix(Fix(ts: 13_000, speedMps: nil)), .end(fast(3000)))
    }

    func testEndsAfterIdleOnSlowFixCarryingLastFastFix() {
        var d = TripDetector(startSpeedMps: 4.2, endIdleMs: 60_000)
        _ = d.onFix(fast(1000)); _ = d.onFix(fast(2000)); _ = d.onFix(fast(3000))
        XCTAssertNil(d.onFix(Fix(ts: 62_999, speedMps: 0)))
        XCTAssertEqual(d.onFix(Fix(ts: 63_000, speedMps: 0)), .end(fast(3000)))
        XCTAssertFalse(d.inTrip)
    }

    func testEndsViaTickWithoutFixes() {
        var d = TripDetector(startSpeedMps: 4.2, endIdleMs: 60_000)
        _ = d.onFix(fast(1000)); _ = d.onFix(fast(2000)); _ = d.onFix(fast(3000))
        XCTAssertNil(d.tick(now: 40_000))
        XCTAssertEqual(d.tick(now: 63_000), .end(fast(3000)))
        XCTAssertNil(d.tick(now: 90_000))
    }

    func testFastFixExtendsTrip() {
        var d = TripDetector(startSpeedMps: 4.2, endIdleMs: 60_000)
        _ = d.onFix(fast(1000)); _ = d.onFix(fast(2000)); _ = d.onFix(fast(3000))
        XCTAssertNil(d.onFix(fast(50_000)))
        XCTAssertNil(d.tick(now: 100_000))
        XCTAssertEqual(d.tick(now: 110_000), .end(fast(50_000)))
    }

    func testForceEnd() {
        var d = detector()
        XCTAssertNil(d.forceEnd())
        _ = d.onFix(fast(1000)); _ = d.onFix(fast(2000)); _ = d.onFix(fast(3000))
        XCTAssertEqual(d.forceEnd(), .end(fast(3000)))
        XCTAssertFalse(d.inTrip)
    }
}
