package com.example.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.example.R
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProvider
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.debug.InternalDebugSecretProvider
import com.google.firebase.appcheck.debug.internal.DebugAppCheckProvider
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import com.google.firebase.inject.Provider
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "AppCheckInit"

// App Check contract keys. These strings are defined by the Firebase SDK and
// the runtime harness, so do not rename them.
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val TOKEN_CACHE_STORE_PREFIX = "com.google.firebase.appcheck.store."
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

object AppCheckTokenManager {
    const val FALLBACK_DEFAULT_DEBUG_TOKEN = "95158850-D125-410E-A80F-3CF42093E1BB"

    private val UUID_TOKEN_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    private val debugLiteExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private val debugBackgroundExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private val debugBlockingExecutor: ExecutorService by lazy { Executors.newCachedThreadPool() }

    fun isValidDebugTokenUuid(raw: String?): Boolean {
        val candidate = raw?.trim().orEmpty()
        return candidate.isNotEmpty() && UUID_TOKEN_REGEX.matches(candidate)
    }

    fun normalizeToken(raw: String): String {
        return raw.trim()
    }

    fun readDefaultDebugToken(context: Context): String {
        return try {
            val fromRes = context.getString(R.string.default_app_check_debug_token).trim()
            if (isValidDebugTokenUuid(fromRes)) normalizeToken(fromRes) else FALLBACK_DEFAULT_DEBUG_TOKEN
        } catch (_: Exception) {
            FALLBACK_DEFAULT_DEBUG_TOKEN
        }
    }

    fun resolvePreferredDebugToken(
        intentToken: String?,
        defaultToken: String
    ): String {
        val cleanIntent = intentToken?.trim().orEmpty()
        if (cleanIntent.isNotEmpty()) {
            return if (isValidDebugTokenUuid(cleanIntent)) normalizeToken(cleanIntent) else cleanIntent
        }
        return if (isValidDebugTokenUuid(defaultToken)) {
            normalizeToken(defaultToken)
        } else {
            FALLBACK_DEFAULT_DEBUG_TOKEN
        }
    }

    fun getActiveDebugToken(context: Context): String {
        return readDefaultDebugToken(context)
    }

    internal fun clearCachedTokenAndResetState(context: Context, persistenceKey: String) {
        try {
            context.getSharedPreferences("$TOKEN_CACHE_STORE_PREFIX$persistenceKey", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to clear cached App Check token store", e)
        }
        try {
            val appCheck = FirebaseAppCheck.getInstance()
            runCatching {
                appCheck.javaClass.getMethod("resetAppCheckState").invoke(appCheck)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to reset in-memory App Check state", e)
        }
    }

    private fun installFreshDebugProvider(debugToken: String) {
        val factory = object : AppCheckProviderFactory {
            override fun create(firebaseApp: FirebaseApp): AppCheckProvider {
                val secretProvider = Provider {
                    InternalDebugSecretProvider { debugToken }
                }
                return DebugAppCheckProvider(
                    firebaseApp,
                    secretProvider,
                    debugLiteExecutor,
                    debugBackgroundExecutor,
                    debugBlockingExecutor
                )
            }
        }
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory)
    }

    fun applyExactDebugToken(
        context: Context,
        exactToken: String
    ): Result<String> {
        val clean = exactToken.trim()
        if (!isValidDebugTokenUuid(clean)) {
            return Result.failure(IllegalArgumentException("Invalid debug token UUID."))
        }
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            val persistenceKey = FirebaseApp.getInstance().persistenceKey
            clearCachedTokenAndResetState(context, persistenceKey)
            context.getSharedPreferences("$DEBUG_STORE_PREFIX$persistenceKey", Context.MODE_PRIVATE)
                .edit()
                .putString(DEBUG_SECRET_KEY, clean)
                .commit()

            if (BuildConfig.DEBUG) {
                installFreshDebugProvider(clean)
            }
            Result.success(clean)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun trySwitchToRecaptcha(context: Context): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            val persistenceKey = FirebaseApp.getInstance().persistenceKey
            clearCachedTokenAndResetState(context, persistenceKey)
            installRecaptchaAppCheck(context)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to switch to RecaptchaAppCheckProviderFactory", e)
            false
        }
    }

    fun getAppCheckDiagnosticSummary(context: Context): String {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            val options = FirebaseApp.getInstance().options
            val projectId = options.projectId ?: "unknown"
            val appId = options.applicationId
            val activeToken = getActiveDebugToken(context)
            "Project: $projectId • App ID: $appId • Token: $activeToken"
        } catch (_: Exception) {
            "Token: ${getActiveDebugToken(context)}"
        }
    }

    suspend fun refreshAppCheckToken(forceRefresh: Boolean = true): Result<String> {
        return suspendCancellableCoroutine { cont ->
            try {
                FirebaseAppCheck.getInstance()
                    .getAppCheckToken(forceRefresh)
                    .addOnSuccessListener { token ->
                        if (cont.isActive) {
                            cont.resume(Result.success(token.token))
                        }
                    }
                    .addOnFailureListener { error ->
                        if (cont.isActive) {
                            cont.resume(Result.failure(error))
                        }
                    }
            } catch (e: Exception) {
                if (cont.isActive) {
                    cont.resume(Result.failure(e))
                }
            }
        }
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        if (BuildConfig.DEBUG) {
            val intentToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)?.trim()
            val persistenceKey = FirebaseApp.getInstance().persistenceKey
            val sdkPrefs = context.getSharedPreferences(
                "$DEBUG_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )
            val existingSdkSecret = sdkPrefs.getString(DEBUG_SECRET_KEY, null)?.trim()
            val defaultToken = AppCheckTokenManager.readDefaultDebugToken(context)

            val resolvedToken = AppCheckTokenManager.resolvePreferredDebugToken(
                intentToken = intentToken,
                defaultToken = defaultToken
            )

            if (!resolvedToken.equals(existingSdkSecret, ignoreCase = false)) {
                AppCheckTokenManager.clearCachedTokenAndResetState(context, persistenceKey)
            }

            // Synchronous commit() guarantees DebugAppCheckProviderFactory reads this secret immediately
            sdkPrefs.edit()
                .putString(DEBUG_SECRET_KEY, resolvedToken)
                .commit()

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed with configured debug token.")
        } else {
            installRecaptchaAppCheck(context)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

private fun installRecaptchaAppCheck(context: Context): Boolean {
    val resId = context.resources.getIdentifier(
        RECAPTCHA_SITE_KEY_RES,
        "string",
        context.packageName
    )
    if (resId == 0) {
        Log.e(TAG, "recaptcha_site_key string resource not found in package.")
        return false
    }
    val siteKey = context.getString(resId)
    if (siteKey.isBlank()) {
        Log.e(TAG, "recaptcha_site_key resource string is blank.")
        return false
    }
    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
    )
    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
    return true
}
