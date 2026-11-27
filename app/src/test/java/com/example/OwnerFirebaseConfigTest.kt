package com.example

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.core.EncryptedPayload
import com.example.core.FirebaseConfigSnippetParser
import com.example.core.FirebaseConfigValidator
import com.example.core.OwnerFirebaseConfig
import com.example.core.OwnerFirebaseConfigStore
import com.example.core.SecureCipherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class OwnerFirebaseConfigTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private class JvmAesGcmCipherProvider : SecureCipherProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val random = SecureRandom()

        override fun encrypt(plaintextUtf8: ByteArray): EncryptedPayload {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plaintextUtf8)
            return EncryptedPayload(
                ivBase64 = Base64.getEncoder().encodeToString(iv),
                cipherBase64 = Base64.getEncoder().encodeToString(encrypted)
            )
        }

        override fun decrypt(payload: EncryptedPayload): ByteArray {
            val iv = Base64.getDecoder().decode(payload.ivBase64)
            val bytes = Base64.getDecoder().decode(payload.cipherBase64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(bytes)
        }
    }

    @Test
    fun `snippet parser extracts all firebase config fields from js snippet`() {
        val snippet = """
            const firebaseConfig = {
              apiKey: "SYNTHETIC_TEST_KEY_000001",
              authDomain: "sample-weighbridge.firebaseapp.com",
              databaseURL: "https://sample-weighbridge-default-rtdb.firebaseio.com",
              projectId: "sample-weighbridge",
              storageBucket: "sample-weighbridge.firebasestorage.app",
              messagingSenderId: "123456789012",
              appId: "1:123456789012:web:abcdef123456",
              measurementId: "G-TEST12345"
            };
        """.trimIndent()

        val parsed = FirebaseConfigSnippetParser.parse(snippet)
        assertNotNull(parsed)
        assertEquals("SYNTHETIC_TEST_KEY_000001", parsed?.apiKey)
        assertEquals("sample-weighbridge.firebaseapp.com", parsed?.authDomain)
        assertEquals("https://sample-weighbridge-default-rtdb.firebaseio.com", parsed?.databaseUrl)
        assertEquals("sample-weighbridge", parsed?.projectId)
        assertEquals("sample-weighbridge.firebasestorage.app", parsed?.storageBucket)
        assertEquals("123456789012", parsed?.messagingSenderId)
        assertEquals("1:123456789012:web:abcdef123456", parsed?.appId)
        assertEquals("G-TEST12345", parsed?.measurementId)
    }

    @Test
    fun `validator rejects invalid fields and accepts well-formed config`() {
        val valid = OwnerFirebaseConfig(
            apiKey = "SYNTHETIC_TEST_KEY_000001",
            authDomain = "sample-weighbridge.firebaseapp.com",
            databaseUrl = "https://sample-weighbridge-default-rtdb.firebaseio.com",
            projectId = "sample-weighbridge",
            storageBucket = "sample-weighbridge.firebasestorage.app",
            messagingSenderId = "123456789012",
            appId = "1:123456789012:web:abcdef123456",
            measurementId = "G-TEST12345"
        )
        assertNull(FirebaseConfigValidator.validate(valid))

        assertNotNull(FirebaseConfigValidator.validate(valid.copy(projectId = "Bad_Project")))
        assertNotNull(FirebaseConfigValidator.validate(valid.copy(databaseUrl = "http://insecure.example.com")))
        assertNotNull(FirebaseConfigValidator.validate(valid.copy(messagingSenderId = "abc123")))
        assertNotNull(FirebaseConfigValidator.validate(valid.copy(apiKey = "short")))
    }

    @Test
    fun `store encrypts preferences on disk and supports save load clear`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefsFile = File(tmpFolder.root, "owner_test.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { prefsFile }
            )
            val store = OwnerFirebaseConfigStore(dataStore, JvmAesGcmCipherProvider())

            val input = OwnerFirebaseConfig(
                apiKey = "SYNTHETIC_TEST_KEY_000001",
                authDomain = "sample-weighbridge.firebaseapp.com",
                databaseUrl = "https://sample-weighbridge-default-rtdb.firebaseio.com",
                projectId = "sample-weighbridge",
                storageBucket = "sample-weighbridge.firebasestorage.app",
                messagingSenderId = "123456789012",
                appId = "1:123456789012:web:abcdef123456",
                measurementId = "G-TEST12345"
            )

            val saveResult = store.saveConfig(input)
            assertTrue(saveResult.isSuccess)

            // Raw stored DataStore entry must not contain plaintext API key
            val rawPrefs = dataStore.data.first()
            val storedCipher = rawPrefs[stringPreferencesKey("enc_firebase_cfg_v1")].orEmpty()
            assertTrue(storedCipher.isNotBlank())
            assertFalse(storedCipher.contains("SYNTHETIC_TEST_KEY_000001"))

            // Decrypted read matches original fields
            val loaded = store.configFlow.first()
            assertNotNull(loaded)
            assertEquals("sample-weighbridge", loaded?.projectId)
            assertEquals("SYNTHETIC_TEST_KEY_000001", loaded?.apiKey)

            // Clearing removes configuration
            assertTrue(store.clearConfig().isSuccess)
            assertNull(store.configFlow.first())
        } finally {
            scope.cancel()
        }
    }
}
