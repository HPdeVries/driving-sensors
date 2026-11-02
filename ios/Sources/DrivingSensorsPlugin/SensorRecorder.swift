import Foundation
import CoreLocation
import CoreMotion

/// Buffers samples in memory, appends them to `<chunkId>.csv.part` once per second on its own
/// serial IO queue and rolls chunks over every `chunkSeconds`. Never blocks the producers.
final class RecordingWriter: @unchecked Sendable {
    private let directory: URL
    private let recordingId: String
    private let chunkSeconds: Double
    private let maxBytes: Int64
    private let sensorInfo: [String: String]
    private let onSeqAdvance: (Int) -> Void
    private let onStop: (String) -> Void
    private let io = DispatchQueue(label: "driving-sensors.recording-io")
    private let lock = NSLock()
    private var buffer: [RecordingSample] = []
    private var timer: DispatchSourceTimer?

    // IO queue only.
    private var nextSeq: Int
    private var handle: FileHandle?
    private var partURL: URL?
    private var stats = ChunkStats()
    private var chunkStart: Double = 0
    private var stopped = false

    init(directory: URL, recordingId: String, startSeq: Int, chunkSeconds: Double, maxStorageMb: Double,
         sensorInfo: [String: String], onSeqAdvance: @escaping (Int) -> Void, onStop: @escaping (String) -> Void) {
        self.directory = directory
        self.recordingId = recordingId
        self.nextSeq = startSeq
        self.chunkSeconds = chunkSeconds
        self.maxBytes = Int64(maxStorageMb * 1_048_576)
        self.sensorInfo = sensorInfo
        self.onSeqAdvance = onSeqAdvance
        self.onStop = onStop
    }

    func start() {
        io.sync {
            let t = DispatchSource.makeTimerSource(queue: io)
            t.schedule(deadline: .now() + 1, repeating: 1)
            t.setEventHandler { [weak self] in self?.flush() }
            t.resume()
            timer = t
        }
    }

    /// Called from sensor callback threads: only a lock and an append.
    func add(_ s: RecordingSample) {
        lock.lock()
        buffer.append(s)
        lock.unlock()
    }

    /// Flushes, finalizes the open chunk and stops the timer. Blocks on the IO queue only.
    func stop() {
        io.sync {
            timer?.cancel()
            timer = nil
            guard !stopped else { return }
            stopped = true
            flushBuffer()
            finalizeChunk()
        }
    }

    // MARK: - IO queue

    private func takeBuffer() -> [RecordingSample] {
        lock.lock(); defer { lock.unlock() }
        let b = buffer
        buffer = []
        return b
    }

    private func flush() {
        guard !stopped else { return }
        if !flushBuffer() { return fail("ERROR") }
        if handle != nil, ProcessInfo.processInfo.systemUptime - chunkStart >= chunkSeconds { finalizeChunk() }
        if RecordingFiles.totalBytes(in: directory) > maxBytes {
            fail("STORAGE_FULL")
        }
    }

    private func fail(_ reason: String) {
        timer?.cancel()
        timer = nil
        stopped = true
        finalizeChunk()
        onStop(reason)
    }

    @discardableResult
    private func flushBuffer() -> Bool {
        let batch = takeBuffer()
        guard !batch.isEmpty else { return true }
        do {
            if handle == nil { try openChunk() }
            var text = ""
            text.reserveCapacity(batch.count * 48)
            for s in batch {
                text += RecordingCSV.line(s)
                stats.add(s)
            }
            try handle?.write(contentsOf: Data(text.utf8))
            return true
        } catch {
            dsLog("recording write failed: \(error)")
            return false
        }
    }

    private func openChunk() throws {
        let id = RecordingChunkId.make(recordingId: recordingId, seq: nextSeq)
        let url = directory.appendingPathComponent(id + RecordingFiles.partSuffix)
        try Data(RecordingCSV.header.utf8).write(to: url)
        handle = try FileHandle(forWritingTo: url)
        try handle?.seekToEnd()
        partURL = url
        stats = ChunkStats()
        chunkStart = ProcessInfo.processInfo.systemUptime
        nextSeq += 1
        onSeqAdvance(nextSeq)
    }

    private func finalizeChunk() {
        guard let url = partURL else { return }
        try? handle?.close()
        handle = nil
        partURL = nil
        do {
            _ = try RecordingFiles.finalizePart(url, sensorInfo: sensorInfo, stats: stats)
        } catch {
            dsLog("could not finalize chunk: \(error)")
        }
        stats = ChunkStats()
    }
}

/// One running recording: CoreMotion sensors + altimeter + the fixes fed from the shared
/// location manager, written through a `RecordingWriter`.
final class SensorRecorder: @unchecked Sendable {
    let recordingId: String
    let options: RecordingOptions
    let startedAt: Int64
    let directory: URL
    private let motion: CMMotionManager
    private let writer: RecordingWriter
    private let rates: RateWindow
    private let altimeter = CMAltimeter()
    private let queue: OperationQueue = {
        let q = OperationQueue()
        q.name = "driving-sensors.recording-sensors"
        q.maxConcurrentOperationCount = 1
        q.qualityOfService = .userInitiated
        return q
    }()
    private let lock = NSLock()
    private var lastLocMs: Int64 = 0
    private var stopped = false

    static func availability(_ m: CMMotionManager) -> [String: Bool] {
        ["acc": m.isAccelerometerAvailable, "gyr": m.isGyroAvailable, "mag": m.isMagnetometerAvailable,
         "bar": CMAltimeter.isRelativeAltitudeAvailable(), "loc": CLLocationManager.locationServicesEnabled()]
    }

