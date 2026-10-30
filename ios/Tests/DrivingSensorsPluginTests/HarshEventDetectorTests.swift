import XCTest
@testable import DrivingSensorsPlugin

final class HarshEventDetectorTests: XCTestCase {
    private func make(brake: Double = 3, accel: Double = 3, gap: Int64 = 3000) -> HarshEventDetector {
        HarshEventDetector(harshBrakeMps2: brake, harshAccelMps2: accel, minEventGapMs: gap)
    }

    /// Feeds horizontal (x-axis) acceleration `ax` with gravity on z, every `step` ms in [from, to).
    private func feed(_ d: HarshEventDetector, from: Int64, to: Int64, ax: Double, step: Int64 = 20) {
        var t = from
        while t < to {
            d.onAccel(ts: t, ax: ax, ay: 0, az: 0, gx: 0, gy: 0, gz: 9.81)
            t += step
        }
    }

    private func fix(_ ts: Int64, _ speed: Double?) -> Fix { Fix(ts: ts, lat: 52, lon: 5, speedMps: speed) }

    // MARK: projection

    func testHorizontalProjectionGravityOnZ() {
        let m = HarshEventDetector.horizontalMagnitude(ax: 3, ay: 4, az: 9, gx: 0, gy: 0, gz: 9.81)
        XCTAssertEqual(m, 5, accuracy: 1e-9)
    }

    func testHorizontalProjectionGravityOnY() {
        let m = HarshEventDetector.horizontalMagnitude(ax: 3, ay: 7, az: 4, gx: 0, gy: -9.81, gz: 0)
        XCTAssertEqual(m, 5, accuracy: 1e-9)
    }

    func testHorizontalProjectionTilted() {
        // g direction (0, 0.6, 0.8); a = 5 along x plus 2 along gravity.
        let m = HarshEventDetector.horizontalMagnitude(ax: 5, ay: 1.2, az: 1.6, gx: 0, gy: 6, gz: 8)
        XCTAssertEqual(m, 5, accuracy: 1e-9)
    }

    func testSmallGravityFallsBackToFullMagnitude() {
        let m = HarshEventDetector.horizontalMagnitude(ax: 3, ay: 4, az: 0, gx: 0, gy: 0, gz: 0.5)
        XCTAssertEqual(m, 5, accuracy: 1e-9)
    }

    // MARK: classification

