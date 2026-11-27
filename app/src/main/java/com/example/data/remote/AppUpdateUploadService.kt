package com.example.data.remote

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.example.core.OwnerFirebaseConfig
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import com.google.firebase.Firebase
import com.google.firebase.app
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "AppUpdateUpload"

class DriveTokenExpiredException(message: String) : IllegalStateException(message)

object AppUpdateUrlValidator {
    private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val DRIVE_FILE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{10,128}$")

    fun isSafeHttpsDownloadUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.length !in 12..1024) return false
        if (!trimmed.startsWith("https://")) return false
        if (trimmed.any { it.isWhitespace() || it.code < 32 || it.code == 127 }) return false

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        val host = uri.host?.trim()?.lowercase(Locale.US).orEmpty()
        if (host.isEmpty() || !host.contains(".")) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false

        val ipv4Match = IPV4_REGEX.matchEntire(host)
        if (ipv4Match != null) {
            val a = ipv4Match.groupValues[1].toIntOrNull() ?: return false
            val b = ipv4Match.groupValues[2].toIntOrNull() ?: return false
            if (a == 127 || a == 10 || a == 0) return false
            if (a == 169 && b == 254) return false
            if (a == 192 && b == 168) return false
            if (a == 172 && b in 16..31) return false
        }
        return true
    }

    fun isSafeGoogleApiUrl(rawUrl: String): Boolean {
        if (!isSafeHttpsDownloadUrl(rawUrl)) return false
        val host = runCatching { URI(rawUrl.trim()).host?.trim()?.lowercase(Locale.US) }.getOrNull()
            ?: return false
        return host == "www.googleapis.com" || host.endsWith(".googleapis.com")
    }

    fun isValidDriveFileId(fileId: String): Boolean =
        DRIVE_FILE_ID_REGEX.matches(fileId.trim())

    fun buildGoogleDriveDirectDownloadUrl(fileId: String): String {
        val cleanId = fileId.trim()
        require(isValidDriveFileId(cleanId)) { "Invalid Google Drive file ID." }
        return "https://drive.google.com/uc?export=download&id=$cleanId"
    }

    fun isValidApkFileName(rawName: String): Boolean {
        val trimmed = rawName.trim()
        return trimmed.length in 5..128 &&
            trimmed.lowercase(Locale.US).endsWith(".apk") &&
            !trimmed.contains("/") &&
            !trimmed.contains("\\") &&
            !trimmed.contains("..")
    }

    fun formatByteSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.2f MB", mb)
        } else {
            String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        }
    }
}

data class ApkInspectionResult(
    val localFile: File,
    val fileName: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val fileSizeBytes: Long,
    val sha256Checksum: String,
    val matchesAppPackage: Boolean
)