    init(directory: URL, recordingId: String, options: RecordingOptions, startSeq: Int, startedAt: Int64,
         motion: CMMotionManager, onSeqAdvance: @escaping (Int) -> Void, onStop: @escaping (String) -> Void) {
        self.directory = directory
        self.recordingId = recordingId
        self.options = options
        self.startedAt = startedAt
        self.motion = motion
        let avail = SensorRecorder.availability(motion)
        func info(_ key: String, _ text: String) -> String { avail[key] == true ? text : "unavailable" }
        let sensorInfo = [
            "acc": info("acc", "CMAccelerometerData raw, x -9.80665 (m/s2, Android sign convention), requested \(options.accelHz) Hz"),
            "gyr": info("gyr", "CMGyroData raw (rad/s), requested \(options.gyroHz) Hz"),
            "mag": info("mag", "CMMagnetometerData raw uncalibrated (uT), requested \(options.magHz) Hz"),
            "bar": info("bar", "CMAltimeter relative altitude updates: pressure kPa x 10 = hPa, relative altitude m; delivers ~1 Hz"),
            "loc": info("loc", "CLLocationManager best accuracy, distanceFilter none")
        ]
        self.rates = RateWindow(window: 5, startedAt: ProcessInfo.processInfo.systemUptime)
        self.writer = RecordingWriter(directory: directory, recordingId: recordingId, startSeq: startSeq,
                                      chunkSeconds: options.chunkSeconds, maxStorageMb: options.maxStorageMb,
                                      sensorInfo: sensorInfo, onSeqAdvance: onSeqAdvance, onStop: onStop)
    }

    private func emit(_ s: RecordingSample) {
        writer.add(s)
        rates.record(s.sensor, at: ProcessInfo.processInfo.systemUptime)
    }

    /// Starts the writer and all available sensors (not location: fixes arrive via `onLocation`).
    func start() {
        writer.start()
        let bootOffset = Date().timeIntervalSince1970 - ProcessInfo.processInfo.systemUptime
        func ms(_ t: TimeInterval) -> Int64 { Int64((t + bootOffset) * 1000) }

        if motion.isAccelerometerAvailable {
            motion.accelerometerUpdateInterval = 1.0 / options.accelHz
            motion.startAccelerometerUpdates(to: queue) { [weak self] d, _ in
                guard let self, let d else { return }
                let k = -9.80665
                self.emit(RecordingSample(t: ms(d.timestamp), sensor: .acc,
                                          x: d.acceleration.x * k, y: d.acceleration.y * k, z: d.acceleration.z * k))
            }
        }
        if motion.isGyroAvailable {
            motion.gyroUpdateInterval = 1.0 / options.gyroHz
            motion.startGyroUpdates(to: queue) { [weak self] d, _ in
                guard let self, let d else { return }
                self.emit(RecordingSample(t: ms(d.timestamp), sensor: .gyr,
                                          x: d.rotationRate.x, y: d.rotationRate.y, z: d.rotationRate.z))
            }
        }
        if motion.isMagnetometerAvailable {
            motion.magnetometerUpdateInterval = 1.0 / options.magHz
            motion.startMagnetometerUpdates(to: queue) { [weak self] d, _ in
                guard let self, let d else { return }
                self.emit(RecordingSample(t: ms(d.timestamp), sensor: .mag,
                                          x: d.magneticField.x, y: d.magneticField.y, z: d.magneticField.z))
            }
        }
        if CMAltimeter.isRelativeAltitudeAvailable() {
            altimeter.startRelativeAltitudeUpdates(to: queue) { [weak self] d, _ in
                guard let self, let d else { return }
                self.emit(RecordingSample(t: ms(d.timestamp), sensor: .bar,
                                          x: d.pressure.doubleValue * 10, y: d.relativeAltitude.doubleValue))
            }
        }
    }

    /// Any thread. Skips invalid fixes and honours locationIntervalMs.
    func onLocation(_ locations: [CLLocation]) {
        for loc in locations where loc.horizontalAccuracy >= 0 {
            let t = Int64(loc.timestamp.timeIntervalSince1970 * 1000)
            lock.lock()
            let skip = lastLocMs != 0 && t - lastLocMs < Int64(options.locationIntervalMs) - 100
            if !skip { lastLocMs = t }
            lock.unlock()
            if skip { continue }
            emit(RecordingSample(t: t, sensor: .loc, x: loc.coordinate.latitude, y: loc.coordinate.longitude,
                                 z: loc.verticalAccuracy >= 0 ? loc.altitude : nil,
                                 v4: loc.speed >= 0 ? loc.speed : nil, v5: loc.horizontalAccuracy))
        }
    }

    /// Stops the sensor callbacks (cheap, any thread).
    func stopSensors() {
        lock.lock()
        let already = stopped
        stopped = true
        lock.unlock()
        guard !already else { return }
        motion.stopAccelerometerUpdates()
        motion.stopGyroUpdates()
        motion.stopMagnetometerUpdates()
        altimeter.stopRelativeAltitudeUpdates()
    }

    /// Flushes and finalizes the last chunk. Call off the main thread.
    func finish() {
        stopSensors()
        queue.waitUntilAllOperationsAreFinished()
        writer.stop()
    }

    func measuredHz() -> [String: Any] {
        let hz = rates.hz(now: ProcessInfo.processInfo.systemUptime)
        var out: [String: Any] = [:]
        for s in RecordingSensor.allCases { out[s.rawValue] = hz[s] ?? 0 }
        return out
    }
}
