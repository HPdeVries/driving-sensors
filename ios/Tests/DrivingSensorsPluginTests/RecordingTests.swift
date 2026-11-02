import XCTest
import zlib
@testable import DrivingSensorsPlugin

final class RecordingTests: XCTestCase {
    func testCsvLineWithEmptyFields() {
        let acc = RecordingSample(t: 1_793_367_101_003, sensor: .acc, x: 0.123, y: -0.045, z: 9.812)
        XCTAssertEqual(RecordingCSV.line(acc), "1793367101003,acc,0.123,-0.045,9.812,,\n")
        let bar = RecordingSample(t: 5, sensor: .bar, x: 1013.25)
        XCTAssertEqual(RecordingCSV.line(bar), "5,bar,1013.25,,,,\n")
        let loc = RecordingSample(t: 7, sensor: .loc, x: 52.09073741, y: 5.12142212, z: 4.2, v4: 13.4, v5: 4.8)
        XCTAssertEqual(RecordingCSV.line(loc), "7,loc,52.09073741,5.12142212,4.2,13.4,4.8\n")
        let none = RecordingSample(t: 1, sensor: .gyr, x: .nan, y: nil, z: -0.0000001)
        XCTAssertEqual(RecordingCSV.line(none), "1,gyr,,,0,,\n")
    }

    func testCsvDecimalSeparatorIgnoresCurrentLocale() {
        // String(format:) without an explicit locale never uses the user locale, and ours is POSIX;
        // verify the formatter output is stable even when the process default locale differs.
        UserDefaults.standard.set(["nl_NL"], forKey: "AppleLanguages")
        UserDefaults.standard.set("nl_NL", forKey: "AppleLocale")
        defer {
            UserDefaults.standard.removeObject(forKey: "AppleLanguages")
            UserDefaults.standard.removeObject(forKey: "AppleLocale")
        }
        XCTAssertEqual(RecordingCSV.number(1234.5, decimals: 6), "1234.5")
        XCTAssertEqual(RecordingCSV.number(-0.25, decimals: 6), "-0.25")
        // Sanity: a nl_NL formatter would have produced a comma, ours must not.
        XCTAssertEqual(String(format: "%.1f", locale: Locale(identifier: "nl_NL"), 1.5), "1,5")
        XCTAssertEqual(RecordingCSV.number(1.5, decimals: 3), "1.5")
        XCTAssertFalse(RecordingCSV.line(RecordingSample(t: 1, sensor: .acc, x: 1.5, y: 2.5, z: 3.5)).contains("1,5"))
    }

    func testChunkId() {
        XCTAssertEqual(RecordingChunkId.make(recordingId: "abc-def", seq: 7), "abc-def-000007")
        XCTAssertEqual(RecordingChunkId.make(recordingId: "r", seq: 1234567), "r-1234567")
        let p = RecordingChunkId.parse("0a1b2c3d-1111-4222-8333-444455556666-000012")
        XCTAssertEqual(p?.recordingId, "0a1b2c3d-1111-4222-8333-444455556666")
        XCTAssertEqual(p?.seq, 12)
        XCTAssertTrue(RecordingChunkId.isValid("rec-000001"))
        XCTAssertFalse(RecordingChunkId.isValid("../etc-000001"))
        XCTAssertFalse(RecordingChunkId.isValid("nodash"))
    }

    func testRateWindow() {
        let w = RateWindow(window: 5, startedAt: 0)
        for i in 0..<1000 { w.record(.acc, at: Double(i) * 0.01) } // 100 Hz for 10 s
        w.record(.loc, at: 9.5)
        let hz = w.hz(now: 10)
        XCTAssertEqual(hz[.acc]!, 100, accuracy: 1)
        XCTAssertEqual(hz[.loc]!, 0.2, accuracy: 0.001)
        XCTAssertEqual(hz[.gyr]!, 0)
        // everything ages out
        XCTAssertEqual(w.hz(now: 20)[.acc]!, 0)
    }

    func testRateWindowShortRecordingUsesElapsed() {
        let w = RateWindow(window: 5, startedAt: 100)
        for i in 0..<100 { w.record(.acc, at: 100 + Double(i) * 0.01) }
        XCTAssertEqual(w.hz(now: 101)[.acc]!, 100, accuracy: 1)
    }

    private func gunzip(_ gz: Data) -> Data? {
        var s = z_stream()
        guard inflateInit2_(&s, 15 + 32, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return nil }
        defer { inflateEnd(&s) }
        var out = Data()
        var buf = [UInt8](repeating: 0, count: 65536)
        var rc: Int32 = Z_OK
        gz.withUnsafeBytes { raw in
            s.next_in = UnsafeMutablePointer<Bytef>(mutating: raw.bindMemory(to: Bytef.self).baseAddress)
            s.avail_in = uInt(raw.count)
            repeat {
                buf.withUnsafeMutableBufferPointer { p in
                    s.next_out = p.baseAddress
                    s.avail_out = 65536
                    rc = zlib.inflate(&s, Z_NO_FLUSH)
                    out.append(p.baseAddress!, count: 65536 - Int(s.avail_out))
                }
            } while rc == Z_OK
        }
        return rc == Z_STREAM_END ? out : nil
    }

