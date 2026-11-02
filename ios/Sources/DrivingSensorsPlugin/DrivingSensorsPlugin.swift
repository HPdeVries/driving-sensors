import Foundation
import Capacitor

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(DrivingSensorsPlugin)
public class DrivingSensorsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "DrivingSensorsPlugin"
    public let jsName = "DrivingSensors"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getBufferedEvents", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "ackEvents", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLastReadings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCurrentReadings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPluginVersion", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getRecordingStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "listRecordingChunks", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getRecordingChunk", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "ackRecordingChunk", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "configureUpload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearUpload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getUploadStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "uploadNow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startHeartbeat", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopHeartbeat", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getHeartbeatStatus", returnType: CAPPluginReturnPromise)
    ]
    private let implementation = DrivingSensors.shared

    override public func load() {
        implementation.resumeIfNeeded()
    }

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        implementation.checkPermissions { call.resolve($0) }
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        let all = ["location", "backgroundLocation", "motion", "notifications"]
        var types: Set<String> = ["location", "motion", "notifications"]
        if let requested = call.getArray("permissions") {
            guard let strings = requested as? [String], strings.allSatisfy(all.contains) else {
                return call.reject("Unknown permission type", "INVALID_ARGUMENT")
            }
            types = Set(strings)
        }
        implementation.requestPermissions(types: types) { call.resolve($0) }
    }

    @objc func startTracking(_ call: CAPPluginCall) {
        let options: TrackingOptions
        do {
            options = try TrackingOptions.parse(call.options as? [String: Any] ?? [:])
        } catch let e as DrivingSensorsError {
            return call.reject(e.message, e.code)
        } catch {
            return call.reject(error.localizedDescription, "INTERNAL")
        }
        implementation.startTracking(options: options) { result in
            switch result {
            case .success(let id): call.resolve(["sessionId": id])
            case .failure(let e): call.reject(e.message, e.code)
            }
        }
    }

    @objc func stopTracking(_ call: CAPPluginCall) {
        implementation.stopTracking { call.resolve() }
    }

    @objc func getStatus(_ call: CAPPluginCall) {
        DispatchQueue.global().async {
            let s = self.implementation.status()
            var result: [String: Any] = ["active": s.active, "serviceRunning": s.serviceRunning,
                                         "inTrip": s.inTrip, "buffered": s.buffered]
            if let loc = s.lastLocation { result["lastLocation"] = loc.dictionary }
            if s.active, let id = s.sessionId { result["sessionId"] = id }
            call.resolve(result)
        }
    }

    @objc func getPluginVersion(_ call: CAPPluginCall) {
        call.resolve(["version": DrivingSensorsVersion.current, "platform": "ios"])
    }

    @objc func getLastReadings(_ call: CAPPluginCall) {
        DispatchQueue.global().async {
            call.resolve(self.implementation.lastReadings())
        }
    }

    @objc func getCurrentReadings(_ call: CAPPluginCall) {
        /// .some(nil) = absent (use default); nil = invalid.
        func number(_ key: String) -> Double?? {
            guard let raw = call.options[key], !(raw is NSNull) else { return .some(nil) }
            guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(),
                  n.doubleValue.isFinite, n.doubleValue > 0 else { return nil }
            return .some(n.doubleValue)
        }
        guard let timeout = number("timeoutMs"), let sample = number("motionSampleMs") else {
            return call.reject("timeoutMs and motionSampleMs must be positive numbers", "INVALID_ARGUMENT")
        }
        implementation.currentReadings(timeoutMs: timeout, motionSampleMs: sample) { result in
            switch result {
            case .success(let r): call.resolve(r)
            case .failure(let e): call.reject(e.message, e.code)
            }
        }
    }

    @objc func getBufferedEvents(_ call: CAPPluginCall) {
        let afterId = call.getInt("afterId") ?? 0
        let limit = call.getInt("limit") ?? 500
        guard afterId >= 0, limit >= 1 else {
            return call.reject("afterId must be >= 0 and limit >= 1", "INVALID_ARGUMENT")
        }
        DispatchQueue.global().async {
            call.resolve(["events": self.implementation.bufferedEvents(afterId: Int64(afterId), limit: limit)])
        }
    }

    @objc func ackEvents(_ call: CAPPluginCall) {
        guard let upToId = call.getInt("upToId") else {
            return call.reject("upToId is required", "INVALID_ARGUMENT")
        }
        DispatchQueue.global().async {
            self.implementation.ackEvents(upToId: Int64(upToId))
            call.resolve()
        }
    }

    // MARK: - Recording

    @objc func startRecording(_ call: CAPPluginCall) {
        let options: RecordingOptions
        do {
            options = try RecordingOptions.parse(call.options as? [String: Any] ?? [:])
        } catch let e as DrivingSensorsError {
            return call.reject(e.message, e.code)
        } catch {
            return call.reject(error.localizedDescription, "INTERNAL")
        }
        implementation.startRecording(options: options) { result in
            switch result {
            case .success(let id): call.resolve(["recordingId": id])
            case .failure(let e): call.reject(e.message, e.code)
            }
        }
    }

    @objc func stopRecording(_ call: CAPPluginCall) {
        implementation.stopRecording { call.resolve() }
    }

    @objc func getRecordingStatus(_ call: CAPPluginCall) {
        DispatchQueue.global().async { call.resolve(self.implementation.recordingStatus()) }
    }

    @objc func listRecordingChunks(_ call: CAPPluginCall) {
        let limit = call.getInt("limit") ?? 100
        guard limit >= 1 else { return call.reject("limit must be >= 1", "INVALID_ARGUMENT") }
        DispatchQueue.global().async {
            call.resolve(["chunks": self.implementation.recordingChunks(limit: limit)])
        }
    }

    @objc func getRecordingChunk(_ call: CAPPluginCall) {
        guard let id = call.getString("chunkId") else { return call.reject("chunkId is required", "INVALID_ARGUMENT") }
        DispatchQueue.global().async {
            switch self.implementation.recordingChunk(chunkId: id) {
            case .success(let r): call.resolve(r)
            case .failure(let e): call.reject(e.message, e.code)
            }
        }
    }

    // MARK: - Upload and heartbeat

    @objc func configureUpload(_ call: CAPPluginCall) {
        let options = call.options as? [String: Any] ?? [:]
        DispatchQueue.global().async {
            do {
                try self.implementation.configureUpload(options)
                call.resolve()
            } catch let e as DrivingSensorsError {
                call.reject(e.message, e.code)
            } catch {
                call.reject("Could not configure upload", "INTERNAL")
            }
        }
    }

    @objc func clearUpload(_ call: CAPPluginCall) {
        DispatchQueue.global().async {
            self.implementation.clearUpload()
            call.resolve()
        }
    }

    @objc func getUploadStatus(_ call: CAPPluginCall) {
        DispatchQueue.global().async { call.resolve(self.implementation.uploads.status()) }
    }

    @objc func uploadNow(_ call: CAPPluginCall) {
        implementation.uploads.uploadNow()
        call.resolve()
    }

    @objc func startHeartbeat(_ call: CAPPluginCall) {
        var interval: Double?
        if let raw = call.options["intervalSec"], !(raw is NSNull) {
            guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else {
                return call.reject("intervalSec must be a number", "INVALID_ARGUMENT")
            }
            interval = n.doubleValue
        }
        implementation.startHeartbeat(intervalSec: interval) { result in
            switch result {
            case .success: call.resolve()
            case .failure(let e): call.reject(e.message, e.code)
            }
        }
    }

    @objc func stopHeartbeat(_ call: CAPPluginCall) {
        implementation.stopHeartbeat { call.resolve() }
    }

    @objc func getHeartbeatStatus(_ call: CAPPluginCall) {
        DispatchQueue.global().async { call.resolve(self.implementation.heartbeat.sender.statusDictionary()) }
    }

    @objc func ackRecordingChunk(_ call: CAPPluginCall) {
        guard let id = call.getString("chunkId") else { return call.reject("chunkId is required", "INVALID_ARGUMENT") }
        DispatchQueue.global().async {
            self.implementation.ackRecordingChunk(chunkId: id)
            call.resolve()
        }
    }
}
