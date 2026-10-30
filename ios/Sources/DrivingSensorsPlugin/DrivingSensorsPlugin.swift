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
        CAPPluginMethod(name: "ackEvents", returnType: CAPPluginReturnPromise)
    ]
    private let implementation = DrivingSensors()

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
            var result: [String: Any] = ["active": s.active, "inTrip": s.inTrip, "buffered": s.buffered]
            if s.active, let id = s.sessionId { result["sessionId"] = id }
            call.resolve(result)
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
}
