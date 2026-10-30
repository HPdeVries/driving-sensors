import Foundation
import SQLite3

struct BufferedEvent {
    var ts: Int64
    var type: String
    var sessionId: String
    var lat: Double?
    var lon: Double?
    var speedMps: Double?
    var accuracyM: Double?
    var peakMps2: Double?
}

struct EventStoreError: Error { let message: String }

private let sqliteTransient = unsafeBitCast(OpaquePointer(bitPattern: -1), to: sqlite3_destructor_type.self)

/// SQLite event buffer on the system sqlite3 API; all access is serialised on one queue.
final class EventStore: @unchecked Sendable {
    static let maxLimit = 1000

    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "driving-sensors.store")

    init(path: String) throws {
        guard sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            let msg = db.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            sqlite3_close(db)
            throw EventStoreError(message: msg)
        }
        let ddl = """
        CREATE TABLE IF NOT EXISTS events (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          ts INTEGER NOT NULL,
          type TEXT NOT NULL,
          session_id TEXT NOT NULL,
          lat REAL, lon REAL, speed_mps REAL, accuracy_m REAL, peak_mps2 REAL
        )
        """
        guard sqlite3_exec(db, ddl, nil, nil, nil) == SQLITE_OK else {
            throw EventStoreError(message: String(cString: sqlite3_errmsg(db)))
        }
    }

    deinit { sqlite3_close(db) }

    /// Application Support/driving_sensors.sqlite, excluded from backup.
    static func openDefault() throws -> EventStore {
        let dir = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        var url = dir.appendingPathComponent("driving_sensors.sqlite")
        let store = try EventStore(path: url.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return store
    }

    @discardableResult
    func insert(_ e: BufferedEvent) -> Int64? {
        queue.sync {
            var stmt: OpaquePointer?
            let sql = "INSERT INTO events (ts, type, session_id, lat, lon, speed_mps, accuracy_m, peak_mps2) VALUES (?,?,?,?,?,?,?,?)"
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, e.ts)
            sqlite3_bind_text(stmt, 2, e.type, -1, sqliteTransient)
            sqlite3_bind_text(stmt, 3, e.sessionId, -1, sqliteTransient)
            bind(stmt, 4, e.lat)
            bind(stmt, 5, e.lon)
            bind(stmt, 6, e.speedMps)
            bind(stmt, 7, e.accuracyM)
            bind(stmt, 8, e.peakMps2)
            guard sqlite3_step(stmt) == SQLITE_DONE else { return nil }
            return sqlite3_last_insert_rowid(db)
        }
    }

    /// Events with id > afterId in ascending order; null columns are omitted.
    func read(afterId: Int64 = 0, limit: Int = 500) -> [[String: Any]] {
        let capped = max(0, min(limit, EventStore.maxLimit))
        return queue.sync {
            var stmt: OpaquePointer?
            let sql = "SELECT id, ts, type, session_id, lat, lon, speed_mps, accuracy_m, peak_mps2 FROM events WHERE id > ? ORDER BY id LIMIT ?"
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, afterId)
            sqlite3_bind_int(stmt, 2, Int32(capped))
            var rows: [[String: Any]] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                var row: [String: Any] = [
                    "id": sqlite3_column_int64(stmt, 0),
                    "ts": sqlite3_column_int64(stmt, 1),
                    "type": String(cString: sqlite3_column_text(stmt, 2)),
                    "sessionId": String(cString: sqlite3_column_text(stmt, 3))
                ]
                let optional: [(Int32, String)] = [(4, "lat"), (5, "lon"), (6, "speedMps"), (7, "accuracyM"), (8, "peakMps2")]
                for (col, key) in optional where sqlite3_column_type(stmt, col) != SQLITE_NULL {
                    row[key] = sqlite3_column_double(stmt, col)
                }
                rows.append(row)
            }
            return rows
        }
    }

    func ack(upToId: Int64) {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "DELETE FROM events WHERE id <= ?", -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_int64(stmt, 1, upToId)
            sqlite3_step(stmt)
        }
    }

    func count() -> Int {
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM events", -1, &stmt, nil) == SQLITE_OK else { return 0 }
            defer { sqlite3_finalize(stmt) }
            return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int64(stmt, 0)) : 0
        }
    }

    private func bind(_ stmt: OpaquePointer?, _ idx: Int32, _ v: Double?) {
        if let v { sqlite3_bind_double(stmt, idx, v) } else { sqlite3_bind_null(stmt, idx) }
    }
}
