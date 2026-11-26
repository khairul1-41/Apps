package com.example.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.example.data.model.OcrExtractionResult
import com.example.domain.WeightVerificationEngine
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

private const val TAG = "WeighbridgeOcr"

class WeighbridgeOcrService(private val context: Context) {

    private val ocrSchema = Schema.obj(
        properties = mapOf(
            "vehicleNumber" to Schema.string(
                description = "Extracted truck/vehicle registration number (e.g. BUG T-11-1633, DMT 24-6422, DMT-13-0806). Empty string if unreadable."
            ),
            "truckWeightKg" to Schema.double(
                description = "Final verified empty/tare/truck weight in KG. Null if uncertain or missing.",
                nullable = true
            ),
            "weightDate" to Schema.string(
                description = "Weighing date from the slip (prefer date matching the selected truck/tare weighing). Empty string if missing."
            ),
            "sourceLabel" to Schema.string(
                description = "Exact document label used (e.g. Tare Weight, Tare, First Weight, Second Weight, Truck Weight, Vehicle Weight)."
            ),
            "confidence" to Schema.double(
                description = "Confidence score from 0.0 to 1.0 for vehicleNumber + truckWeightKg + weightDate accuracy."
            ),
            "requiresManualConfirmation" to Schema.boolean(
                description = "True if any field is partially obscured, ambiguous, missing, or if multiple weighing entries caused uncertainty."
            ),
            "reasoning" to Schema.string(
                description = "Short explanation of which slip fields were seen and why truckWeightKg was chosen."
            ),
            "grossWeightKg" to Schema.double(
                description = "Gross weight in KG if printed on the slip, otherwise null.",
                nullable = true
            ),
            "netWeightKg" to Schema.double(
                description = "Net cargo weight in KG if printed on the slip, otherwise null.",
                nullable = true
            ),
            "firstWeightKg" to Schema.double(
                description = "First weight in KG if printed on the slip, otherwise null.",
                nullable = true
            ),
            "secondWeightKg" to Schema.double(
                description = "Second/final weighing in KG if printed on the slip, otherwise null.",
                nullable = true
            )
        ),
        optionalProperties = listOf(
            "truckWeightKg",
            "grossWeightKg",
            "netWeightKg",
            "firstWeightKg",
            "secondWeightKg"
        )
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = ocrSchema
            }
        )
    }

    suspend fun analyzeDocument(
        imageUri: Uri,
        strictSecondWeightPriority: Boolean,
        minConfidenceThreshold: Double
    ): OcrExtractionResult {
        val bitmap = withContext(Dispatchers.IO) {
            loadBitmapBounded(imageUri, maxDim = 2048)
        } ?: throw IllegalStateException("Unable to decode the selected weighbridge image.")

        val priorityInstruction = if (strictSecondWeightPriority) {
            "CRITICAL MULTI-WEIGHING RULE: If the slip lists multiple weighing entries for the same truck/tare weighing (such as First Weight AND Second Weight), select ONLY the second/final weighing value for truckWeightKg, and NEVER select the first weighing value when a second/final weighing value is present. However, if Second Weight is blank/empty/unprinted and First Weight is the empty truck weight, select First Weight and set requiresManualConfirmation=true if any second row is obscured."
        } else {
            "If multiple truck weighing rows are present, prefer the latest/final truck weighing value unless it is blank."
        }

        val promptText = """
            You are an industrial weighbridge slip OCR verification engine. Carefully inspect this weighbridge ticket / weight report image and extract structured fields.

            FIELDS TO RECOGNIZE:
            A. Vehicle Number: Look for labels "Truck No", "Tare No", "Vehicle No", "Vehicle". Extract the exact plate/registration code (e.g., "BUG T-11-1633", "DMT 24-6422", "DMT -13-0806"). Do NOT pick Challan No, Weight ID, or Mobile numbers.
            B. Truck Weight (in KG): Look for labels "Truck Weight", "Tare Weight", "Tare", "Vehicle Weight", "First Weight", or "Second Weight".
               - NEVER confuse "Gross Weight" (loaded weight) or "Net Weight" (cargo weight) with Truck/Tare Weight!
               - $priorityInstruction
               - Remove commas and "Kg" units to return a clean numeric value in KG (e.g. "6,050 Kg" -> 6050.0, "7,160 Kg" -> 7160.0, "10270" -> 10270.0).
            C. Weight Date: Look for labels "Date", "Weight Date", "Weighing Date", "Tare Time", "Transaction Date". Prefer the date directly tied to the selected Truck/Tare weighing if multiple dates appear.

            CONFIDENCE & SAFETY:
            - Never guess or fabricate obscured digits. If the target weight or vehicle number is partially covered, blurry, or missing, return null for truckWeightKg (or empty string for vehicleNumber) and set requiresManualConfirmation = true.
        """.trimIndent()

        return try {
            val response = withTimeout(60_000L) {
                model.generateContent(
                    content {
                        image(bitmap)
                        text(promptText)
                    }
                )
            }
            val jsonText = response.text
                ?: throw IllegalStateException("OCR model returned an empty response.")
            parseOcrJson(jsonText, minConfidenceThreshold)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "OCR analysis timed out after 60s", e)
            throw IllegalStateException("Document OCR timed out after 60 seconds. Please retry or crop closer to the weight table.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OCR extraction failed", e)
            throw e
        }
    }

    private fun parseOcrJson(jsonText: String, minConfidenceThreshold: Double): OcrExtractionResult {
        val json = JSONObject(jsonText)
        val rawVehicle = json.optString("vehicleNumber", "").trim()
        val vehicleDisplay = if (rawVehicle.isNotBlank()) {
            WeightVerificationEngine.normalizeVehicleDisplay(rawVehicle)
        } else {
            ""
        }

        val truckWeight = if (json.isNull("truckWeightKg")) null else json.optDouble("truckWeightKg").takeIf { !it.isNaN() && it > 0.0 }
        val grossWeight = if (json.isNull("grossWeightKg")) null else json.optDouble("grossWeightKg").takeIf { !it.isNaN() && it > 0.0 }
        val netWeight = if (json.isNull("netWeightKg")) null else json.optDouble("netWeightKg").takeIf { !it.isNaN() && it > 0.0 }
        val firstWeight = if (json.isNull("firstWeightKg")) null else json.optDouble("firstWeightKg").takeIf { !it.isNaN() && it > 0.0 }
        val secondWeight = if (json.isNull("secondWeightKg")) null else json.optDouble("secondWeightKg").takeIf { !it.isNaN() && it > 0.0 }

        val weightDate = json.optString("weightDate", "").trim()
        val sourceLabel = json.optString("sourceLabel", "Tare Weight").trim().ifEmpty { "Tare Weight" }
        val confidence = json.optDouble("confidence", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 1.0) }
        val modelFlaggedConfirm = json.optBoolean("requiresManualConfirmation", false)
        val reasoning = json.optString("reasoning", "").trim()

        val needsConfirm = modelFlaggedConfirm ||
            confidence < minConfidenceThreshold ||
            vehicleDisplay.isBlank() ||
            truckWeight == null ||
            weightDate.isBlank()

        return OcrExtractionResult(
            vehicleNumber = vehicleDisplay,
            truckWeightKg = truckWeight,
            weightDate = weightDate,
            sourceLabel = sourceLabel,
            confidence = confidence,
            requiresManualConfirmation = needsConfirm,
            reasoning = reasoning,
            grossWeightKg = grossWeight,
            netWeightKg = netWeight,
            firstWeightKg = firstWeight,
            secondWeightKg = secondWeight
        )
    }

    suspend fun copyUriToEditableCache(sourceUri: Uri): Uri = withContext(Dispatchers.IO) {
        val bitmap = loadBitmapBounded(sourceUri, maxDim = 2400)
            ?: throw IllegalStateException("Could not load selected image.")
        saveBitmapToSlipCache(bitmap)
    }

    suspend fun rotateSlipImage(sourceUri: Uri, degrees: Float): Uri = withContext(Dispatchers.IO) {
        val bitmap = loadBitmapBounded(sourceUri, maxDim = 2400)
            ?: throw IllegalStateException("Could not decode image for rotation.")
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        saveBitmapToSlipCache(rotated)
    }

    suspend fun cropSlipImage(sourceUri: Uri, normalizedCrop: RectF): Uri = withContext(Dispatchers.IO) {
        val bitmap = loadBitmapBounded(sourceUri, maxDim = 2400)
            ?: throw IllegalStateException("Could not decode image for cropping.")
        val left = (normalizedCrop.left.coerceIn(0f, 0.95f) * bitmap.width).toInt()
        val top = (normalizedCrop.top.coerceIn(0f, 0.95f) * bitmap.height).toInt()
        val right = (normalizedCrop.right.coerceIn(0.05f, 1f) * bitmap.width).toInt()
        val bottom = (normalizedCrop.bottom.coerceIn(0.05f, 1f) * bitmap.height).toInt()
        val width = max(32, right - left)
        val height = max(32, bottom - top)
        val safeWidth = min(width, bitmap.width - left)
        val safeHeight = min(height, bitmap.height - top)
        val cropped = Bitmap.createBitmap(bitmap, left, top, safeWidth, safeHeight)
        saveBitmapToSlipCache(cropped)
    }

    fun loadBitmapBounded(uri: Uri, maxDim: Int = 2048): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        if (origW <= 0 || origH <= 0) return null

        var inSampleSize = 1
        while (origW / inSampleSize > maxDim * 2 || origH / inSampleSize > maxDim * 2) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val exifRotation = readExifRotationDegrees(uri)
        return if (exifRotation != 0f) {
            val matrix = Matrix().apply { postRotate(exifRotation) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }
    }

    private fun readExifRotationDegrees(uri: Uri): Float {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }
    }

    private fun saveBitmapToSlipCache(bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, "slips").apply { mkdirs() }
        val file = File(dir, "slip_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        return Uri.fromFile(file)
    }
}