    func testBrakeClassifiedByGpsSpeedDrop() {
        let d = make()
        _ = d.onFix(fix(0, 15))
        feed(d, from: 1000, to: 1600, ax: -5)
        feed(d, from: 1600, to: 2500, ax: 0)
        let events = d.onFix(fix(3000, 8))
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].type, .brake)
        XCTAssertEqual(events[0].ts, 1000)
        XCTAssertEqual(events[0].speedMps, 15)
        XCTAssertEqual(events[0].peakMps2, 5, accuracy: 1e-9)
        XCTAssertEqual(events[0].lat, 52)
    }

    func testAccelClassifiedByGpsSpeedRise() {
        let d = make()
        _ = d.onFix(fix(0, 5))
        feed(d, from: 1000, to: 1600, ax: 5)
        feed(d, from: 1600, to: 2500, ax: 0)
        let events = d.onFix(fix(3000, 10))
        XCTAssertEqual(events.map(\.type), [.accel])
        XCTAssertEqual(events[0].speedMps, 5)
    }

    func testWaitsForFixAtLeastOneSecondAfterEnd() {
        let d = make()
        _ = d.onFix(fix(0, 15))
        feed(d, from: 1000, to: 1600, ax: -5)
        feed(d, from: 1600, to: 2000, ax: 0)
        XCTAssertTrue(d.onFix(fix(1500, 9)).isEmpty)
        XCTAssertEqual(d.onFix(fix(3000, 9)).count, 1)
    }

    func testShortCandidateDiscarded() {
        let d = make()
        _ = d.onFix(fix(0, 15))
        // 50 ms sampling: smoothed drops below 0.7*T after 100 ms.
        d.onAccel(ts: 1000, ax: -5, ay: 0, az: 0, gx: 0, gy: 0, gz: 9.81)
        d.onAccel(ts: 1050, ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 9.81)
        d.onAccel(ts: 1100, ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 9.81)
        feed(d, from: 1150, to: 2500, ax: 0, step: 50)
        XCTAssertTrue(d.onFix(fix(3000, 8)).isEmpty)
    }

    func testMinGapSuppressesSecondEvent() {
        let d = make(gap: 3000)
        _ = d.onFix(fix(0, 15))
        feed(d, from: 1000, to: 1600, ax: -5)
        feed(d, from: 1600, to: 2500, ax: 0)
        XCTAssertEqual(d.onFix(fix(2800, 8)).count, 1)
        feed(d, from: 2800, to: 4000, ax: 0)
        feed(d, from: 4000, to: 4600, ax: -5)
        feed(d, from: 4600, to: 5500, ax: 0)
        XCTAssertTrue(d.onFix(fix(5800, 3)).isEmpty)
    }

    func testSecondEventAllowedWhenGapElapsed() {
        let d = make(gap: 1000)
        _ = d.onFix(fix(0, 15))
        feed(d, from: 1000, to: 1600, ax: -5)
        feed(d, from: 1600, to: 2500, ax: 0)
        XCTAssertEqual(d.onFix(fix(2800, 8)).count, 1)
        feed(d, from: 2800, to: 4000, ax: 0)
        feed(d, from: 4000, to: 4600, ax: -5)
        feed(d, from: 4600, to: 5500, ax: 0)
        XCTAssertEqual(d.onFix(fix(5800, 3)).count, 1)
    }

    func testSmallSpeedDeltaDiscarded() {
        let d = make()
        _ = d.onFix(fix(0, 10))
        feed(d, from: 1000, to: 1600, ax: -5)
        feed(d, from: 1600, to: 2500, ax: 0)
        XCTAssertTrue(d.onFix(fix(3000, 9.6)).isEmpty)
    }

    func testUnknownSpeedDiscarded() {
        let d = make()
        _ = d.onFix(fix(0, nil))
        feed(d, from: 1000, to: 1600, ax: -5)
        feed(d, from: 1600, to: 2500, ax: 0)
        XCTAssertTrue(d.onFix(fix(3000, 8)).isEmpty)
    }

    func testPeakBelowClassThresholdDiscarded() {
        // T = min(4, 2) = 2; a peak of 3 triggers but is below the brake threshold of 4.
        let brake = make(brake: 4, accel: 2)
        _ = brake.onFix(fix(0, 15))
        feed(brake, from: 1000, to: 1600, ax: -3)
        feed(brake, from: 1600, to: 2500, ax: 0)
        XCTAssertTrue(brake.onFix(fix(3000, 8)).isEmpty)

        // The same peak on an acceleration passes the accel threshold of 2.
        let accel = make(brake: 4, accel: 2)
        _ = accel.onFix(fix(0, 5))
        feed(accel, from: 1000, to: 1600, ax: 3)
        feed(accel, from: 1600, to: 2500, ax: 0)
        XCTAssertEqual(accel.onFix(fix(3000, 10)).map(\.type), [.accel])
    }

    func testPendingDiscardedAfterFiveSecondsWithoutFix() {
        let d = make()
        _ = d.onFix(fix(0, 15))
        feed(d, from: 1000, to: 1600, ax: -5)
        feed(d, from: 1600, to: 7500, ax: 0)
        XCTAssertTrue(d.onFix(fix(7600, 8)).isEmpty)
    }

    func testPendingDiscardedByTick() {
        let d = make()
        _ = d.onFix(fix(0, 15))
        feed(d, from: 1000, to: 1600, ax: -5)
        feed(d, from: 1600, to: 2000, ax: 0)
        d.tick(now: 9000)
        XCTAssertTrue(d.onFix(fix(9100, 8)).isEmpty)
    }

    func testGravityAxisAccelerationDoesNotTrigger() {
        let d = make()
        _ = d.onFix(fix(0, 15))
        var t: Int64 = 1000
        while t < 2000 { // large vertical bump only
            d.onAccel(ts: t, ax: 0, ay: 0, az: 8, gx: 0, gy: 0, gz: 9.81)
            t += 20
        }
        XCTAssertTrue(d.onFix(fix(3500, 8)).isEmpty)
    }
}
