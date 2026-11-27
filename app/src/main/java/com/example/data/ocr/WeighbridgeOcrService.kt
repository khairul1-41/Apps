package com.example.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.example.core.AppCheckTokenManager
import com.example.data.model.OcrExtractionResult
import com.example.data.model.SlipFormatTemplate
import com.example.domain.WeightVerificationEngine
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
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
                description = "Final verified EMPTY/TARE truck weight in KG (never Gross loaded weight or Net cargo weight). Null if uncertain or missing.",
                nullable = true
            ),
            "tareWeightKg" to Schema.double(
                description = "Numeric value explicitly labeled 'Tare Weight' or 'Tare' in KG if printed on the slip, otherwise null.",
                nullable = true
            ),
            "weightDate" to Schema.string(
                description = "Calendar date directly tied to the selected empty/tare weighing row (e.g. '22/11/26', '16/11/2026', '2026-11-22'), without clock time. Empty string if missing."
            ),
            "tareDate" to Schema.string(
                description = "Calendar date directly above/next to 'Tare Weight' or in 'Tare Time' (without clock time) if printed, otherwise empty string."
            ),
            "sourceLabel" to Schema.string(
                description = "Exact document label used (e.g. Tare Weight, Tare, First Weight, Second Weight, Truck Weight)."
            ),
            "confidence" to Schema.double(
                description = "Confidence score from 0.0 to 1.0 for vehicleNumber + truckWeightKg + weightDate accuracy."
            ),
            "requiresManualConfirmation" to Schema.boolean(
                description = "True if any target field is obscured, ambiguous, missing, or uncertain."
            ),
            "reasoning" to Schema.string(
                description = "Short explanation of the slip template matched and why truckWeightKg and weightDate were chosen."
            ),
            "grossWeightKg" to Schema.double(
                description = "Gross loaded weight in KG if printed on the slip, otherwise null.",
                nullable = true
            ),
            "netWeightKg" to Schema.double(
                description = "Net cargo weight in KG if printed on the slip, otherwise null.",
                nullable = true
            ),
            "firstWeightKg" to Schema.double(
                description = "First Weight in KG if printed on the slip, otherwise null.",
                nullable = true
            ),
            "firstWeightDate" to Schema.string(
                description = "Transaction Date on the First Weight row (without time) if printed, otherwise empty string."
            ),
            "secondWeightKg" to Schema.double(
                description = "Second Weight in KG if printed on the slip, otherwise null.",
                nullable = true
            ),
            "secondWeightDate" to Schema.string(
                description = "Transaction Date on the Second Weight row (without time) if printed, otherwise empty string."
            )
        ),
        optionalProperties = listOf(
            "truckWeightKg",
            "tareWeightKg",
            "tareDate",
            "grossWeightKg",
            "netWeightKg",
            "firstWeightKg",
            "firstWeightDate",
            "secondWeightKg",
            "secondWeightDate"
        )
    )

    private val primaryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = PRIMARY_OCR_MODEL,
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = ocrSchema
            }
        )
    }

    private val fallbackModel by lazy {
        Firebase.ai.generativeModel(
            modelName = FALLBACK_OCR_MODEL,
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
        minConfidenceThreshold: Double,
        customTemplates: List<SlipFormatTemplate> = emptyList()
    ): OcrExtractionResult {
        val bitmap = withContext(Dispatchers.IO) {
            loadBitmapBounded(imageUri, maxDim = 2048)
        } ?: throw IllegalStateException("Unable to decode the selected weighbridge image.")

        val promptText = buildPromptText(
            strictSecondWeightPriority = strictSecondWeightPriority,
            customTemplates = customTemplates
        )

        return try {
            val response = withTimeout(60_000L) {
                try {
                    executeOcrWithFallback(bitmap, promptText)
                } catch (e: Exception) {
                    if (isAppCheckError(e)) {
                        Log.w(TAG, "Debug App Check failed; attempting clean case-variant and Recaptcha fallbacks.", e)
                        val activeToken = AppCheckTokenManager.getActiveDebugToken(context)
                        val lowerToken = activeToken.lowercase(java.util.Locale.US)
                        val upperToken = activeToken.uppercase(java.util.Locale.US)
                        val alternateCaseToken = when {
                            activeToken != lowerToken -> lowerToken
                            activeToken != upperToken -> upperToken
                            else -> null
                        }

                        var recovered = false
                        var recoveryError: Throwable? = null

                        // 1. Cleanly test alternate UUID casing on a brand-new DebugAppCheckProvider (fresh RetryManager)
                        if (alternateCaseToken != null &&
                            AppCheckTokenManager.applyExactDebugToken(
                                context = context,
                                exactToken = alternateCaseToken
                            ).isSuccess
                        ) {
                            val altRefresh = AppCheckTokenManager.refreshAppCheckToken(forceRefresh = true)
                            if (altRefresh.isSuccess) {
                                recovered = true
                            } else {
                                recoveryError = altRefresh.exceptionOrNull()
                            }
                        }

                        // 2. Fallback to Recaptcha App Check once if debug token exchange still failed
                        if (!recovered && AppCheckTokenManager.trySwitchToRecaptcha(context)) {
                            val recaptchaRefresh = AppCheckTokenManager.refreshAppCheckToken(forceRefresh = true)
                            if (recaptchaRefresh.isSuccess) {
                                recovered = true
                            } else {
                                recoveryError = recoveryError ?: recaptchaRefresh.exceptionOrNull()
                            }
                        }

                        if (!recovered) {
                            // Restore fresh DebugAppCheckProvider so RetryManager is clean for subsequent user retries
                            AppCheckTokenManager.applyExactDebugToken(
                                context = context,
                                exactToken = activeToken
                            )
                            val summary = AppCheckTokenManager.getAppCheckDiagnosticSummary(context)
                            val reason = recoveryError?.localizedMessage
                                ?: e.localizedMessage
                                ?: "App attestation failed."
                            throw IllegalStateException(
                                "Firebase App Check token rejected ($reason).\n\n$summary\n\nRegister this token in Firebase Console -> App Check -> Apps for this App ID.",
                                e
                            )
                        }
                        executeOcrWithFallback(bitmap, promptText)
                    } else {
                        throw e
                    }
                }
            }
            val jsonText = response.text
                ?: throw IllegalStateException("OCR model returned an empty response.")
            parseOcrJson(jsonText, minConfidenceThreshold, strictSecondWeightPriority)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "OCR analysis timed out after 60s", e)
            throw IllegalStateException("Document OCR timed out after 60 seconds. Please retry or crop closer to the weight table.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OCR extraction failed", e)
            if (isAppCheckError(e)) {
                val summary = AppCheckTokenManager.getAppCheckDiagnosticSummary(context)
                val baseMsg = e.localizedMessage ?: "Firebase App Check token is invalid."
                if (!baseMsg.contains("App ID:", ignoreCase = true)) {
                    throw IllegalStateException(
                        "$baseMsg\n\n$summary",
                        e
                    )
                }
            }
            throw e
        }
    }

    private suspend fun executeOcrWithFallback(bitmap: Bitmap, promptText: String) =
        run {
            val attempts = listOf(
                PRIMARY_OCR_MODEL to primaryModel,
                FALLBACK_OCR_MODEL to fallbackModel,
                PRIMARY_OCR_MODEL to primaryModel
            )
            var lastError: Exception? = null
            for ((index, pair) in attempts.withIndex()) {
                val (modelName, model) = pair
                try {
                    return@run model.generateContent(
                        content {
                            image(bitmap)
                            text(promptText)
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    val isLastAttempt = index == attempts.lastIndex
                    if (!isLastAttempt && isTransientDemandError(e)) {
                        val backoffMs = if (index == 0) 1000L else 1800L
                        Log.w(
                            TAG,
                            "Transient capacity error on $modelName (${e.localizedMessage}); switching/retrying in ${backoffMs}ms."
                        )
                        delay(backoffMs)
                    } else {
                        throw e
                    }
                }
            }
            throw (lastError ?: IllegalStateException("OCR extraction failed."))
        }

    private fun parseOcrJson(
        jsonText: String,
        minConfidenceThreshold: Double,
        strictSecondWeightPriority: Boolean
    ): OcrExtractionResult {
        val json = JSONObject(jsonText)
        val rawVehicle = json.optString("vehicleNumber", "").trim()
        val vehicleDisplay = if (rawVehicle.isNotBlank()) {
            WeightVerificationEngine.normalizeVehicleDisplay(rawVehicle)
        } else {
            ""
        }

        val rawTruckWeight = if (json.isNull("truckWeightKg")) null else json.optDouble("truckWeightKg").takeIf { !it.isNaN() && it > 0.0 }
        val tareWeight = if (json.isNull("tareWeightKg")) null else json.optDouble("tareWeightKg").takeIf { !it.isNaN() && it > 0.0 }
        val grossWeight = if (json.isNull("grossWeightKg")) null else json.optDouble("grossWeightKg").takeIf { !it.isNaN() && it > 0.0 }
        val netWeight = if (json.isNull("netWeightKg")) null else json.optDouble("netWeightKg").takeIf { !it.isNaN() && it > 0.0 }
        val firstWeight = if (json.isNull("firstWeightKg")) null else json.optDouble("firstWeightKg").takeIf { !it.isNaN() && it > 0.0 }
        val secondWeight = if (json.isNull("secondWeightKg")) null else json.optDouble("secondWeightKg").takeIf { !it.isNaN() && it > 0.0 }

        val rawWeightDate = json.optString("weightDate", "").trim()
        val rawTareDate = json.optString("tareDate", "").trim()
        val rawFirstDate = json.optString("firstWeightDate", "").trim()
        val rawSecondDate = json.optString("secondWeightDate", "").trim()
        val rawSourceLabel = json.optString("sourceLabel", "Tare Weight").trim().ifEmpty { "Tare Weight" }
        val confidence = json.optDouble("confidence", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 1.0) }
        val modelFlaggedConfirm = json.optBoolean("requiresManualConfirmation", false)
        val reasoning = json.optString("reasoning", "").trim()

        val resolved = resolveTruckWeightSelection(
            modelTruckWeightKg = rawTruckWeight,
            tareWeightKg = tareWeight,
            grossWeightKg = grossWeight,
            netWeightKg = netWeight,
            firstWeightKg = firstWeight,
            secondWeightKg = secondWeight,
            modelWeightDate = rawWeightDate,
            tareDate = rawTareDate,
            firstWeightDate = rawFirstDate,
            secondWeightDate = rawSecondDate,
            modelSourceLabel = rawSourceLabel,
            strictSecondWeightPriority = strictSecondWeightPriority
        )

        val needsConfirm = modelFlaggedConfirm ||
            confidence < minConfidenceThreshold ||
            vehicleDisplay.isBlank() ||
            resolved.truckWeightKg == null ||
            resolved.weightDate.isBlank()

        return OcrExtractionResult(
            vehicleNumber = vehicleDisplay,
            truckWeightKg = resolved.truckWeightKg,
            weightDate = resolved.weightDate,
            sourceLabel = resolved.sourceLabel,
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

    suspend fun readImageAspectRatio(uri: Uri): Float = withContext(Dispatchers.IO) {
        val bitmap = loadBitmapBounded(uri, maxDim = 1024) ?: return@withContext 0.75f
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        (w.toFloat() / h.toFloat()).coerceIn(0.4f, 2.2f)
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

    data class ResolvedTruckWeight(
        val truckWeightKg: Double?,
        val weightDate: String,
        val sourceLabel: String
    )

    companion object {
        const val PRIMARY_OCR_MODEL = "gemini-3.8-flash"
        const val FALLBACK_OCR_MODEL = "gemini-3.5-flash-lite"

        fun buildPromptText(
            strictSecondWeightPriority: Boolean,
            customTemplates: List<SlipFormatTemplate>
        ): String {
            val multiWeighingRule = if (strictSecondWeightPriority) {
                "On First Weight / Second Weight slips: when BOTH First Weight and Second Weight exist AND there is a cargo load difference (e.g. Net Weight > 0 or one weight is much heavier than the other), ALWAYS choose the SMALLER weight as truckWeightKg (empty truck/tare weight) — NEVER pick the loaded gross weight! Only prefer Second Weight if both weighings are close empty check weighings (no cargo load difference)."
            } else {
                "On First Weight / Second Weight slips: choose the empty truck/tare weighing (the smaller weight when Gross/Net cargo is present, or whichever row matches empty tare)."
            }

            val activeCustom = customTemplates
                .filter { it.isActive && it.vehicleBox != null && it.weightBox != null }
                .take(15)

            val customSection = if (activeCustom.isEmpty()) {
                ""
            } else {
                val templateDescriptions = activeCustom.mapIndexed { idx, tmpl ->
                    val headerPart = if (tmpl.headerHint.isNotBlank()) {
                        " | Header/Scale Title Match: \"${tmpl.headerHint}\""
                    } else ""
                    val vHint = if (tmpl.vehicleLabelHint.isNotBlank()) {
                        " (printed near label \"${tmpl.vehicleLabelHint}\")"
                    } else ""
                    val wHint = if (tmpl.weightLabelHint.isNotBlank()) {
                        " (printed near label \"${tmpl.weightLabelHint}\")"
                    } else ""
                    val dateLine = tmpl.dateBox?.takeIf { it.isValid() }?.let { dBox ->
                        val dHint = if (tmpl.dateLabelHint.isNotBlank()) {
                            " (printed near label \"${tmpl.dateLabelHint}\")"
                        } else ""
                        "\n                  - Weight Date Region: ${dBox.toPercentSummary()}$dHint"
                    }.orEmpty()
                    val notesLine = if (tmpl.notes.isNotBlank()) {
                        "\n                  - Owner Notes: ${tmpl.notes}"
                    } else ""

                    """
                    Custom Format #${idx + 1}: "${tmpl.formatName}"$headerPart
                      - Vehicle/Truck Number Region: ${tmpl.vehicleBox!!.toPercentSummary()}$vHint
                      - Empty/Tare Truck Weight Region (PUT THIS NUMBER IN tareWeightKg AND truckWeightKg): ${tmpl.weightBox!!.toPercentSummary()}$wHint$dateLine$notesLine
                    """.trimIndent()
                }.joinToString("\n\n")

                """

                CUSTOM OWNER-MARKED SLIP FORMATS (HIGHEST PRIORITY WHEN SLIP MATCHES):
                The Owner has marked exact normalized [Left%..Right%, Top%..Bottom%] bounding regions for newer/custom scale slips.
                First inspect the slip header, layout geometry, and labels to see if this image matches any custom format below (allowing small camera perspective shifts). If it matches, extract Vehicle Number, Empty/Tare Truck Weight, and Weight Date from those exact marked regions and label hints, and mention the matched format name in `reasoning`:

                $templateDescriptions
                """.trimIndent()
            }

            return """
                You are a high-precision Bangladesh industrial weighbridge slip OCR verification engine.
                Carefully inspect this weighbridge ticket / weight report / weight bill image and extract the EMPTY TRUCK (TARE) fields.
                $customSection

                BUILT-IN STANDARD SLIP LAYOUTS & EXACT FIELD LOCATIONS:
                1. Layout A — "Weight Report" with "First Weight" / "Second Weight" / "Net Weight" (e.g. MAA-BABAR DUA TRADING):
                   - Vehicle Number: Look for "Vehicle No :" (e.g. "BUG T-11-1633"). Never pick Challan, Contact Number, or Qnty.
                   - Empty Truck Weight: Read both "First Weight" (into firstWeightKg) and "Second Weight" (into secondWeightKg), plus "Net Weight" (into netWeightKg). $multiWeighingRule
                   - Weight Date: Read the "Transaction Date :" column on the SAME row as the chosen empty truck weight (e.g. "22/11/26"). Put each row's date into firstWeightDate / secondWeightDate and set weightDate to the matching date ONLY (omit clock time like "7:52:45 am").
                2. Layout B — 3-Column Scale Slip with "Gross Weight" / "Tare Weight" / "Net Weight" (e.g. MAHIRA ENTERPRISE DIGITAL SCALE):
                   - Vehicle Number: Look for "Truck No :" (e.g. "DMT 24-6422"). Never pick Weight id, Challan No, Clint Code, or mobile numbers.
                   - Empty Truck Weight: Look strictly for "Tare Weight" (e.g. "7,160 Kg" -> 7160.0) into both tareWeightKg and truckWeightKg. NEVER pick "Gross Weight" or "Net Weight".
                   - Weight Date: Pick ONLY the "Date :" printed directly above "Tare Weight" (e.g. "16/11/2026" from "Date : 16/11/2026 3:18:44PM"), omitting the clock time. Put this in tareDate and weightDate.
                3. Layout C — Tabular "Weight Bill" with "Tare:" and "Tare Time:" (e.g. KAZI GHAT DIGITAL SCALE):
                   - Vehicle Number: Look for "Vehicle:" (e.g. "DMT -13-0806"). Never pick Serial No, Bag, or mobile numbers.
                   - Empty Truck Weight: Look strictly for "Tare:" (e.g. "10270" with Unit Kg -> 10270.0) into both tareWeightKg and truckWeightKg. NEVER pick Gross or Net.
                   - Weight Date: Pick ONLY the calendar date from "Tare Time:" (e.g. "2026-11-22" from "Tare Time: 2026-11-22 9:33:25 AM"), omitting the clock time and ignoring the header DateTime. Put this in tareDate and weightDate.

                GENERAL EXTRACTION RULES:
                - Remove commas and "Kg" units for all numeric weights (e.g. "6,050 Kg" -> 6050.0, "7,160 Kg" -> 7160.0, "10270" -> 10270.0).
                - Return weightDate as ONLY the calendar date (DD/MM/YY, DD/MM/YYYY, or YYYY-MM-DD) without time.
                - Never guess or fabricate obscured digits. If the target vehicle number or empty truck weight is unreadable or missing, return null for truckWeightKg (or empty string for vehicleNumber) and set requiresManualConfirmation = true.
            """.trimIndent()
        }

        private val CALENDAR_DATE_REGEX =
            Regex("""\b(\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})\b""")

        fun cleanCalendarDate(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return CALENDAR_DATE_REGEX.find(trimmed)?.groupValues?.get(1) ?: trimmed
        }

        fun resolveTruckWeightSelection(
            modelTruckWeightKg: Double?,
            tareWeightKg: Double?,
            grossWeightKg: Double?,
            netWeightKg: Double?,
            firstWeightKg: Double?,
            secondWeightKg: Double?,
            modelWeightDate: String,
            tareDate: String,
            firstWeightDate: String,
            secondWeightDate: String,
            modelSourceLabel: String,
            strictSecondWeightPriority: Boolean
        ): ResolvedTruckWeight {
            val cleanedModelDate = cleanCalendarDate(modelWeightDate)
            val cleanedTareDate = cleanCalendarDate(tareDate)
            val cleanedFirstDate = cleanCalendarDate(firstWeightDate)
            val cleanedSecondDate = cleanCalendarDate(secondWeightDate)

            // 1. Explicit Tare Weight / Tare label takes top priority (Layouts B & C)
            if (tareWeightKg != null && tareWeightKg > 0.0) {
                val resolvedLabel = if (modelSourceLabel.contains("tare", ignoreCase = true)) {
                    modelSourceLabel
                } else {
                    "Tare Weight"
                }
                val resolvedDate = cleanedTareDate.ifBlank { cleanedModelDate }
                return ResolvedTruckWeight(
                    truckWeightKg = tareWeightKg,
                    weightDate = resolvedDate,
                    sourceLabel = resolvedLabel
                )
            }

            // 2. First Weight / Second Weight report layout (Layout A)
            if (firstWeightKg != null && secondWeightKg != null) {
                val diff = abs(firstWeightKg - secondWeightKg)
                val matchesNetDiff = netWeightKg != null && netWeightKg > 0.0 && abs(diff - netWeightKg) <= 50.0
                val hasCargoLoadDifference = diff >= 500.0 || matchesNetDiff

                return if (hasCargoLoadDifference) {
                    // One weighing is empty (Tare) and the other is loaded (Gross): empty truck is always lighter.
                    if (firstWeightKg <= secondWeightKg) {
                        ResolvedTruckWeight(
                            truckWeightKg = firstWeightKg,
                            weightDate = cleanedFirstDate.ifBlank { cleanedModelDate },
                            sourceLabel = "First Weight"
                        )
                    } else {
                        ResolvedTruckWeight(
                            truckWeightKg = secondWeightKg,
                            weightDate = cleanedSecondDate.ifBlank { cleanedModelDate },
                            sourceLabel = "Second Weight"
                        )
                    }
                } else if (strictSecondWeightPriority) {
                    ResolvedTruckWeight(
                        truckWeightKg = secondWeightKg,
                        weightDate = cleanedSecondDate.ifBlank { cleanedModelDate },
                        sourceLabel = "Second Weight"
                    )
                } else {
                    ResolvedTruckWeight(
                        truckWeightKg = modelTruckWeightKg ?: secondWeightKg,
                        weightDate = cleanedModelDate.ifBlank { cleanedSecondDate.ifBlank { cleanedFirstDate } },
                        sourceLabel = modelSourceLabel
                    )
                }
            }

            if (firstWeightKg != null && secondWeightKg == null) {
                val resolvedWeight = if (
                    modelTruckWeightKg == null ||
                    (grossWeightKg != null && abs(modelTruckWeightKg - grossWeightKg) < 0.01) ||
                    (netWeightKg != null && abs(modelTruckWeightKg - netWeightKg) < 0.01)
                ) {
                    firstWeightKg
                } else {
                    modelTruckWeightKg
                }
                return ResolvedTruckWeight(
                    truckWeightKg = resolvedWeight,
                    weightDate = cleanedFirstDate.ifBlank { cleanedModelDate },
                    sourceLabel = if (modelSourceLabel.contains("first", ignoreCase = true)) modelSourceLabel else "First Weight"
                )
            }

            if (secondWeightKg != null && firstWeightKg == null) {
                val resolvedWeight = if (
                    modelTruckWeightKg == null ||
                    (grossWeightKg != null && abs(modelTruckWeightKg - grossWeightKg) < 0.01) ||
                    (netWeightKg != null && abs(modelTruckWeightKg - netWeightKg) < 0.01)
                ) {
                    secondWeightKg
                } else {
                    modelTruckWeightKg
                }
                return ResolvedTruckWeight(
                    truckWeightKg = resolvedWeight,
                    weightDate = cleanedSecondDate.ifBlank { cleanedModelDate },
                    sourceLabel = if (modelSourceLabel.contains("second", ignoreCase = true)) modelSourceLabel else "Second Weight"
                )
            }

            return ResolvedTruckWeight(
                truckWeightKg = modelTruckWeightKg,
                weightDate = cleanedModelDate,
                sourceLabel = modelSourceLabel
            )
        }

        fun isTransientDemandError(error: Throwable): Boolean {
            val chainMessages = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.lowercase() }
                .joinToString(" | ")
            val classNames = generateSequence(error) { it.cause }
                .map { it::class.java.simpleName.lowercase() }
                .joinToString(" | ")

            // Never treat App Check, permission, or auth failures as transient capacity spikes
            if (chainMessages.contains("app check") ||
                chainMessages.contains("permission") ||
                chainMessages.contains("api key") ||
                chainMessages.contains("unauthenticated")
            ) {
                return false
            }

            return chainMessages.contains("high demand") ||
                chainMessages.contains("overloaded") ||
                chainMessages.contains("temporarily unavailable") ||
                chainMessages.contains("unavailable") ||
                chainMessages.contains("resource_exhausted") ||
                chainMessages.contains("rate limit") ||
                chainMessages.contains("quota") ||
                chainMessages.contains("too many requests") ||
                chainMessages.contains("503") ||
                chainMessages.contains("429") ||
                chainMessages.contains("500") ||
                chainMessages.contains("try again later") ||
                classNames.contains("serviceunavailable") ||
                classNames.contains("serverexception")
        }

        fun isAppCheckError(error: Throwable): Boolean {
            val chainMessages = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.lowercase() }
                .joinToString(" | ")
            return chainMessages.contains("app check") ||
                chainMessages.contains("appcheck") ||
                chainMessages.contains("attestation")
        }
    }
}
