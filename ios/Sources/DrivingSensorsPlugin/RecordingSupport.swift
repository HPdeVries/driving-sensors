import Foundation
import zlib

// Pure, unit-testable pieces of raw sensor recording (v0.2.0).

enum RecordingSensor: String, CaseIterable, Codable {
    case acc, gyr, mag, bar, loc
}

/// Samples per sensor (the `counts` / `measuredHz` shape of definitions.ts).
struct SensorCounts: Codable, Equatable {
    var acc = 0, gyr = 0, mag = 0, bar = 0, loc = 0

    subscript(s: RecordingSensor) -> Int {
        get {
            switch s {
            case .acc: return acc
            case .gyr: return gyr
            case .mag: return mag
            case .bar: return bar
            case .loc: return loc
            }
        }
        set {
            switch s {
            case .acc: acc = newValue
            case .gyr: gyr = newValue
            case .mag: mag = newValue
            case .bar: bar = newValue
            case .loc: loc = newValue
            }
        }
    }

    var dictionary: [String: Any] { ["acc": acc, "gyr": gyr, "mag": mag, "bar": bar, "loc": loc] }
}

struct RecordingSample {
    var t: Int64
    var sensor: RecordingSensor
    var x: Double?
    var y: Double?
    var z: Double?
    var v4: Double?
    var v5: Double?

    init(t: Int64, sensor: RecordingSensor, x: Double? = nil, y: Double? = nil, z: Double? = nil,
         v4: Double? = nil, v5: Double? = nil) {
        self.t = t; self.sensor = sensor; self.x = x; self.y = y; self.z = z; self.v4 = v4; self.v5 = v5
    }
}

/// Resolved recording options, clamped per SPEC. Notification fields are Android-only.
struct RecordingOptions: Codable, Equatable {
    var accelHz = 100.0
    var gyroHz = 100.0
    var magHz = 50.0
    var baroHz = 10.0
    var locationIntervalMs = 1000.0
    var chunkSeconds = 60.0
    var maxStorageMb = 500.0

    static func parse(_ dict: [String: Any]) throws -> RecordingOptions {
        var o = RecordingOptions()
        func num(_ key: String, _ target: inout Double, _ lo: Double, _ hi: Double) throws {
            guard let raw = dict[key], !(raw is NSNull) else { return }
            guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else {
                throw DrivingSensorsError.invalid("\(key) must be a number")
            }
            guard n.doubleValue > 0, n.doubleValue.isFinite else { throw DrivingSensorsError.invalid("\(key) must be > 0") }
            target = min(max(n.doubleValue, lo), hi)
        }
        try num("accelHz", &o.accelHz, 1, 200)
        try num("gyroHz", &o.gyroHz, 1, 200)
        try num("magHz", &o.magHz, 1, 200)
        try num("baroHz", &o.baroHz, 1, 50)
        try num("locationIntervalMs", &o.locationIntervalMs, 500, 60_000)
        try num("chunkSeconds", &o.chunkSeconds, 10, 600)
        try num("maxStorageMb", &o.maxStorageMb, 10, 10_000)
        return o
    }
}

enum RecordingCSV {
    static let header = "t_ms,sensor,x,y,z,v4,v5\n"
    private static let posix = Locale(identifier: "en_US_POSIX")

    /// POSIX number formatting (always "."), up to `decimals` decimals, trailing zeros trimmed.
    /// nil / non-finite gives an empty field.
    static func number(_ v: Double?, decimals: Int) -> String {
        guard let v, v.isFinite else { return "" }
        var s = String(format: "%.\(decimals)f", locale: posix, v)
        if s.contains(".") {
            while s.hasSuffix("0") { s.removeLast() }
            if s.hasSuffix(".") { s.removeLast() }
        }
        if s == "-0" { s = "0" }
        return s
    }

    /// One CSV line including the trailing "\n". lat/lon get 8 decimals, everything else 6.
    static func line(_ s: RecordingSample) -> String {
        let d = s.sensor == .loc ? 8 : 6
        return "\(s.t),\(s.sensor.rawValue),\(number(s.x, decimals: d)),\(number(s.y, decimals: d)),"
            + "\(number(s.z, decimals: 6)),\(number(s.v4, decimals: 6)),\(number(s.v5, decimals: 6))\n"
    }
}

enum RecordingChunkId {
    static func make(recordingId: String, seq: Int) -> String {
        recordingId + "-" + String(format: "%06d", seq)
    }

