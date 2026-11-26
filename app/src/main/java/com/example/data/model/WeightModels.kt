package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class AlertStatus(
    val displayTitle: String,
    val shortBadge: String,
    val summary: String
) {
    NEUTRAL(
        displayTitle = "Neutral",
        shortBadge = "NEUTRAL",
        summary = "0–4 KG variance or baseline first weighing"
    ),
    GREEN_SIGNAL(
        displayTitle = "Green Signal",
        shortBadge = "GREEN SIGNAL",
        summary = "5–15 KG normal operational variance"
    ),
    ORANGE_WARNING(
        displayTitle = "Orange Warning",
        shortBadge = "ORANGE WARNING",
        summary = "16–19 KG elevated weight difference"
    ),
    RED_ALERT(
        displayTitle = "Red Alert",
        shortBadge = "RED ALERT",
        summary = "20 KG or more critical discrepancy"
    );

    companion object {
        fun fromStringSafe(raw: String?): AlertStatus {
            if (raw.isNullOrBlank()) return NEUTRAL
            return entries.firstOrNull { it.name == raw.trim() } ?: NEUTRAL
        }
    }
}

@Keep
@IgnoreExtraProperties
data class TruckWeightRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val vehicleNumber: String = "",
    val vehicleKey: String = "",
    val truckWeight: Double = 0.0,
    val weightDate: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
    val previousWeight: Double? = null,
    val weightDifference: Double = 0.0,
    val alertStatus: String = AlertStatus.NEUTRAL.name,
    val ocrConfidence: Double = 0.0,
    val telegramMessageId: String? = null,
    val documentImageTelegramFileId: String? = null,
    val sourceLabel: String? = null,
    val notes: String? = null
) {
    val resolvedAlertStatus: AlertStatus
        get() = AlertStatus.fromStringSafe(alertStatus)

    @Suppress("UNCHECKED_CAST")
    fun toCreateMap(authenticatedUid: String): Map<String, Any> {
        return mapOf(
            "userId" to authenticatedUid,
            "vehicleNumber" to vehicleNumber.trim().take(64),
            "vehicleKey" to vehicleKey.trim().take(64),
            "truckWeight" to truckWeight,
            "weightDate" to weightDate.trim().take(40),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "previousWeight" to previousWeight?.takeIf { it > 0.0 },
            "weightDifference" to weightDifference,
            "alertStatus" to AlertStatus.fromStringSafe(alertStatus).name,
            "ocrConfidence" to ocrConfidence.coerceIn(0.0, 1.0),
            "telegramMessageId" to telegramMessageId?.trim()?.takeIf { it.isNotEmpty() }?.take(128),
            "documentImageTelegramFileId" to documentImageTelegramFileId?.trim()?.takeIf { it.isNotEmpty() }?.take(256),
            "sourceLabel" to sourceLabel?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
            "notes" to notes?.trim()?.takeIf { it.isNotEmpty() }?.take(500)
        ).filterValues { it != null } as Map<String, Any>
    }
}

@Keep
@IgnoreExtraProperties
data class RuntimeSettings(
    val neutralMaxKg: Double = 4.0,
    val greenMaxKg: Double = 15.0,
    val orangeMaxKg: Double = 19.0,
    val minOcrConfidence: Double = 0.70,
    val autoSendTelegram: Boolean = true,
    val telegramRelayUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    @Suppress("UNCHECKED_CAST")
    fun toCreateMap(): Map<String, Any> = mapOf(
        "neutralMaxKg" to neutralMaxKg.coerceIn(0.0, 1000.0),
        "greenMaxKg" to greenMaxKg.coerceIn(neutralMaxKg, 5000.0),
        "orangeMaxKg" to orangeMaxKg.coerceIn(greenMaxKg, 10000.0),
        "minOcrConfidence" to minOcrConfidence.coerceIn(0.0, 1.0),
        "autoSendTelegram" to autoSendTelegram,
        "telegramRelayUrl" to telegramRelayUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(512),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null } as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    fun toUpdateMap(): Map<String, Any> = mapOf(
        "neutralMaxKg" to neutralMaxKg.coerceIn(0.0, 1000.0),
        "greenMaxKg" to greenMaxKg.coerceIn(neutralMaxKg, 5000.0),
        "orangeMaxKg" to orangeMaxKg.coerceIn(greenMaxKg, 10000.0),
        "minOcrConfidence" to minOcrConfidence.coerceIn(0.0, 1.0),
        "autoSendTelegram" to autoSendTelegram,
        "telegramRelayUrl" to (telegramRelayUrl?.trim()?.take(512) ?: ""),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class OwnerAdminConfig(
    val ownerEmail: String = "lofimusic492@gmail.com",
    val ocrStrictFinalWeight: Boolean = true,
    val telegramChatIdLabel: String? = null,
    val firebaseConfigNote: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    @Suppress("UNCHECKED_CAST")
    fun toCreateMap(): Map<String, Any> = mapOf(
        "ownerEmail" to ownerEmail.trim().take(128),
        "ocrStrictFinalWeight" to ocrStrictFinalWeight,
        "telegramChatIdLabel" to telegramChatIdLabel?.trim()?.takeIf { it.isNotEmpty() }?.take(128),
        "firebaseConfigNote" to firebaseConfigNote?.trim()?.takeIf { it.isNotEmpty() }?.take(500),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null } as Map<String, Any>

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "ownerEmail" to ownerEmail.trim().take(128),
        "ocrStrictFinalWeight" to ocrStrictFinalWeight,
        "telegramChatIdLabel" to (telegramChatIdLabel?.trim()?.take(128) ?: ""),
        "firebaseConfigNote" to (firebaseConfigNote?.trim()?.take(500) ?: ""),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

data class OcrExtractionResult(
    val vehicleNumber: String,
    val truckWeightKg: Double?,
    val weightDate: String,
    val sourceLabel: String,
    val confidence: Double,
    val requiresManualConfirmation: Boolean,
    val reasoning: String,
    val grossWeightKg: Double? = null,
    val netWeightKg: Double? = null,
    val firstWeightKg: Double? = null,
    val secondWeightKg: Double? = null
)

data class VerificationComparison(
    val currentWeightKg: Double?,
    val previousWeightKg: Double?,
    val previousRecordDate: String?,
    val previousRecordId: String?,
    val weightDifferenceKg: Double,
    val alertStatus: AlertStatus,
    val isDuplicateRisk: Boolean
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
