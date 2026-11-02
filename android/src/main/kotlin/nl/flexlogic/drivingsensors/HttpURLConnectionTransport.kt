package nl.flexlogic.drivingsensors

import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** HTTPS-only POST over HttpURLConnection; the body is streamed with a fixed length (no buffering). */
class HttpURLConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpTransport {

    private class CountingSink : OutputStream() {
        var count = 0L
        override fun write(b: Int) {
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
        }
    }

    override fun post(url: String, headers: Map<String, String>, body: (OutputStream) -> Unit): Int {
        if (!url.startsWith("https://", ignoreCase = true)) throw IOException("only https is allowed")
        val length = CountingSink().also { body(it) }.count
        val conn = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("not an https connection")
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setFixedLengthStreamingMode(length)
            conn.outputStream.use { body(it) }
            val code = conn.responseCode
            drain(conn, code)
            return code
        } finally {
            conn.disconnect()
        }
    }

    private fun drain(conn: HttpURLConnection, code: Int) {
        try {
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            stream?.use { s ->
                val buf = ByteArray(2048)
                var total = 0
                while (total < 64 * 1024) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
        } catch (_: IOException) {
        }
    }
}
