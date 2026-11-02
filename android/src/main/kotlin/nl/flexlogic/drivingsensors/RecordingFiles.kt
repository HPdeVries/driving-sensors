package nl.flexlogic.drivingsensors

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream

/** Metadata of a finished chunk; serialised to `<chunkId>.json`. */
class RecordingChunkMeta(
    val chunkId: String,
    val recordingId: String,
    val seq: Int,
    val startTs: Long,
    val endTs: Long,
    val bytes: Long,
    val counts: LongArray,
) {
    fun toJson(): String = buildString {
        append("{\"chunkId\":\"").append(chunkId).append("\",\"recordingId\":\"").append(recordingId)
        append("\",\"seq\":").append(seq).append(",\"startTs\":").append(startTs)
        append(",\"endTs\":").append(endTs).append(",\"bytes\":").append(bytes).append(",\"counts\":{")
        for (i in RecordingSensors.NAMES.indices) {
            if (i > 0) append(',')
            append('"').append(RecordingSensors.NAMES[i]).append("\":").append(counts[i])
        }
        append("}}")
    }
}

/** Pure file-level helpers for recording chunks (no Android dependencies). */
object RecordingFiles {
    const val PART_SUFFIX = ".csv.part"
    const val GZ_SUFFIX = ".csv.gz"
    const val JSON_SUFFIX = ".json"

    fun chunkId(recordingId: String, seq: Int): String = recordingId + "-" + seq.toString().padStart(6, '0')

    /** Splits `<recordingId>-<6 digit seq>`; null when malformed. */
    fun parseChunkId(chunkId: String): Pair<String, Int>? {
        val i = chunkId.lastIndexOf('-')
        if (i <= 0 || chunkId.length - i - 1 < 6) return null
        val seq = chunkId.substring(i + 1).toIntOrNull() ?: return null
        return chunkId.substring(0, i) to seq
    }

    fun isSafeChunkId(chunkId: String): Boolean =
        chunkId.isNotEmpty() && chunkId.length <= 128 &&
            chunkId.all { it.isLetterOrDigit() || it == '-' } && parseChunkId(chunkId) != null

    fun partFile(dir: File, chunkId: String) = File(dir, chunkId + PART_SUFFIX)
    fun gzFile(dir: File, chunkId: String) = File(dir, chunkId + GZ_SUFFIX)
    fun jsonFile(dir: File, chunkId: String) = File(dir, chunkId + JSON_SUFFIX)

    fun totalBytes(dir: File): Long = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    fun isOverCap(totalBytes: Long, maxStorageMb: Int): Boolean = totalBytes > maxStorageMb.toLong() * 1024 * 1024

    /** Next free seq for [recordingId]: max(persisted, 1 + highest seq present on disk). */
    fun nextSeq(dir: File, recordingId: String, persisted: Int): Int {
        var next = persisted
        dir.listFiles()?.forEach { f ->
            val id = f.name.substringBefore('.')
            val parsed = parseChunkId(id) ?: return@forEach
            if (parsed.first == recordingId && parsed.second + 1 > next) next = parsed.second + 1
        }
        return next
    }

    /**
     * Gzips [part] to `<chunkId>.csv.gz`, writes `<chunkId>.json`, deletes the part file.
     * Lines that are not complete samples (e.g. truncated by a crash) are dropped. Returns null
     * (and deletes the part) when it holds no samples.
     */
    fun finalizePart(part: File): RecordingChunkMeta? {
        val dir = part.parentFile ?: File(".")
        val chunkId = part.name.removeSuffix(PART_SUFFIX)
        val (recordingId, seq) = parseChunkId(chunkId) ?: return null
        val gz = gzFile(dir, chunkId)
        val gzTmp = File(dir, gz.name + ".tmp")
        val counts = LongArray(RecordingSensors.NAMES.size)
        var startTs = Long.MAX_VALUE
        var endTs = Long.MIN_VALUE
        var samples = 0L
        BufferedReader(InputStreamReader(FileInputStream(part), Charsets.UTF_8)).use { reader ->
            BufferedWriter(OutputStreamWriter(GZIPOutputStream(FileOutputStream(gzTmp), 16 * 1024), Charsets.UTF_8)).use { writer ->
                writer.write(RecordingCsv.HEADER)
                writer.write("\n")
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == RecordingCsv.HEADER) continue
                    val sensor = sampleSensor(line) ?: continue
                    val t = line.substring(0, line.indexOf(',')).toLong()
                    counts[sensor]++
                    samples++
                    if (t < startTs) startTs = t
                    if (t > endTs) endTs = t
                    writer.write(line)
                    writer.write("\n")
                }
            }
        }
        if (samples == 0L) {
            gzTmp.delete()
            part.delete()
            return null
        }
        if (!gzTmp.renameTo(gz)) throw IOException("cannot rename ${gzTmp.name}")
        val meta = RecordingChunkMeta(chunkId, recordingId, seq, startTs, endTs, gz.length(), counts)
        val json = jsonFile(dir, chunkId)
        val jsonTmp = File(dir, json.name + ".tmp")
        jsonTmp.writeText(meta.toJson(), Charsets.UTF_8)
        if (!jsonTmp.renameTo(json)) throw IOException("cannot rename ${jsonTmp.name}")
        part.delete()
        return meta
    }

    /** Index of the sensor of a complete sample line, or null when the line is malformed. */
    private fun sampleSensor(line: String): Int? {
        var commas = 0
        for (c in line) if (c == ',') commas++
        if (commas != 6) return null
        val first = line.indexOf(',')
        if (line.substring(0, first).toLongOrNull() == null) return null
        val second = line.indexOf(',', first + 1)
        val idx = RecordingSensors.NAMES.indexOf(line.substring(first + 1, second))
        return if (idx >= 0) idx else null
    }

    /** Finalizes every leftover `.part` in [dir] (also removes stale `.tmp` files). */
    fun finalizeLeftovers(dir: File): List<RecordingChunkMeta> {
        val files = dir.listFiles() ?: return emptyList()
        val out = ArrayList<RecordingChunkMeta>()
        for (f in files) {
            if (f.name.endsWith(".tmp")) f.delete()
        }
        for (f in files.filter { it.name.endsWith(PART_SUFFIX) }.sortedBy { it.name }) {
            try {
                finalizePart(f)?.let { out.add(it) }
            } catch (e: Exception) {
                // Leave the part for the next attempt.
            }
        }
        return out
    }
}
