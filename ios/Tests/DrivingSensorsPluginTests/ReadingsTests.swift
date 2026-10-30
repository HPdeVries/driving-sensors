import XCTest
@testable import DrivingSensorsPlugin

final class ReadingsTests: XCTestCase {
    private func sample(_ ts: Int64, ax: Double = 0, ay: Double = 0, az: Double = 0) -> MotionSample {
        MotionSample(ts: ts, ax: ax, ay: ay, az: az, gx: 0, gy: 0, gz: -9.8)
    }

    func testWindowAggregation() {
        var w = MotionWindow()
        XCTAssertNil(w.snapshot)
        w.add(sample(1, ax: 1))
        w.add(sample(2, ax: 3, ay: 4)) // horizontal 5
        w.add(sample(3, ax: 2, az: 7)) // vertical part removed -> 2
        let s = w.snapshot
        XCTAssertEqual(s?.samples, 3)
        XCTAssertEqual(s?.sample.ts, 3)
        XCTAssertEqual(s?.horizontalMps2 ?? 0, 2, accuracy: 1e-9)
        XCTAssertEqual(s?.peakHorizontalMps2 ?? 0, 5, accuracy: 1e-9)
    }

    func testLocationDictionaryOmitsNilFields() {
        let d = LocationSnapshot(ts: 10, lat: 1, lon: 2).dictionary
        XCTAssertEqual(Set(d.keys), ["ts", "lat", "lon"])
        let full = LocationSnapshot(ts: 10, lat: 1, lon: 2, speedMps: 3, accuracyM: 4).dictionary
        XCTAssertEqual(Set(full.keys), ["ts", "lat", "lon", "speedMps", "accuracyM"])
    }

    func testMotionDictionaryPeakAndSamplesOnlyForWindow() {
        let plain = sample(1, ax: 3, ay: 4).snapshot().dictionary
        XCTAssertNil(plain["peakHorizontalMps2"])
        XCTAssertNil(plain["samples"])
        XCTAssertEqual(plain["horizontalMps2"] as? Double ?? 0, 5, accuracy: 1e-9)
        var w = MotionWindow()
        w.add(sample(1, ax: 3, ay: 4))
        let d = w.snapshot!.dictionary
        XCTAssertEqual(d["samples"] as? Int, 1)
        XCTAssertNotNil(d["peakHorizontalMps2"])
    }

    func testReadingsDictionary() {
        let empty = SensorReadings.dictionary(location: nil, motion: nil)
        XCTAssertEqual(empty["hasLocation"] as? Bool, false)
        XCTAssertEqual(empty["hasMotion"] as? Bool, false)
        XCTAssertNil(empty["location"])
        XCTAssertNil(empty["motion"])
        let both = SensorReadings.dictionary(location: LocationSnapshot(ts: 1, lat: 1, lon: 1),
                                             motion: sample(1).snapshot())
        XCTAssertEqual(both["hasLocation"] as? Bool, true)
        XCTAssertEqual(both["hasMotion"] as? Bool, true)
        XCTAssertNotNil(both["location"])
        XCTAssertNotNil(both["motion"])
    }

    func testClamp() {
        XCTAssertEqual(SensorReadings.clamp(nil, default: 10_000, min: 1_000, max: 60_000), 10_000)
        XCTAssertEqual(SensorReadings.clamp(5, default: 10_000, min: 1_000, max: 60_000), 1_000)
        XCTAssertEqual(SensorReadings.clamp(1e9, default: 10_000, min: 1_000, max: 60_000), 60_000)
    }
}
