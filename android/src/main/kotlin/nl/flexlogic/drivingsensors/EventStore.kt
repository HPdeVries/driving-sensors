package nl.flexlogic.drivingsensors

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.concurrent.Executors

/** SQLite event buffer. All database work runs on a single background thread, in submission order. */
class EventStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "driving-sensors-db").apply { isDaemon = true }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ts INTEGER NOT NULL, " +
                "type TEXT NOT NULL, " +
                "session_id TEXT NOT NULL, " +
                "lat REAL, lon REAL, speed_mps REAL, accuracy_m REAL, peak_mps2 REAL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Runs [work] on the database thread and delivers the result there. */
    fun <T> async(work: EventStore.() -> T, done: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching { work() }
            result.exceptionOrNull()?.let { Log.e(TAG, "database operation failed", it) }
            done(result)
        }
    }

    fun insert(record: EventRecord) {
        async({ insertNow(record) }) { }
    }

    /** Invokes [done] after every previously submitted write has finished. */
    fun whenIdle(done: () -> Unit) = async({ }) { done() }

    private fun insertNow(r: EventRecord) {
        val values = ContentValues().apply {
            put("ts", r.ts)
            put("type", r.type)
            put("session_id", r.sessionId)
            r.lat?.let { put("lat", it) }
            r.lon?.let { put("lon", it) }
            r.speedMps?.let { put("speed_mps", it) }
            r.accuracyM?.let { put("accuracy_m", it) }
            r.peakMps2?.let { put("peak_mps2", it) }
        }
        writableDatabase.insertOrThrow("events", null, values)
    }

    fun queryNow(afterId: Long, limit: Int): List<StoredEvent> {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        val out = ArrayList<StoredEvent>()
        readableDatabase.rawQuery(
            "SELECT id, ts, type, session_id, lat, lon, speed_mps, accuracy_m, peak_mps2 " +
                "FROM events WHERE id > ? ORDER BY id LIMIT ?",
            arrayOf(afterId.toString(), capped.toString()),
        ).use { c ->
            fun dbl(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
            while (c.moveToNext()) {
                out.add(
                    StoredEvent(
                        c.getLong(0),
                        EventRecord(c.getLong(1), c.getString(2), c.getString(3), dbl(4), dbl(5), dbl(6), dbl(7), dbl(8)),
                    ),
                )
            }
        }
        return out
    }

    fun ackNow(upToId: Long) {
        writableDatabase.delete("events", "id <= ?", arrayOf(upToId.toString()))
    }

    fun countNow(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

    companion object {
        private const val TAG = "DrivingSensors"
        const val DB_NAME = "driving_sensors.db"
        private const val DB_VERSION = 1
        const val MAX_LIMIT = 1000

        @Volatile
        private var instance: EventStore? = null

        fun get(context: Context): EventStore =
            instance ?: synchronized(this) {
                instance ?: EventStore(context.applicationContext).also { instance = it }
            }
    }
}
