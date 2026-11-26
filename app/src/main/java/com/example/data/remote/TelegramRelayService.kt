package com.example.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.model.TruckWeightRecord
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "TelegramRelay"

sealed interface TelegramDispatchResult {
    data class Sent(
        val messageId: String,
        val fileId: String
    ) : TelegramDispatchResult

    data class NotConfigured(val reason: String) : TelegramDispatchResult
    data class Failed(val errorMessage: String) : TelegramDispatchResult
}

class TelegramRelayService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun dispatchRecordPhoto(
        relayUrl: String?,
        record: TruckWeightRecord,
        imageUri: Uri
    ): TelegramDispatchResult {
        val trimmedUrl = relayUrl?.trim().orEmpty()
        if (trimmedUrl.isBlank()) {
            return TelegramDispatchResult.NotConfigured(
                "Backend Telegram relay URL is not configured yet. Deploy the Cloud Function and set the endpoint in Owner Settings."
            )
        }
        if (!trimmedUrl.startsWith("https://")) {
            return TelegramDispatchResult.Failed(
                "Relay URL must use HTTPS for secure Firebase token transmission."
            )
        }

        val currentUser = Firebase.auth.currentUser
            ?: return TelegramDispatchResult.Failed("User must be signed in before dispatching to Telegram.")

        return try {
            val idToken = currentUser.getIdToken(false).await().token
                ?: return TelegramDispatchResult.Failed("Unable to acquire Firebase ID token.")

            val imageBase64 = withContext(Dispatchers.IO) {
                encodeImageAsJpegBase64(imageUri)
            } ?: return TelegramDispatchResult.Failed("Failed to read original slip image for upload.")

            val payloadJson = JSONObject().apply {
                put("recordId", record.id)
                put("vehicleNumber", record.vehicleNumber)
                put("truckWeight", record.truckWeight)
                put("weightDate", record.weightDate)
                if (record.previousWeight != null) {
                    put("previousWeight", record.previousWeight)
                } else {
                    put("previousWeight", JSONObject.NULL)
                }
                put("weightDifference", record.weightDifference)
                put("alertStatus", record.alertStatus)
                put("imageBase64", imageBase64)
                put("imageMimeType", "image/jpeg")
            }

            val body = payloadJson.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(trimmedUrl)
                .addHeader("Authorization", "Bearer $idToken")
                .post(body)
                .build()

            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    val parsed = runCatching { JSONObject(responseBody) }.getOrNull()
                    if (response.isSuccessful && parsed?.optBoolean("ok", false) == true) {
                        TelegramDispatchResult.Sent(
                            messageId = parsed.optString("telegramMessageId", ""),
                            fileId = parsed.optString("documentImageTelegramFileId", "")
                        )
                    } else {
                        val err = parsed?.optString("error")
                            ?.takeIf { it.isNotBlank() }
                            ?: "Backend returned HTTP ${response.code}"
                        TelegramDispatchResult.Failed(err)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Telegram relay dispatch error", e)
            TelegramDispatchResult.Failed(e.localizedMessage ?: "Network error contacting backend relay.")
        }
    }

    private fun encodeImageAsJpegBase64(uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
