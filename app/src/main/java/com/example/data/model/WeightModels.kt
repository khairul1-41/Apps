package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class UserRole(val displayTitle: String) {
    OWNER("Owner"),
    ADMIN("Admin"),
    USER("User");

    val canManageUsers: Boolean
        get() = this == OWNER || this == ADMIN

    val canManageAdmins: Boolean
        get() = this == OWNER

    companion object {
        fun fromStringSafe(raw: String?): UserRole {
            if (raw.isNullOrBlank()) return USER
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: USER
        }
    }
}

sealed interface MemberAccessState {
    data object Checking : MemberAccessState
    data class Authorized(
        val role: UserRole,
        val displayName: String? = null
    ) : MemberAccessState

    data class Unauthorized(
        val email: String,
        val errorMessage: String? = null
    ) : MemberAccessState
}

@Keep
@IgnoreExtraProperties
data class AuthorizedMember(
    @DocumentId val id: String = "",
    val email: String = "",
    val displayName: String? = null,
    val role: String = UserRole.USER.name,
    val addedByEmail: String = "",
    val addedByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val resolvedRole: UserRole
        get() = if (role.trim().equals(UserRole.ADMIN.name, ignoreCase = true)) {
            UserRole.ADMIN
        } else {
            UserRole.USER
        }

    @Suppress("UNCHECKED_CAST")
    fun toCreateMap(
        normalizedEmail: String,
        assignedRole: UserRole,
        creatorEmail: String,
        creatorUid: String
    ): Map<String, Any> {
        val safeRole = if (assignedRole == UserRole.ADMIN) UserRole.ADMIN.name else UserRole.USER.name
        return mapOf(
            "email" to normalizedEmail,
            "displayName" to displayName?.trim()?.takeIf { it.isNotEmpty() }?.take(80),
            "role" to safeRole,
            "addedByEmail" to creatorEmail.trim().lowercase(java.util.Locale.US).take(128),
            "addedByUid" to creatorUid.trim().take(128),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }
}

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
    val createdByEmail: String? = null,
    val createdByRole: String? = null,
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
            "createdByEmail" to createdByEmail?.trim()?.lowercase(java.util.Locale.US)?.takeIf { it.isNotEmpty() }?.take(128),
            "createdByRole" to createdByRole?.trim()?.takeIf { it in setOf("OWNER", "ADMIN", "USER") },
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
    val appCheckDebugToken: String? = null,
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
        "appCheckDebugToken" to appCheckDebugToken?.trim()?.takeIf { it.isNotEmpty() }?.take(64),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null } as Map<String, Any>

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "neutralMaxKg" to neutralMaxKg.coerceIn(0.0, 1000.0),
        "greenMaxKg" to greenMaxKg.coerceIn(neutralMaxKg, 5000.0),
        "orangeMaxKg" to orangeMaxKg.coerceIn(greenMaxKg, 10000.0),
        "minOcrConfidence" to minOcrConfidence.coerceIn(0.0, 1.0),
        "autoSendTelegram" to autoSendTelegram,
        "telegramRelayUrl" to (telegramRelayUrl?.trim()?.take(512) ?: ""),
        "appCheckDebugToken" to (appCheckDebugToken?.trim()?.take(64) ?: ""),
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

enum class AdsDisplayMode {
    AD_CODE,
    SPONSORED_CARD;

    companion object {
        fun fromStringSafe(raw: String?): AdsDisplayMode {
            if (raw.isNullOrBlank()) return AD_CODE
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: AD_CODE
        }
    }
}