    func testGzipRoundTrip() throws {
        var text = RecordingCSV.header
        for i in 0..<20_000 { text += "\(i),acc,0.\(i % 1000),-1.5,9.81,,\n" }
        let original = Data(text.utf8)
        let gz = try Gzip.compress(original)
        XCTAssertEqual(gz[0], 0x1f) // gzip magic, not a raw zlib stream
        XCTAssertEqual(gz[1], 0x8b)
        XCTAssertLessThan(gz.count, original.count)
        XCTAssertEqual(gunzip(gz), original)
        XCTAssertEqual(gunzip(try Gzip.compress(Data())), Data())
    }

    private func tempDir() throws -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("rec-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: d) }
        return d
    }

    func testFinalizeLeftoverPart() throws {
        let dir = try tempDir()
        let id = "11111111-2222-4333-8444-555555555555"
        let chunk = RecordingChunkId.make(recordingId: id, seq: 3)
        var csv = RecordingCSV.header
        csv += RecordingCSV.line(RecordingSample(t: 1000, sensor: .acc, x: 1, y: 2, z: 3))
        csv += RecordingCSV.line(RecordingSample(t: 1005, sensor: .gyr, x: 1, y: 2, z: 3))
        csv += RecordingCSV.line(RecordingSample(t: 1010, sensor: .acc, x: 1, y: 2, z: 3))
        let complete = csv
        csv += "1020,acc,0.5,0." // torn last line from a crash
        let part = dir.appendingPathComponent(chunk + ".csv.part")
        try Data(csv.utf8).write(to: part)

        let metas = RecordingFiles.finalizeLeftovers(in: dir)
        XCTAssertEqual(metas.count, 1)
        let m = try XCTUnwrap(metas.first)
        XCTAssertEqual(m.chunkId, chunk)
        XCTAssertEqual(m.recordingId, id)
        XCTAssertEqual(m.seq, 3)
        XCTAssertEqual(m.startTs, 1000)
        XCTAssertEqual(m.endTs, 1010)
        XCTAssertEqual(m.counts.acc, 2)
        XCTAssertEqual(m.counts.gyr, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: part.path))
        let gz = try Data(contentsOf: dir.appendingPathComponent(chunk + ".csv.gz"))
        XCTAssertEqual(m.bytes, gz.count)
        XCTAssertEqual(gunzip(gz), Data(complete.utf8))
        let listed = RecordingFiles.listChunks(in: dir, limit: 10)
        XCTAssertEqual(listed.map { $0.chunkId }, [chunk])
        XCTAssertEqual(listed.first?.counts, m.counts)
    }

    func testFinalizeEmptyPartIsDropped() throws {
        let dir = try tempDir()
        let part = dir.appendingPathComponent("rec-000000.csv.part")
        try Data(RecordingCSV.header.utf8).write(to: part)
        XCTAssertTrue(RecordingFiles.finalizeLeftovers(in: dir).isEmpty)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path), [])
    }

    func testWriterRollsOverAndStops() throws {
        let dir = try tempDir()
        var seqs: [Int] = []
        let w = RecordingWriter(directory: dir, recordingId: "rec", startSeq: 5, chunkSeconds: 10, maxStorageMb: 10,
                                sensorInfo: ["acc": "test"], onSeqAdvance: { seqs.append($0) }, onStop: { _ in })
        w.start()
        for i in 0..<50 { w.add(RecordingSample(t: Int64(i), sensor: .acc, x: 1, y: 2, z: 3)) }
        w.stop()
        let chunks = RecordingFiles.listChunks(in: dir, limit: 10)
        XCTAssertEqual(chunks.map { $0.chunkId }, ["rec-000005"])
        XCTAssertEqual(chunks.first?.counts.acc, 50)
        XCTAssertEqual(seqs, [6])
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".part") }, [])
    }

    func testOptionClamping() throws {
        let d = try RecordingOptions.parse([:])
        XCTAssertEqual(d, RecordingOptions())
        XCTAssertEqual(d.accelHz, 100)
        XCTAssertEqual(d.chunkSeconds, 60)
        let o = try RecordingOptions.parse([
            "accelHz": 1000, "gyroHz": 0.2, "magHz": 500, "baroHz": 400, "locationIntervalMs": 10,
            "chunkSeconds": 5000, "maxStorageMb": 1
        ])
        XCTAssertEqual(o.accelHz, 200)
        XCTAssertEqual(o.gyroHz, 1)
        XCTAssertEqual(o.magHz, 200)
        XCTAssertEqual(o.baroHz, 50)
        XCTAssertEqual(o.locationIntervalMs, 500)
        XCTAssertEqual(o.chunkSeconds, 600)
        XCTAssertEqual(o.maxStorageMb, 10)
        XCTAssertEqual(try RecordingOptions.parse(["locationIntervalMs": 1_000_000]).locationIntervalMs, 60_000)
        XCTAssertEqual(try RecordingOptions.parse(["chunkSeconds": 1]).chunkSeconds, 10)
    }

    func testOptionValidation() {
        for bad: [String: Any] in [["accelHz": 0], ["gyroHz": -5], ["chunkSeconds": "60"], ["maxStorageMb": true],
                                   ["baroHz": Double.infinity]] {
            XCTAssertThrowsError(try RecordingOptions.parse(bad)) {
                XCTAssertEqual(($0 as? DrivingSensorsError)?.code, "INVALID_ARGUMENT")
            }
        }
        XCTAssertNoThrow(try RecordingOptions.parse(["notificationTitle": "x", "accelHz": NSNull()]))
    }
}
