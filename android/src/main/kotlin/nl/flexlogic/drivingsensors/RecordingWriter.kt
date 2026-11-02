package nl.flexlogic.drivingsensors

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Buffers samples in memory and appends them to `<chunkId>.csv.part` once per [flushPeriodMs] on
 * its own single thread. Rolls over every [chunkMs] (wall clock since the chunk was opened) and
 * stops itself when the directory exceeds [maxBytes].
 */
class RecordingWriter(
    private val dir: File,
    private val recordingId: String,
    private val chunkMs: Long,
    private val maxBytes: Long,
    startSeq: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val flushPeriodMs: Long = 1000,
    private val onChunkOpened: (Int) -> Unit = {},
    private val onStorageFull: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val onChunkFinalized: () -> Unit = {},
) {
    private val lock = Any()
    private val buffer = StringBuilder(64 * 1024)
    private var accepting = true

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "driving-sensors-writer").apply { isDaemon = false }
    }

    // Executor-thread state.
    private var nextSeq = startSeq
    private var out: BufferedOutputStream? = null
    private var currentPart: File? = null
    private var chunkStart = 0L

    fun start() {
        dir.mkdirs()
        executor.scheduleWithFixedDelay({
            try {
                flush()
            } catch (t: Throwable) {
                fail(t)
            }
        }, flushPeriodMs, flushPeriodMs, TimeUnit.MILLISECONDS)
    }

    /** Thread safe; called from sensor callbacks. NaN = unknown. */
    fun add(
        tMs: Long,
        sensor: Int,
        x: Double,
        y: Double = Double.NaN,
        z: Double = Double.NaN,
        v4: Double = Double.NaN,
        v5: Double = Double.NaN,
    ) {
        synchronized(lock) {
            if (!accepting) return
            RecordingCsv.appendSample(buffer, tMs, sensor, x, y, z, v4, v5)
        }
    }

    /** Stops accepting samples, flushes and finalizes the current chunk, then runs [onDone] on the writer thread. */
    fun stop(onDone: () -> Unit) {
        synchronized(lock) { accepting = false }
        try {
            executor.execute {
                try {
                    flushData()
                    closeChunk()
                } catch (t: Throwable) {
                    onError(t)
                }
                try {
                    onDone()
                } finally {
                    executor.shutdown()
                }
            }
        } catch (e: RejectedExecutionException) {
            // Already shut down (storage full / error): everything is finalized.
            onDone()
        }
    }

    private fun fail(t: Throwable) {
        synchronized(lock) { accepting = false }
        try {
            closeChunk()
        } catch (_: Throwable) {
        }
        onError(t)
        executor.shutdown()
    }

    private fun flushData() {
        val data = synchronized(lock) {
            if (buffer.isEmpty()) null else buffer.toString().also { buffer.setLength(0) }
        } ?: return
        val stream = out ?: openChunk()
        stream.write(data.toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    private fun flush() {
        flushData()
        if (out != null && now() - chunkStart >= chunkMs) closeChunk()
        if (RecordingFiles.totalBytes(dir) > maxBytes) {
            synchronized(lock) { accepting = false }
            closeChunk()
            executor.shutdown()
            onStorageFull()
        }
    }

    private fun openChunk(): BufferedOutputStream {
        val seq = nextSeq++
        val part = RecordingFiles.partFile(dir, RecordingFiles.chunkId(recordingId, seq))
        val stream = BufferedOutputStream(FileOutputStream(part, true), 32 * 1024)
        stream.write((RecordingCsv.HEADER + "\n").toByteArray(Charsets.UTF_8))
        out = stream
        currentPart = part
        chunkStart = now()
        onChunkOpened(seq)
        return stream
    }

    private fun closeChunk() {
        val stream = out ?: return
        val part = currentPart
        out = null
        currentPart = null
        try {
            stream.close()
        } finally {
            if (part != null && RecordingFiles.finalizePart(part) != null) {
                try {
                    onChunkFinalized()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
