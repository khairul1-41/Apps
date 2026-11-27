package com.example.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.FirebaseOptions
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "OwnerConfigStore"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "twg_owner_firebase_cfg_aes_v1"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128

private val Context.ownerSecureDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "owner_secure_firebase_prefs"
)

@JsonClass(generateAdapter = true)
data class OwnerFirebaseConfig(
    val apiKey: String = "",
    val authDomain: String = "",
    val databaseUrl: String = "",
    val projectId: String = "",
    val storageBucket: String = "",
    val messagingSenderId: String = "",
    val appId: String = "",
    val measurementId: String = "",
    val updatedAtEpochMs: Long = 0L
) {
    fun normalized(): OwnerFirebaseConfig = copy(
        apiKey = apiKey.trim(),
        authDomain = authDomain.trim(),
        databaseUrl = databaseUrl.trim(),
        projectId = projectId.trim(),
        storageBucket = storageBucket.trim(),
        messagingSenderId = messagingSenderId.trim(),
        appId = appId.trim(),
        measurementId = measurementId.trim()
    )

    fun toJsonString(): String = MOSHI_ADAPTER.toJson(this)

    fun maskedApiKey(): String {
        val clean = apiKey.trim()
        if (clean.length <= 8) return "••••••••"
        return "${clean.take(4)}••••••••${clean.takeLast(4)}"
    }

    fun toFirebaseOptions(): FirebaseOptions {
        val clean = normalized()
        val builder = FirebaseOptions.Builder()
            .setApiKey(clean.apiKey)
            .setApplicationId(clean.appId)
            .setProjectId(clean.projectId)
        if (clean.databaseUrl.isNotEmpty()) builder.setDatabaseUrl(clean.databaseUrl)
        if (clean.storageBucket.isNotEmpty()) builder.setStorageBucket(clean.storageBucket)
        if (clean.messagingSenderId.isNotEmpty()) builder.setGcmSenderId(clean.messagingSenderId)
        if (clean.measurementId.isNotEmpty()) builder.setGaTrackingId(clean.measurementId)
        return builder.build()
    }

    companion object {
        private val MOSHI: Moshi = Moshi.Builder().build()
        private val MOSHI_ADAPTER = MOSHI.adapter(OwnerFirebaseConfig::class.java)

        fun fromJsonString(raw: String): OwnerFirebaseConfig {
            return MOSHI_ADAPTER.fromJson(raw)
                ?: throw IllegalArgumentException("Invalid OwnerFirebaseConfig JSON payload")
        }
    }
}

object FirebaseConfigValidator {
    private val PROJECT_ID_REGEX = Regex("^[a-z0-9][a-z0-9-]{2,62}$")
    private val HOST_REGEX = Regex("^[a-zA-Z0-9.-]+$")
    private val DIGITS_REGEX = Regex("^\\d+$")
    private val UNSAFE_CONTROL_REGEX = Regex("[\\u0000-\\u001F\\u007F<>]")

    fun validate(config: OwnerFirebaseConfig): String? {
        val c = config.normalized()
        val allFields = listOf(
            c.apiKey, c.authDomain, c.databaseUrl, c.projectId,
            c.storageBucket, c.messagingSenderId, c.appId, c.measurementId
        )
        if (allFields.any { UNSAFE_CONTROL_REGEX.containsMatchIn(it) }) {
            return "Configuration fields contain unsupported control or bracket characters."
        }
        if (c.apiKey.length < 10 || c.apiKey.any { it.isWhitespace() }) {
            return "Please enter a valid Firebase API Key (at least 10 characters, no spaces)."
        }
        if (!PROJECT_ID_REGEX.matches(c.projectId)) {
            return "Project ID must use lowercase letters, digits, or hyphens (e.g. my-project-123)."
        }
        if (c.appId.isBlank() || c.appId.any { it.isWhitespace() } || !c.appId.contains(":")) {
            return "Please enter a valid Firebase App ID (e.g. 1:123456:web:... or 1:123456:android:...)."
        }
        if (c.authDomain.isNotEmpty() && !HOST_REGEX.matches(c.authDomain)) {
            return "Auth Domain should be a valid hostname (e.g. project-id.firebaseapp.com)."
        }
        if (c.databaseUrl.isNotEmpty() && (!c.databaseUrl.startsWith("https://") || c.databaseUrl.any { it.isWhitespace() })) {
            return "Database URL must start with https:// and contain no spaces."
        }
        if (c.storageBucket.isNotEmpty() && c.storageBucket.any { it.isWhitespace() }) {
            return "Storage Bucket must not contain spaces."
        }
        if (c.messagingSenderId.isNotEmpty() && !DIGITS_REGEX.matches(c.messagingSenderId)) {
            return "Messaging Sender ID must contain digits only."
        }
        if (c.measurementId.isNotEmpty() && c.measurementId.any { it.isWhitespace() }) {
            return "Measurement ID must not contain spaces."
        }
        return null
    }
}

object FirebaseConfigSnippetParser {
    private val FIELD_KEYS = listOf(
        "apiKey",
        "authDomain",
        "databaseURL",
        "databaseUrl",
        "projectId",
        "storageBucket",
        "messagingSenderId",
        "appId",
        "measurementId"
    )

