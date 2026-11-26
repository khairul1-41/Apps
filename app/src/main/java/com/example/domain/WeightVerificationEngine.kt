package com.example.domain

import com.example.data.model.AlertStatus
import com.example.data.model.RuntimeSettings
import com.example.data.model.TruckWeightRecord
import com.example.data.model.VerificationComparison
import com.google.firebase.Timestamp
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object WeightVerificationEngine {

    private val weightFormatter = DecimalFormat("#,##0.##")

    /**
     * Normalizes vehicle registration numbers so minor OCR spacing/dash variance
     * (e.g., "DMT -13-0806" vs "DMT-13-0806") still matches the same truck,
     * while strictly isolating different trucks.
     */
    fun normalizeVehicleKey(raw: String): String {
        return raw.uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9]"), "")
            .trim()
    }

    fun normalizeVehicleDisplay(raw: String): String {
        return raw.uppercase(Locale.US)
            .replace(Regex("\\s*-\\s*"), "-")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun parseWeightNumber(raw: String): Double? {
        val cleaned = raw.replace(",", "")
            .replace(Regex("(?i)\\s*kgs?\\b"), "")
            .trim()
        if (cleaned.isEmpty()) return null
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)").find(cleaned) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return value.takeIf { it > 0.0 && it <= 500_000.0 }
    }

    fun formatWeightKg(weightKg: Double?): String {
        if (weightKg == null || weightKg <= 0.0) return "—"
        return "${weightFormatter.format(weightKg)} KG"
    }

    fun formatDifferenceKg(diffKg: Double): String {
        return "${weightFormatter.format(abs(diffKg))} KG"
    }

    fun findLatestValidPreviousRecord(
        vehicleKey: String,
        records: List<TruckWeightRecord>,
        excludeRecordId: String? = null
    ): TruckWeightRecord? {
        if (vehicleKey.isBlank()) return null
        return records
            .asSequence()
            .filter { it.vehicleKey == vehicleKey && it.truckWeight > 0.0 }
            .filter { excludeRecordId == null || it.id != excludeRecordId }
            .maxByOrNull { recordSortMillis(it) }
    }

    fun evaluateAlert(
        currentWeightKg: Double,
        previousWeightKg: Double?,
        settings: RuntimeSettings
    ): Pair<Double, AlertStatus> {
        if (previousWeightKg == null || previousWeightKg <= 0.0) {
            return 0.0 to AlertStatus.NEUTRAL
        }
        val diff = abs(currentWeightKg - previousWeightKg)
        val status = when {
            diff <= settings.neutralMaxKg -> AlertStatus.NEUTRAL
            diff <= settings.greenMaxKg -> AlertStatus.GREEN_SIGNAL
            diff <= settings.orangeMaxKg -> AlertStatus.ORANGE_WARNING
            else -> AlertStatus.RED_ALERT
        }
        return diff to status
    }

    fun isDuplicateCandidate(
        vehicleKey: String,
        weightKg: Double?,
        weightDate: String,
        existingRecords: List<TruckWeightRecord>
    ): Boolean {
        if (vehicleKey.isBlank() || weightKg == null || weightKg <= 0.0) return false
        val normalizedDate = weightDate.trim().lowercase(Locale.US)
        return existingRecords.any { record ->
            record.vehicleKey == vehicleKey &&
                abs(record.truckWeight - weightKg) < 0.01 &&
                record.weightDate.trim().lowercase(Locale.US) == normalizedDate
        }
    }

    fun buildComparison(
        vehicleNumberRaw: String,
        currentWeightKg: Double?,
        weightDate: String,
        existingRecords: List<TruckWeightRecord>,
        settings: RuntimeSettings,
        excludeRecordId: String? = null
    ): VerificationComparison {
        val vehicleKey = normalizeVehicleKey(vehicleNumberRaw)
        val previous = findLatestValidPreviousRecord(
            vehicleKey = vehicleKey,
            records = existingRecords,
            excludeRecordId = excludeRecordId
        )
        val (diff, status) = if (currentWeightKg != null && currentWeightKg > 0.0) {
            evaluateAlert(currentWeightKg, previous?.truckWeight, settings)
        } else {
            0.0 to AlertStatus.NEUTRAL
        }
        val duplicate = isDuplicateCandidate(
            vehicleKey = vehicleKey,
            weightKg = currentWeightKg,
            weightDate = weightDate,
            existingRecords = existingRecords
        )
        return VerificationComparison(
            currentWeightKg = currentWeightKg,
            previousWeightKg = previous?.truckWeight,
            previousRecordDate = previous?.weightDate,
            previousRecordId = previous?.id,
            weightDifferenceKg = diff,
            alertStatus = status,
            isDuplicateRisk = duplicate
        )
    }

    fun recordSortMillis(record: TruckWeightRecord): Long {
        return record.createdAt?.toDate()?.time ?: 0L
    }

    fun formatTimestamp(timestamp: Timestamp?): String {
        val date = timestamp?.toDate() ?: return "Pending sync"
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
        return sdf.format(date)
    }

    fun currentFallbackDateString(): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        return sdf.format(Date())
    }

    fun exportRecordsToCsv(records: List<TruckWeightRecord>): String {
        val header = listOf(
            "Record ID",
            "Vehicle Number",
            "Truck Weight (KG)",
            "Previous Weight (KG)",
            "Difference (KG)",
            "Alert Status",
            "Weight Date",
            "Source Label",
            "OCR Confidence",
            "Telegram Message ID",
            "Telegram File ID",
            "Created At"
        ).joinToString(",")

        val rows = records.map { rec ->
            listOf(
                escapeCsv(rec.id),
                escapeCsv(rec.vehicleNumber),
                rec.truckWeight.toString(),
                rec.previousWeight?.toString() ?: "",
                rec.weightDifference.toString(),
                escapeCsv(rec.alertStatus),
                escapeCsv(rec.weightDate),
                escapeCsv(rec.sourceLabel ?: ""),
                String.format(Locale.US, "%.2f", rec.ocrConfidence),
                escapeCsv(rec.telegramMessageId ?: ""),
                escapeCsv(rec.documentImageTelegramFileId ?: ""),
                escapeCsv(formatTimestamp(rec.createdAt))
            ).joinToString(",")
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }
}