@Keep
@IgnoreExtraProperties
data class AdsBannerConfig(
    val adsEnabled: Boolean = false,
    val adsMode: String = AdsDisplayMode.AD_CODE.name,
    val adCodeSnippet: String = "",
    val sponsorTitle: String = "",
    val sponsorSubtitle: String = "",
    val sponsorCtaLabel: String = "Open",
    val sponsorTargetUrl: String = "",
    val updatedByEmail: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val resolvedMode: AdsDisplayMode
        get() = AdsDisplayMode.fromStringSafe(adsMode)

    fun toCreateMap(ownerEmail: String): Map<String, Any> = mapOf(
        "adsEnabled" to adsEnabled,
        "adsMode" to resolvedMode.name,
        "adCodeSnippet" to adCodeSnippet.trim().take(4096),
        "sponsorTitle" to sponsorTitle.trim().take(120),
        "sponsorSubtitle" to sponsorSubtitle.trim().take(240),
        "sponsorCtaLabel" to sponsorCtaLabel.trim().ifEmpty { "Open" }.take(32),
        "sponsorTargetUrl" to sponsorTargetUrl.trim().take(512),
        "updatedByEmail" to ownerEmail.trim().lowercase(java.util.Locale.US).take(128),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    fun toUpdateMap(ownerEmail: String): Map<String, Any> = mapOf(
        "adsEnabled" to adsEnabled,
        "adsMode" to resolvedMode.name,
        "adCodeSnippet" to adCodeSnippet.trim().take(4096),
        "sponsorTitle" to sponsorTitle.trim().take(120),
        "sponsorSubtitle" to sponsorSubtitle.trim().take(240),
        "sponsorCtaLabel" to sponsorCtaLabel.trim().ifEmpty { "Open" }.take(32),
        "sponsorTargetUrl" to sponsorTargetUrl.trim().take(512),
        "updatedByEmail" to ownerEmail.trim().lowercase(java.util.Locale.US).take(128),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class SlipRegionBox(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0
) {
    fun isValid(): Boolean {
        return left in 0.0..0.99 &&
            top in 0.0..0.99 &&
            right in (left + 0.01)..1.0 &&
            bottom in (top + 0.01)..1.0
    }

    fun normalizedClamped(): SlipRegionBox {
        val l = minOf(left, right).coerceIn(0.0, 0.98)
        val t = minOf(top, bottom).coerceIn(0.0, 0.98)
        val r = maxOf(left, right).coerceIn(l + 0.02, 1.0)
        val b = maxOf(top, bottom).coerceIn(t + 0.02, 1.0)
        return SlipRegionBox(left = l, top = t, right = r, bottom = b)
    }

    fun toPercentSummary(): String {
        val l = (left * 100).toInt()
        val t = (top * 100).toInt()
        val r = (right * 100).toInt()
        val b = (bottom * 100).toInt()
        return "X: $l%–$r%, Y: $t%–$b%"
    }

    fun toFirestoreMap(): Map<String, Any> {
        val clean = normalizedClamped()
        return mapOf(
            "left" to clean.left,
            "top" to clean.top,
            "right" to clean.right,
            "bottom" to clean.bottom
        )
    }
}

@Keep
@IgnoreExtraProperties
data class SlipFormatTemplate(
    @DocumentId val id: String = "",
    val formatName: String = "",
    val headerHint: String = "",
    val vehicleBox: SlipRegionBox? = null,
    val vehicleLabelHint: String = "",
    val weightBox: SlipRegionBox? = null,
    val weightLabelHint: String = "",
    val dateBox: SlipRegionBox? = null,
    val dateLabelHint: String = "",
    val notes: String = "",
    val isActive: Boolean = true,
    val createdByEmail: String = "",
    val createdByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    @Suppress("UNCHECKED_CAST")
    fun toCreateMap(ownerEmail: String, ownerUid: String): Map<String, Any> {
        val vBox = vehicleBox?.toFirestoreMap()
            ?: throw IllegalArgumentException("Vehicle Number region must be marked.")
        val wBox = weightBox?.toFirestoreMap()
            ?: throw IllegalArgumentException("Truck Weight region must be marked.")
        return mapOf(
            "formatName" to formatName.trim().take(80),
            "headerHint" to headerHint.trim().take(120),
            "vehicleBox" to vBox,
            "vehicleLabelHint" to vehicleLabelHint.trim().take(64),
            "weightBox" to wBox,
            "weightLabelHint" to weightLabelHint.trim().take(64),
            "dateBox" to dateBox?.takeIf { it.isValid() }?.toFirestoreMap(),
            "dateLabelHint" to dateLabelHint.trim().take(64),
            "notes" to notes.trim().take(240),
            "isActive" to isActive,
            "createdByEmail" to ownerEmail.trim().lowercase(java.util.Locale.US).take(128),
            "createdByUid" to ownerUid.trim().take(128),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }
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

@Keep
@IgnoreExtraProperties
data class AppUpdateRelease(
    val versionCode: Long = 0L,
    val versionName: String = "",
    val fileName: String = "",
    val fileSizeBytes: Long = 0L,
    val sha256Checksum: String = "",
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val isMandatory: Boolean = false,
    val publishedByEmail: String = "",
    val publishedByRole: String = UserRole.OWNER.name,
    val publishedByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(
        publisherEmail: String,
        publisherRole: UserRole,
        publisherUid: String
    ): Map<String, Any> {
        val safeRole = if (publisherRole == UserRole.OWNER) UserRole.OWNER.name else UserRole.ADMIN.name
        return mapOf(
            "versionCode" to versionCode.coerceIn(1L, 999_999_999L),
            "versionName" to versionName.trim().take(32),
            "fileName" to fileName.trim().take(128),
            "fileSizeBytes" to fileSizeBytes.coerceIn(1024L, 1_073_741_824L),
            "sha256Checksum" to sha256Checksum.trim().lowercase(java.util.Locale.US).take(64),
            "downloadUrl" to downloadUrl.trim().take(1024),
            "releaseNotes" to releaseNotes.trim().take(500),
            "isMandatory" to isMandatory,
            "publishedByEmail" to publisherEmail.trim().lowercase(java.util.Locale.US).take(128),
            "publishedByRole" to safeRole,
            "publishedByUid" to publisherUid.trim().take(128),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }

    fun toUpdateMap(
        publisherEmail: String,
        publisherRole: UserRole,
        publisherUid: String
    ): Map<String, Any> {
        val safeRole = if (publisherRole == UserRole.OWNER) UserRole.OWNER.name else UserRole.ADMIN.name
        return mapOf(
            "versionCode" to versionCode.coerceIn(1L, 999_999_999L),
            "versionName" to versionName.trim().take(32),
            "fileName" to fileName.trim().take(128),
            "fileSizeBytes" to fileSizeBytes.coerceIn(1024L, 1_073_741_824L),
            "sha256Checksum" to sha256Checksum.trim().lowercase(java.util.Locale.US).take(64),
            "downloadUrl" to downloadUrl.trim().take(1024),
            "releaseNotes" to releaseNotes.trim().take(500),
            "isMandatory" to isMandatory,
            "publishedByEmail" to publisherEmail.trim().lowercase(java.util.Locale.US).take(128),
            "publishedByRole" to safeRole,
            "publishedByUid" to publisherUid.trim().take(128),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}