    fun parse(rawInput: String): OwnerFirebaseConfig? {
        val text = rawInput.trim()
        if (text.isEmpty()) return null

        // Attempt direct JSON object parsing first if user pasted JSON
        if (text.startsWith("{") && text.endsWith("}")) {
            val normalizedJsonText = text.replace("\"databaseURL\"", "\"databaseUrl\"")
            val fromJson = runCatching { OwnerFirebaseConfig.fromJsonString(normalizedJsonText) }.getOrNull()
            if (fromJson != null &&
                (fromJson.apiKey.isNotBlank() || fromJson.projectId.isNotBlank() || fromJson.appId.isNotBlank())
            ) {
                return fromJson.normalized()
            }
        }

        fun extractValue(vararg keys: String): String {
            for (key in keys) {
                val pattern = Regex(
                    """["']?${Regex.escape(key)}["']?\s*:\s*["']([^"'\r\n]+)["']"""
                )
                val match = pattern.find(text)
                if (match != null) {
                    return match.groupValues[1].trim()
                }
            }
            return ""
        }

        val extracted = OwnerFirebaseConfig(
            apiKey = extractValue("apiKey"),
            authDomain = extractValue("authDomain"),
            databaseUrl = extractValue("databaseURL", "databaseUrl"),
            projectId = extractValue("projectId"),
            storageBucket = extractValue("storageBucket"),
            messagingSenderId = extractValue("messagingSenderId"),
            appId = extractValue("appId"),
            measurementId = extractValue("measurementId")
        ).normalized()

        val foundAny = extracted.apiKey.isNotEmpty() ||
            extracted.projectId.isNotEmpty() ||
            extracted.appId.isNotEmpty()
        return if (foundAny && FIELD_KEYS.isNotEmpty()) extracted else null
    }
}

data class EncryptedPayload(
    val ivBase64: String,
    val cipherBase64: String
)

interface SecureCipherProvider {
    fun encrypt(plaintextUtf8: ByteArray): EncryptedPayload
    fun decrypt(payload: EncryptedPayload): ByteArray
}

class AndroidKeystoreCipherProvider : SecureCipherProvider {

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun encrypt(plaintextUtf8: ByteArray): EncryptedPayload {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val cipherBytes = cipher.doFinal(plaintextUtf8)
        return EncryptedPayload(
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            cipherBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        )
    }

    override fun decrypt(payload: EncryptedPayload): ByteArray {
        val iv = Base64.decode(payload.ivBase64, Base64.NO_WRAP)
        val cipherBytes = Base64.decode(payload.cipherBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
        return cipher.doFinal(cipherBytes)
    }
}

class OwnerFirebaseConfigStore(
    private val dataStore: DataStore<Preferences>,
    private val cipherProvider: SecureCipherProvider = AndroidKeystoreCipherProvider()
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.ownerSecureDataStore,
        cipherProvider = AndroidKeystoreCipherProvider()
    )

    val configFlow: Flow<OwnerFirebaseConfig?> = dataStore.data
        .map { prefs ->
            val cipherB64 = prefs[KEY_ENCRYPTED_CONFIG]
            val ivB64 = prefs[KEY_CONFIG_IV]
            if (cipherB64.isNullOrBlank() || ivB64.isNullOrBlank()) {
                null
            } else {
                try {
                    val decrypted = cipherProvider.decrypt(EncryptedPayload(ivB64, cipherB64))
                    OwnerFirebaseConfig.fromJsonString(String(decrypted, Charsets.UTF_8))
                } catch (e: Exception) {
                    runCatching { Log.e(TAG, "Failed to decrypt stored owner Firebase configuration.", e) }
                    null
                }
            }
        }
        .catch { e ->
            if (e is CancellationException) throw e
            runCatching { Log.e(TAG, "Failed reading encrypted owner preferences.", e) }
            emit(null)
        }

    suspend fun saveConfig(input: OwnerFirebaseConfig): Result<OwnerFirebaseConfig> {
        val normalized = input.normalized().copy(updatedAtEpochMs = System.currentTimeMillis())
        val validationError = FirebaseConfigValidator.validate(normalized)
        if (validationError != null) {
            return Result.failure(IllegalArgumentException(validationError))
        }

        return try {
            withContext(Dispatchers.IO) {
                val plaintext = normalized.toJsonString().toByteArray(Charsets.UTF_8)
                val encrypted = cipherProvider.encrypt(plaintext)
                dataStore.edit { prefs ->
                    prefs[KEY_ENCRYPTED_CONFIG] = encrypted.cipherBase64
                    prefs[KEY_CONFIG_IV] = encrypted.ivBase64
                }
            }
            Result.success(normalized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Failed saving encrypted owner Firebase configuration.", e) }
            Result.failure(e)
        }
    }

    suspend fun clearConfig(): Result<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                dataStore.edit { prefs ->
                    prefs.remove(KEY_ENCRYPTED_CONFIG)
                    prefs.remove(KEY_CONFIG_IV)
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Failed clearing owner Firebase configuration.", e) }
            Result.failure(e)
        }
    }

    companion object {
        private val KEY_ENCRYPTED_CONFIG = stringPreferencesKey("enc_firebase_cfg_v1")
        private val KEY_CONFIG_IV = stringPreferencesKey("enc_firebase_cfg_iv_v1")
    }
}