class AppUpdateUploadService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun inspectPickedApk(uri: Uri): Result<ApkInspectionResult> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val originalName = queryDisplayName(uri)
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .take(96)
                .let { if (it.lowercase(Locale.US).endsWith(".apk")) it else "${it.ifBlank { "app-update" }}.apk" }

            val stagedFile = File(updatesDir, "staged_release.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(stagedFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        totalBytes += read
                    }
                }
            } ?: return@withContext Result.failure(IllegalArgumentException("Unable to open the selected file."))

            if (totalBytes < 1024L) {
                stagedFile.delete()
                return@withContext Result.failure(IllegalArgumentException("Selected APK file is empty or too small."))
            }

            // Verify ZIP/APK magic header (PK\x03\x04)
            val magic = ByteArray(4)
            FileInputStream(stagedFile).use { it.read(magic) }
            val isZipArchive = magic[0] == 0x50.toByte() &&
                magic[1] == 0x4B.toByte() &&
                magic[2] == 0x03.toByte() &&
                magic[3] == 0x04.toByte()
            if (!isZipArchive) {
                stagedFile.delete()
                return@withContext Result.failure(IllegalArgumentException("Selected file is not a valid Android APK package."))
            }

            val packageInfo = readArchivePackageInfo(stagedFile.absolutePath)
                ?: run {
                    stagedFile.delete()
                    return@withContext Result.failure(IllegalArgumentException("Could not parse AndroidManifest inside the selected APK."))
                }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val parsedCode = PackageInfoCompat.getLongVersionCode(packageInfo).coerceAtLeast(1L)
            val parsedName = packageInfo.versionName?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0"
            val pkgName = packageInfo.packageName.trim()

            Result.success(
                ApkInspectionResult(
                    localFile = stagedFile,
                    fileName = originalName,
                    packageName = pkgName,
                    versionCode = parsedCode,
                    versionName = parsedName,
                    fileSizeBytes = totalBytes,
                    sha256Checksum = sha256,
                    matchesAppPackage = pkgName == context.packageName
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "APK inspection failed", e)
            Result.failure(e)
        }
    }

    suspend fun uploadApkToBackendOrStorage(
        apk: ApkInspectionResult,
        versionCode: Long,
        savedOwnerConfig: OwnerFirebaseConfig?,
        telegramRelayUrl: String?,
        onProgress: (Int) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val currentUser = Firebase.auth.currentUser
            ?: return@withContext Result.failure(IllegalStateException("Google Sign-In required before uploading an APK."))

        val idToken = try {
            currentUser.getIdToken(false).await().token
                ?: return@withContext Result.failure(IllegalStateException("Unable to acquire Firebase ID token."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }

        val errors = mutableListOf<String>()

        // Path 1: Cloud Function signed upload URL if Cloud Functions endpoint is configured
        val relayEndpoint = telegramRelayUrl?.trim().orEmpty()
        if (relayEndpoint.startsWith("https://") && relayEndpoint.contains("sendWeighbridgeToTelegram")) {
            val uploadSignEndpoint = relayEndpoint.replace("sendWeighbridgeToTelegram", "requestApkUploadUrl")
            if (AppUpdateUrlValidator.isSafeHttpsDownloadUrl(uploadSignEndpoint)) {
                val fnResult = tryUploadViaSignedFunctionUrl(
                    signEndpoint = uploadSignEndpoint,
                    idToken = idToken,
                    apk = apk,
                    versionCode = versionCode,
                    onProgress = onProgress
                )
                if (fnResult.isSuccess) return@withContext fnResult
                errors.add("Cloud Function upload: ${fnResult.exceptionOrNull()?.message}")
            }
        }

        // Path 2: Firebase Storage REST upload using configured bucket (Owner config first, then default app bucket)
        val candidateBuckets = listOfNotNull(
            savedOwnerConfig?.storageBucket?.trim()?.takeIf { it.isNotEmpty() },
            runCatching { Firebase.app.options.storageBucket?.trim() }.getOrNull()?.takeIf { !it.isNullOrEmpty() }
        ).distinct()

        for (bucket in candidateBuckets) {
            val restResult = tryUploadViaFirebaseStorageRest(
                bucket = bucket,
                idToken = idToken,
                apk = apk,
                versionCode = versionCode,
                onProgress = onProgress
            )
            if (restResult.isSuccess) return@withContext restResult
            errors.add("Storage ($bucket): ${restResult.exceptionOrNull()?.message}")
        }

        val summary = if (errors.isEmpty()) {
            "No storage bucket configured."
        } else {
            errors.joinToString(" | ")
        }
        Result.failure(
            IllegalStateException(
                "Cloud Storage is unavailable ($summary). Authorize Google Drive access or paste a valid HTTPS APK download link below."
            )
        )
    }

    suspend fun uploadApkToGoogleDrive(
        apk: ApkInspectionResult,
        versionCode: Long,
        versionName: String,
        accessToken: String,
        onProgress: (Int) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val token = accessToken.trim()
        if (token.isEmpty()) {
            return@withContext Result.failure(DriveTokenExpiredException("Google Drive permission is required."))
        }
        if (!apk.localFile.exists() || apk.localFile.length() < 1024L) {
            return@withContext Result.failure(IllegalStateException("Selected APK file is no longer cached. Please select it again."))
        }

        try {
            onProgress(1)
            val safeFileName = "TruckWeightGuard-v${versionName.trim()}-b${versionCode}-${apk.fileName}"
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .take(110)
                .let { if (it.lowercase(Locale.US).endsWith(".apk")) it else "$it.apk" }

            val metadataJson = JSONObject().apply {
                put("name", safeFileName)
                put("mimeType", APK_MIME_TYPE)
                put("description", "TruckWeight Guard APK update v$versionName (Build $versionCode)")
            }.toString()

            // Step 1: Initiate resumable upload session on Google Drive API v3
            val initRequest = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,webContentLink")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Upload-Content-Type", APK_MIME_TYPE)
                .addHeader("X-Upload-Content-Length", apk.fileSizeBytes.toString())
                .post(metadataJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val uploadSessionUrl = client.newCall(initRequest).execute().use { resp ->
                if (resp.code == 401) {
                    return@withContext Result.failure(
                        DriveTokenExpiredException("Google Drive authorization expired. Please grant Drive access again.")
                    )
                }
                if (!resp.isSuccessful) {
                    val err = parseGoogleApiError(resp.body?.string().orEmpty(), resp.code)
                    return@withContext Result.failure(IllegalStateException("Google Drive upload start failed: $err"))
                }
                val location = resp.header("Location")?.trim().orEmpty()
                if (!AppUpdateUrlValidator.isSafeGoogleApiUrl(location)) {
                    return@withContext Result.failure(IllegalStateException("Invalid Google Drive upload session URL returned."))
                }
                location
            }

            // Step 2: Stream APK binary to Google Drive with live progress
            onProgress(2)
            val putRequest = Request.Builder()
                .url(uploadSessionUrl)
                .put(
                    ProgressFileRequestBody(
                        file = apk.localFile,
                        mediaType = APK_MIME_TYPE.toMediaType(),
                        onProgress = { pct -> onProgress((2 + (pct * 90) / 100).coerceIn(2, 92)) }
                    )
                )
                .build()

            val uploadedFileId = client.newCall(putRequest).execute().use { putResp ->
                val raw = putResp.body?.string().orEmpty()
                if (putResp.code == 401) {
                    return@withContext Result.failure(
                        DriveTokenExpiredException("Google Drive authorization expired during upload. Please retry.")
                    )
                }
                if (!putResp.isSuccessful) {
                    val err = parseGoogleApiError(raw, putResp.code)
                    return@withContext Result.failure(IllegalStateException("Google Drive file transfer failed: $err"))
                }
                val id = runCatching { JSONObject(raw).optString("id", "").trim() }.getOrDefault("")
                if (!AppUpdateUrlValidator.isValidDriveFileId(id)) {
                    return@withContext Result.failure(IllegalStateException("Google Drive did not return a valid file ID."))
                }
                id
            }

            // Step 3: Grant read-only 'anyone with link' permission ONLY on this uploaded APK file
            onProgress(95)
            val permBody = JSONObject().apply {
                put("role", "reader")
                put("type", "anyone")
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val permRequest = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$uploadedFileId/permissions")
                .addHeader("Authorization", "Bearer $token")
                .post(permBody)
                .build()

            client.newCall(permRequest).execute().use { permResp ->
                val raw = permResp.body?.string().orEmpty()
                if (!permResp.isSuccessful) {
                    val err = parseGoogleApiError(raw, permResp.code)
                    return@withContext Result.failure(
                        IllegalStateException(
                            "APK uploaded to Google Drive, but link sharing permission failed ($err). Check your Google Account Drive sharing policy."
                        )
                    )
                }
            }

            onProgress(100)
            Result.success(AppUpdateUrlValidator.buildGoogleDriveDirectDownloadUrl(uploadedFileId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Google Drive APK upload failed", e)
            Result.failure(e)
        }
    }

    private fun parseGoogleApiError(rawBody: String, statusCode: Int): String {
        val parsed = runCatching {
            val json = JSONObject(rawBody)
            json.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: json.optString("error_description").takeIf { it.isNotBlank() }
        }.getOrNull()
        return parsed ?: "HTTP $statusCode"
    }

    private fun tryUploadViaSignedFunctionUrl(
        signEndpoint: String,
        idToken: String,
        apk: ApkInspectionResult,
        versionCode: Long,
        onProgress: (Int) -> Unit
    ): Result<String> {
        return try {
            val signBody = JSONObject().apply {
                put("fileName", apk.fileName)
                put("versionCode", versionCode)
                put("contentType", APK_MIME_TYPE)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val signRequest = Request.Builder()
                .url(signEndpoint)
                .addHeader("Authorization", "Bearer $idToken")
                .post(signBody)
                .build()

            val (uploadUrl, downloadUrl) = client.newCall(signRequest).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (!resp.isSuccessful || json?.optBoolean("ok", false) != true) {
                    val err = json?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                    return Result.failure(IllegalStateException(err))
                }
                val uUrl = json.optString("uploadUrl", "")
                val dUrl = json.optString("downloadUrl", "")
                if (!AppUpdateUrlValidator.isSafeHttpsDownloadUrl(uUrl) ||
                    !AppUpdateUrlValidator.isSafeHttpsDownloadUrl(dUrl)
                ) {
                    return Result.failure(IllegalStateException("Invalid signed upload URL returned."))
                }
                uUrl to dUrl
            }

            val putReq = Request.Builder()
                .url(uploadUrl)
                .put(ProgressFileRequestBody(apk.localFile, APK_MIME_TYPE.toMediaType(), onProgress))
                .build()

            client.newCall(putReq).execute().use { putResp ->
                if (!putResp.isSuccessful) {
                    return Result.failure(IllegalStateException("Upload rejected (HTTP ${putResp.code})"))
                }
            }
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun tryUploadViaFirebaseStorageRest(
        bucket: String,
        idToken: String,
        apk: ApkInspectionResult,
        versionCode: Long,
        onProgress: (Int) -> Unit
    ): Result<String> {
        return try {
            val safeName = apk.fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
            val objectPath = "app_updates/v${versionCode}_${System.currentTimeMillis()}_$safeName"
            val encodedPath = URLEncoder.encode(objectPath, "UTF-8").replace("+", "%20")
            val uploadUrl = "https://firebasestorage.googleapis.com/v0/b/$bucket/o?uploadType=media&name=$encodedPath"
            if (!AppUpdateUrlValidator.isSafeHttpsDownloadUrl(uploadUrl)) {
                return Result.failure(IllegalArgumentException("Invalid storage bucket format."))
            }

            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Firebase $idToken")
                .post(ProgressFileRequestBody(apk.localFile, APK_MIME_TYPE.toMediaType(), onProgress))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (!response.isSuccessful) {
                    val errMsg = json?.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: "HTTP ${response.code}"
                    return Result.failure(IllegalStateException(errMsg))
                }
                val token = json?.optString("downloadTokens")
                    ?.split(",")
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                val downloadUrl = if (token.isNotEmpty()) {
                    "https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encodedPath?alt=media&token=$token"
                } else {
                    "https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encodedPath?alt=media"
                }
                Result.success(downloadUrl)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "TruckWeightGuard-update.apk"
    }

    private fun readArchivePackageInfo(apkPath: String): PackageInfo? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkPath, 0)
        }
    }

    private class ProgressFileRequestBody(
        private val file: File,
        private val mediaType: MediaType,
        private val onProgress: (Int) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1L)
            val buffer = ByteArray(16 * 1024)
            var uploaded = 0L
            var lastPct = -1
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    uploaded += read
                    val pct = ((uploaded * 100L) / total).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }
        }
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val GOOGLE_DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        fun buildGoogleDriveAuthorizationRequest(): AuthorizationRequest =
            AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(GOOGLE_DRIVE_FILE_SCOPE)))
                .build()
    }
}