    /// Splits `<recordingId>-<seq>`; the recording id itself contains dashes (UUID).
    static func parse(_ chunkId: String) -> (recordingId: String, seq: Int)? {
        guard let i = chunkId.lastIndex(of: "-") else { return nil }
        let rec = String(chunkId[..<i]), seqStr = String(chunkId[chunkId.index(after: i)...])
        guard !rec.isEmpty, seqStr.count >= 6, let seq = Int(seqStr) else { return nil }
        return (rec, seq)
    }

    /// Chunk ids become file names: only [A-Za-z0-9-] are accepted.
    static func isValid(_ chunkId: String) -> Bool {
        !chunkId.isEmpty && chunkId.count <= 128 && parse(chunkId) != nil
            && chunkId.unicodeScalars.allSatisfy { $0.value < 128 && (CharacterSet.alphanumerics.contains($0) || $0 == "-") }
    }
}

/// Rolling per-sensor sample counter. `hz` = samples in the window / window length.
final class RateWindow: @unchecked Sendable {
    private let window: Double
    private let startedAt: Double
    private let lock = NSLock()
    private var stamps: [RecordingSensor: [Double]] = [:]
    private var heads: [RecordingSensor: Int] = [:]

    init(window: Double = 5, startedAt: Double) {
        self.window = window
        self.startedAt = startedAt
    }

    func record(_ sensor: RecordingSensor, at now: Double) {
        lock.lock(); defer { lock.unlock() }
        stamps[sensor, default: []].append(now)
        prune(sensor, now)
    }

    func hz(now: Double) -> [RecordingSensor: Double] {
        lock.lock(); defer { lock.unlock() }
        let length = min(window, max(now - startedAt, 1))
        var out: [RecordingSensor: Double] = [:]
        for s in RecordingSensor.allCases {
            prune(s, now)
            out[s] = Double((stamps[s]?.count ?? 0) - (heads[s] ?? 0)) / length
        }
        return out
    }

    private func prune(_ s: RecordingSensor, _ now: Double) {
        guard let arr = stamps[s] else { return }
        var h = heads[s] ?? 0
        while h < arr.count && arr[h] <= now - window { h += 1 }
        if h > 4096 { stamps[s] = Array(arr[h...]); h = 0 }
        heads[s] = h
    }
}

struct GzipError: Error { let code: Int32 }

enum Gzip {
    /// Real gzip framing (zlib windowBits 15 + 16).
    static func compress(_ input: Data) throws -> Data {
        var strm = z_stream()
        let rc = deflateInit2_(&strm, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY,
                               ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard rc == Z_OK else { throw GzipError(code: rc) }
        defer { deflateEnd(&strm) }
        var out = Data()
        let bufSize = 64 * 1024
        var buf = [UInt8](repeating: 0, count: bufSize)
        var err: Int32 = Z_OK
        input.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            strm.next_in = UnsafeMutablePointer<Bytef>(mutating: raw.bindMemory(to: Bytef.self).baseAddress)
            strm.avail_in = uInt(raw.count)
            repeat {
                buf.withUnsafeMutableBufferPointer { p in
                    strm.next_out = p.baseAddress
                    strm.avail_out = uInt(bufSize)
                    err = deflate(&strm, Z_FINISH)
                    out.append(p.baseAddress!, count: bufSize - Int(strm.avail_out))
                }
            } while err == Z_OK
        }
        guard err == Z_STREAM_END else { throw GzipError(code: err) }
        return out
    }
}

/// Persisted next to each finished chunk as `<chunkId>.json`.
struct RecordingChunkMeta: Codable, Equatable {
    var chunkId: String
    var recordingId: String
    var seq: Int
    var startTs: Int64
    var endTs: Int64
    var bytes: Int
    var counts: SensorCounts
    var sensorInfo: [String: String]?

    /// The RecordingChunkInfo fields only.
    var infoDictionary: [String: Any] {
        ["chunkId": chunkId, "recordingId": recordingId, "seq": seq, "startTs": startTs,
         "endTs": endTs, "bytes": bytes, "counts": counts.dictionary]
    }
}

struct ChunkStats {
    var counts = SensorCounts()
    var minT: Int64 = .max
    var maxT: Int64 = .min

    var isEmpty: Bool { minT == .max }

    mutating func add(_ s: RecordingSample) {
        counts[s.sensor] += 1
        minT = min(minT, s.t)
        maxT = max(maxT, s.t)
    }

