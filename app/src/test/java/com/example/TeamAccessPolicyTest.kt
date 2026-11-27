package com.example

import com.example.core.OwnerPolicy
import com.example.data.model.AppUpdateRelease
import com.example.data.model.AuthorizedMember
import com.example.data.model.TruckWeightRecord
import com.example.data.model.UserRole
import com.example.data.remote.AppUpdateUrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamAccessPolicyTest {

    @Test
    fun ownerPolicy_normalizesAndValidatesEmails() {
        assertEquals("operator@gmail.com", OwnerPolicy.normalizeEmail("  Operator@Gmail.COM "))
        assertEquals("", OwnerPolicy.normalizeEmail(null))

        assertTrue(OwnerPolicy.isValidEmail("operator.1+gate@gmail.com"))
        assertTrue(OwnerPolicy.isValidEmail("  LOFIMUSIC492@gmail.com "))
        assertFalse(OwnerPolicy.isValidEmail("invalid-email"))
        assertFalse(OwnerPolicy.isValidEmail("user@domain"))
        assertFalse(OwnerPolicy.isValidEmail(""))
    }

    @Test
    fun userRole_enforcesAdminAndUserManagementHierarchy() {
        assertTrue(UserRole.OWNER.canManageUsers)
        assertTrue(UserRole.OWNER.canManageAdmins)

        assertTrue(UserRole.ADMIN.canManageUsers)
        assertFalse(UserRole.ADMIN.canManageAdmins)

        assertFalse(UserRole.USER.canManageUsers)
        assertFalse(UserRole.USER.canManageAdmins)

        assertEquals(UserRole.ADMIN, UserRole.fromStringSafe("admin"))
        assertEquals(UserRole.OWNER, UserRole.fromStringSafe("OWNER"))
        assertEquals(UserRole.USER, UserRole.fromStringSafe("unknown"))
        assertEquals(UserRole.USER, UserRole.fromStringSafe(null))
    }

    @Test
    fun authorizedMember_neverElevatesArbitraryStringsToOwnerOrAdmin() {
        val admin = AuthorizedMember(email = "lead@gmail.com", role = "admin")
        assertEquals(UserRole.ADMIN, admin.resolvedRole)

        val elevatedAttempt = AuthorizedMember(email = "spoof@gmail.com", role = "OWNER")
        assertEquals(UserRole.USER, elevatedAttempt.resolvedRole)

        val createMap = admin.toCreateMap(
            normalizedEmail = "lead@gmail.com",
            assignedRole = UserRole.OWNER,
            creatorEmail = "LOFIMUSIC492@GMAIL.COM",
            creatorUid = "uid-owner"
        )
        assertEquals("USER", createMap["role"])
        assertEquals("lofimusic492@gmail.com", createMap["addedByEmail"])
    }

    @Test
    fun truckWeightRecord_toCreateMapSanitizesCreatorAttribution() {
        val record = TruckWeightRecord(
            userId = "uid-user-1",
            createdByEmail = "  Gate.Operator@Gmail.com ",
            createdByRole = "ADMIN",
            vehicleNumber = "DMT 24-6422",
            vehicleKey = "DMT246422",
            truckWeight = 7160.0,
            weightDate = "16/11/2026"
        )

        val payload = record.toCreateMap("uid-user-1")
        assertEquals("uid-user-1", payload["userId"])
        assertEquals("gate.operator@gmail.com", payload["createdByEmail"])
        assertEquals("ADMIN", payload["createdByRole"])

        val invalidRoleRecord = record.copy(createdByRole = "SUPERUSER", createdByEmail = "   ")
        val sanitizedPayload = invalidRoleRecord.toCreateMap("uid-user-1")
        assertNull(sanitizedPayload["createdByRole"])
        assertNull(sanitizedPayload["createdByEmail"])
    }

    @Test
    fun appUpdateUrlValidator_acceptsPublicHttpsAndRejectsUnsafeSchemesOrLocalHosts() {
        assertTrue(
            AppUpdateUrlValidator.isSafeHttpsDownloadUrl(
                "https://firebasestorage.googleapis.com/v0/b/demo.appspot.com/o/app.apk?alt=media"
            )
        )
        assertFalse(AppUpdateUrlValidator.isSafeHttpsDownloadUrl("http://example.com/app.apk"))
        assertFalse(AppUpdateUrlValidator.isSafeHttpsDownloadUrl("javascript:alert(1)"))
        assertFalse(AppUpdateUrlValidator.isSafeHttpsDownloadUrl("file:///sdcard/app.apk"))
        assertFalse(AppUpdateUrlValidator.isSafeHttpsDownloadUrl("https://127.0.0.1/app.apk"))
        assertFalse(AppUpdateUrlValidator.isSafeHttpsDownloadUrl("https://192.168.1.10/app.apk"))
        assertFalse(AppUpdateUrlValidator.isSafeHttpsDownloadUrl("https://localhost/app.apk"))

        assertTrue(AppUpdateUrlValidator.isValidApkFileName("TruckWeightGuard-v1.2.apk"))
        assertFalse(AppUpdateUrlValidator.isValidApkFileName("../evil.apk"))
        assertFalse(AppUpdateUrlValidator.isValidApkFileName("script.sh"))
    }

    @Test
    fun appUpdateRelease_mapsNormalizePublisherRoleAndEmail() {
        val release = AppUpdateRelease(
            versionCode = 2L,
            versionName = "1.1.0",
            fileName = "TruckWeightGuard-v1.1.0.apk",
            fileSizeBytes = 31_000_000L,
            sha256Checksum = "A".repeat(64),
            downloadUrl = "https://example.com/TruckWeightGuard-v1.1.0.apk",
            releaseNotes = "Locked OCR fields",
            isMandatory = true
        )

        val map = release.toCreateMap(
            publisherEmail = "  Admin.Ops@Example.COM ",
            publisherRole = UserRole.USER,
            publisherUid = "uid-admin-1"
        )
        assertEquals("ADMIN", map["publishedByRole"])
        assertEquals("admin.ops@example.com", map["publishedByEmail"])
        assertEquals("a".repeat(64), map["sha256Checksum"])
        assertEquals(2L, map["versionCode"])
    }

    @Test
    fun appUpdateUrlValidator_validatesGoogleDriveIdsAndApiHosts() {
        assertTrue(AppUpdateUrlValidator.isSafeGoogleApiUrl("https://www.googleapis.com/upload/drive/v3/files?upload_id=abc123"))
        assertFalse(AppUpdateUrlValidator.isSafeGoogleApiUrl("https://evil-googleapis.com/upload"))
        assertFalse(AppUpdateUrlValidator.isSafeGoogleApiUrl("http://www.googleapis.com/upload"))

        assertTrue(AppUpdateUrlValidator.isValidDriveFileId("1AbCDefGhIjKlMnOpQrStUvWxYz012345"))
        assertFalse(AppUpdateUrlValidator.isValidDriveFileId("../bad"))
        assertFalse(AppUpdateUrlValidator.isValidDriveFileId("short"))

        assertEquals(
            "https://drive.google.com/uc?export=download&id=1AbCDefGhIjKlMnOpQrStUvWxYz012345",
            AppUpdateUrlValidator.buildGoogleDriveDirectDownloadUrl(" 1AbCDefGhIjKlMnOpQrStUvWxYz012345 ")
        )
    }
}
