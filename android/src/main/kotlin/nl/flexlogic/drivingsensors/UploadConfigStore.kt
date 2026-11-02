package nl.flexlogic.drivingsensors

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the device token. Behind an interface because the Keystore does not exist on the JVM. */
interface TokenCipher {
    class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(plain: ByteArray): Sealed
    fun decrypt(sealed: Sealed): ByteArray
    fun deleteKey()
}

/** AES-256-GCM key in the AndroidKeyStore. */
class KeystoreTokenCipher(private val alias: String = ALIAS) : TokenCipher {

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    private fun key(): SecretKey {
        val ks = keyStore()
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): TokenCipher.Sealed {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return TokenCipher.Sealed(c.iv, c.doFinal(plain))
    }

    override fun decrypt(sealed: TokenCipher.Sealed): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.iv))
        return c.doFinal(sealed.ciphertext)
    }

    override fun deleteKey() {
        try {
            val ks = keyStore()
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ALIAS = "driving_sensors_upload"
    }
}

/** SharedPreferences backed [UploadStore]; the token is only ever stored encrypted and never logged. */
class UploadConfigStore(context: Context, private val cipher: TokenCipher = KeystoreTokenCipher()) : UploadStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("driving_sensors_upload", Context.MODE_PRIVATE)
    private val lock = Any()
    private val generation = AtomicLong(0)

    /** Replaces config and token, resets counters and state to idle. Throws when the token cannot be encrypted. */
    fun save(settings: UploadSettings, token: String) = synchronized(lock) {
        val sealed = cipher.encrypt(token.toByteArray(Charsets.UTF_8))
        prefs.edit().clear()
            .putString("baseUrl", settings.baseUrl)
            .putString("deviceId", settings.deviceId)
            .putBoolean("uploadRecordings", settings.uploadRecordings)
            .putBoolean("uploadEvents", settings.uploadEvents)
            .putBoolean("wifiOnly", settings.wifiOnly)
            .putInt("eventBatchSize", settings.eventBatchSize)
            .putString("tokenIv", android.util.Base64.encodeToString(sealed.iv, android.util.Base64.NO_WRAP))
            .putString("tokenCt", android.util.Base64.encodeToString(sealed.ciphertext, android.util.Base64.NO_WRAP))
            .putString("state", UploadStates.IDLE)
            .commit()
        generation.incrementAndGet()
    }

    fun clear() = synchronized(lock) {
        prefs.edit().clear().commit()
        cipher.deleteKey()
        generation.incrementAndGet()
    }

    override fun generation(): Long = generation.get()

    override fun loadConfig(): UploadSettings? {
        val url = prefs.getString("baseUrl", null) ?: return null
        val id = prefs.getString("deviceId", null) ?: return null
        return UploadSettings(
            baseUrl = url,
            deviceId = id,
            uploadRecordings = prefs.getBoolean("uploadRecordings", true),
            uploadEvents = prefs.getBoolean("uploadEvents", true),
            wifiOnly = prefs.getBoolean("wifiOnly", false),
            eventBatchSize = prefs.getInt("eventBatchSize", 500),
        )
    }

    override fun loadToken(): String? = synchronized(lock) {
        val iv = prefs.getString("tokenIv", null) ?: return null
        val ct = prefs.getString("tokenCt", null) ?: return null
        try {
            String(
                cipher.decrypt(
                    TokenCipher.Sealed(
                        android.util.Base64.decode(iv, android.util.Base64.NO_WRAP),
                        android.util.Base64.decode(ct, android.util.Base64.NO_WRAP),
                    ),
                ),
                Charsets.UTF_8,
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun loadStatus(): UploadStatusData {
        fun long(k: String) = if (prefs.contains(k)) prefs.getLong(k, 0) else null
        return UploadStatusData(
            state = prefs.getString("state", UploadStates.IDLE) ?: UploadStates.IDLE,
            uploadedChunks = prefs.getLong("uploadedChunks", 0),
            uploadedEvents = prefs.getLong("uploadedEvents", 0),
            lastSuccessAt = long("lastSuccessAt"),
            lastErrorAt = long("lastErrorAt"),
            lastError = prefs.getString("lastError", null),
            lastHttpStatus = if (prefs.contains("lastHttpStatus")) prefs.getInt("lastHttpStatus", 0) else null,
            nextAttemptAt = long("nextAttemptAt"),
            backoffMs = prefs.getLong("backoffMs", 0),
        )
    }

    override fun updateStatus(f: (UploadStatusData) -> UploadStatusData): UploadStatusData = synchronized(lock) {
        // Never resurrect status after clearUpload.
        if (!prefs.contains("baseUrl")) return@synchronized UploadStatusData(state = UploadStates.DISABLED)
        val s = f(loadStatus())
        val e = prefs.edit()
            .putString("state", s.state)
            .putLong("uploadedChunks", s.uploadedChunks)
            .putLong("uploadedEvents", s.uploadedEvents)
            .putLong("backoffMs", s.backoffMs)
        fun opt(k: String, v: Long?) = if (v == null) e.remove(k) else e.putLong(k, v)
        opt("lastSuccessAt", s.lastSuccessAt)
        opt("lastErrorAt", s.lastErrorAt)
        opt("nextAttemptAt", s.nextAttemptAt)
        if (s.lastError == null) e.remove("lastError") else e.putString("lastError", s.lastError)
        if (s.lastHttpStatus == null) e.remove("lastHttpStatus") else e.putInt("lastHttpStatus", s.lastHttpStatus)
        e.apply()
        s
    }
}