    /// Computes stats from CSV bytes (header skipped); malformed lines are ignored.
    static func scan(_ csv: Data) -> ChunkStats {
        var st = ChunkStats()
        let text = String(decoding: csv, as: UTF8.self)
        for line in text.split(separator: "\n", omittingEmptySubsequences: true).dropFirst() {
            let f = line.split(separator: ",", maxSplits: 2, omittingEmptySubsequences: false)
            guard f.count >= 3, let t = Int64(f[0]), let s = RecordingSensor(rawValue: String(f[1])) else { continue }
            st.add(RecordingSample(t: t, sensor: s))
        }
        return st
    }
}

enum RecordingFiles {
    static let partSuffix = ".csv.part"
    static let rejectedSuffix = ".rejected.json"

    /// The server refused this chunk (400/413/422): `<id>.json` becomes `<id>.rejected.json`, the
    /// `.gz` stays. It is no longer listed but still counts towards storage.
    static func rejectChunk(_ chunkId: String, in dir: URL) {
        try? FileManager.default.moveItem(at: dir.appendingPathComponent(chunkId + ".json"),
                                          to: dir.appendingPathComponent(chunkId + rejectedSuffix))
    }

    static func rejectedCount(in dir: URL) -> Int {
        ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.lastPathComponent.hasSuffix(rejectedSuffix) }.count
    }

    /// Turns `<chunkId>.csv.part` into `.csv.gz` + `.json` and deletes the part. Data after the
    /// last newline (a torn write) is dropped. Parts without samples are deleted and give nil.
    static func finalizePart(_ part: URL, sensorInfo: [String: String]?, stats known: ChunkStats? = nil) throws -> RecordingChunkMeta? {
        let name = part.lastPathComponent
        guard name.hasSuffix(partSuffix) else { return nil }
        let chunkId = String(name.dropLast(partSuffix.count))
        let dir = part.deletingLastPathComponent()
        var data = try Data(contentsOf: part)
        if let last = data.lastIndex(of: 0x0A) {
            if last != data.count - 1 { data = data.prefix(last + 1) }
        } else {
            data = Data()
        }
        let fm = FileManager.default
        guard let ids = RecordingChunkId.parse(chunkId) else {
            try? fm.removeItem(at: part)
            return nil
        }
        let stats = known ?? ChunkStats.scan(data)
        guard !stats.isEmpty else {
            try? fm.removeItem(at: part)
            return nil
        }
        let gz = try Gzip.compress(data)
        let gzURL = dir.appendingPathComponent(chunkId + ".csv.gz")
        try gz.write(to: gzURL, options: .atomic)
        let meta = RecordingChunkMeta(chunkId: chunkId, recordingId: ids.recordingId, seq: ids.seq,
                                      startTs: stats.minT, endTs: stats.maxT, bytes: gz.count,
                                      counts: stats.counts, sensorInfo: sensorInfo)
        try JSONEncoder().encode(meta).write(to: dir.appendingPathComponent(chunkId + ".json"), options: .atomic)
        try? fm.removeItem(at: part)
        return meta
    }

    /// Finalizes every leftover `.part` file in `dir`; returns the chunks produced.
    @discardableResult
    static func finalizeLeftovers(in dir: URL) -> [RecordingChunkMeta] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        var out: [RecordingChunkMeta] = []
        for u in urls where u.lastPathComponent.hasSuffix(partSuffix) {
            do {
                if let m = try finalizePart(u, sensorInfo: nil) { out.append(m) }
            } catch {
                dsLog("could not finalize \(u.lastPathComponent): \(error)")
            }
        }
        return out
    }

    static func totalBytes(in dir: URL) -> Int64 {
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return urls.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    /// Finished chunks (json + gz present), oldest first.
    static func listChunks(in dir: URL, limit: Int) -> [RecordingChunkMeta] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        var metas: [RecordingChunkMeta] = []
        for u in urls where u.pathExtension == "json" && !u.lastPathComponent.hasSuffix(rejectedSuffix) {
            guard let data = try? Data(contentsOf: u), let m = try? JSONDecoder().decode(RecordingChunkMeta.self, from: data),
                  FileManager.default.fileExists(atPath: dir.appendingPathComponent(m.chunkId + ".csv.gz").path) else { continue }
            metas.append(m)
        }
        metas.sort { ($0.startTs, $0.seq, $0.chunkId) < ($1.startTs, $1.seq, $1.chunkId) }
        return Array(metas.prefix(max(limit, 0)))
    }

    static func defaultDirectory() throws -> URL {
        let fm = FileManager.default
        let support = try fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        var root = support.appendingPathComponent("driving_sensors", isDirectory: true)
        var dir = root.appendingPathComponent("recordings", isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? root.setResourceValues(values)
        try? dir.setResourceValues(values)
        return dir
    }
}
