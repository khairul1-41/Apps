package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.AppCheckTokenManager
import com.example.core.OperationType
import com.example.core.OwnerPolicy
import com.example.core.handleFirestoreError
import com.example.data.model.AdsBannerConfig
import com.example.data.model.AppUpdateRelease
import com.example.data.model.AuthorizedMember
import com.example.data.model.MemberAccessState
import com.example.data.model.OwnerAdminConfig
import com.example.data.model.RuntimeSettings
import com.example.data.model.SlipFormatTemplate
import com.example.data.model.TruckWeightRecord
import com.example.data.model.UserRole
import com.example.data.remote.AppUpdateUrlValidator
import com.example.domain.WeightVerificationEngine
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class TruckWeightRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeMemberAccess(user: FirebaseUser): Flow<MemberAccessState> = flow {
        if (OwnerPolicy.isOwner(user)) {
            emit(MemberAccessState.Authorized(UserRole.OWNER, user.displayName))
            return@flow
        }

        val normalizedEmail = OwnerPolicy.normalizeEmail(user.email)
        if (!user.isEmailVerified || !OwnerPolicy.isValidEmail(normalizedEmail)) {
            emit(
                MemberAccessState.Unauthorized(
                    email = normalizedEmail,
                    errorMessage = "A verified Google email account is required to access TruckWeight Guard."
                )
            )
            return@flow
        }

        val docRef = db.collection(COLLECTION_AUTHORIZED_USERS).document(normalizedEmail)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        val member = snapshot.toObject(
                            AuthorizedMember::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                        if (member != null) {
                            MemberAccessState.Authorized(
                                role = member.resolvedRole,
                                displayName = member.displayName?.takeIf { it.isNotBlank() } ?: user.displayName
                            )
                        } else {
                            MemberAccessState.Unauthorized(normalizedEmail)
                        }
                    } else {
                        MemberAccessState.Unauthorized(normalizedEmail)
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: error
                    handleFirestoreError(toLog, OperationType.GET, docRef.path)
                    emit(
                        MemberAccessState.Unauthorized(
                            email = normalizedEmail,
                            errorMessage = toLog.localizedMessage ?: "Failed to verify team access."
                        )
                    )
                }
        )
    }

    /**
     * All authorized team members (Users, Admins, and Owner) share access to all
     * weighbridge records across the organization.
     */
    fun observeRecords(currentUserId: String): Flow<List<TruckWeightRecord>> = flow {
        require(currentUserId.isNotBlank()) { "Authenticated user ID is required." }
        val path = COLLECTION_RECORDS
        val query: Query = db.collection(COLLECTION_RECORDS)
            .orderBy("createdAt", Query.Direction.DESCENDING)

        emitAll(
            query.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        TruckWeightRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { WeightVerificationEngine.recordSortMillis(it) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == currentUserId) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeAuthorizedMembers(canManageUsers: Boolean): Flow<List<AuthorizedMember>> {
        if (!canManageUsers) return flowOf(emptyList())
        val path = COLLECTION_AUTHORIZED_USERS
        return db.collection(COLLECTION_AUTHORIZED_USERS)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    AuthorizedMember::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedWith(
                    compareBy<AuthorizedMember> {
                        if (it.resolvedRole == UserRole.ADMIN) 0 else 1
                    }.thenBy { it.email }
                )
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.LIST, path)
                throw error
            }
    }

    suspend fun addOrUpdateMember(
        rawEmail: String,
        rawDisplayName: String,
        assignedRole: UserRole,
        callerRole: UserRole
    ): Result<AuthorizedMember> {
        var docPath = COLLECTION_AUTHORIZED_USERS
        return try {
            val user = auth.currentUser
                ?: throw IllegalStateException("Sign in required.")
            check(callerRole.canManageUsers) {
                "Only the Owner or an Admin can add team users."
            }
            if (assignedRole == UserRole.ADMIN && !callerRole.canManageAdmins) {
                throw IllegalStateException("Only the verified application owner can assign Admin privileges.")
            }
            if (assignedRole == UserRole.OWNER) {
                throw IllegalArgumentException("Owner role cannot be assigned.")
            }

            val normalizedEmail = OwnerPolicy.normalizeEmail(rawEmail)
            require(OwnerPolicy.isValidEmail(normalizedEmail)) {
                "Please enter a valid Google email address."
            }
            require(normalizedEmail != OwnerPolicy.OWNER_EMAIL) {
                "The application owner (${OwnerPolicy.OWNER_EMAIL}) already has permanent Owner access."
            }

            val callerEmail = OwnerPolicy.normalizeEmail(user.email)
            check(OwnerPolicy.isValidEmail(callerEmail) && user.isEmailVerified) {
                "A verified caller email is required."
            }

            val cleanName = rawDisplayName.trim().take(80)
            val docRef = db.collection(COLLECTION_AUTHORIZED_USERS).document(normalizedEmail)
            docPath = docRef.path
            val existing = docRef.get().await()

            val targetRole = if (assignedRole == UserRole.ADMIN) UserRole.ADMIN else UserRole.USER
            if (existing.exists()) {
                check(callerRole.canManageAdmins) {
                    "$normalizedEmail is already in the team directory. Only the Owner can modify existing member roles."
                }
                val updateMap = mutableMapOf<String, Any>(
                    "role" to targetRole.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (cleanName.isNotEmpty()) {
                    updateMap["displayName"] = cleanName
                }
                docRef.update(updateMap).await()
            } else {
                val member = AuthorizedMember(
                    id = normalizedEmail,
                    email = normalizedEmail,
                    displayName = cleanName.takeIf { it.isNotEmpty() },
                    role = targetRole.name,
                    addedByEmail = callerEmail,
                    addedByUid = user.uid
                )
                docRef.set(
                    member.toCreateMap(
                        normalizedEmail = normalizedEmail,
                        assignedRole = targetRole,
                        creatorEmail = callerEmail,
                        creatorUid = user.uid
                    )
                ).await()
            }

            Result.success(
                AuthorizedMember(
                    id = normalizedEmail,
                    email = normalizedEmail,
                    displayName = cleanName.takeIf { it.isNotEmpty() },
                    role = targetRole.name,
                    addedByEmail = callerEmail,
                    addedByUid = user.uid
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateMemberRole(
        member: AuthorizedMember,
        newRole: UserRole,
        callerRole: UserRole
    ): Result<Unit> {
        val docRef = db.collection(COLLECTION_AUTHORIZED_USERS).document(member.email)
        return try {
            requireUserId()
            check(callerRole.canManageAdmins) {
                "Only the verified Owner can promote or remove Admins."
            }
            require(newRole == UserRole.ADMIN || newRole == UserRole.USER) {
                "Target role must be Admin or User."
            }
            require(member.email.isNotBlank() && member.email != OwnerPolicy.OWNER_EMAIL) {
                "Invalid member email."
            }
            docRef.update(
                mapOf(
                    "role" to newRole.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteMember(
        member: AuthorizedMember,
        callerRole: UserRole
    ): Result<Unit> {
        val normalizedEmail = OwnerPolicy.normalizeEmail(member.email.ifBlank { member.id })
        val docRef = db.collection(COLLECTION_AUTHORIZED_USERS).document(normalizedEmail)
        return try {
            requireUserId()
            check(callerRole.canManageUsers) {
                "Only the Owner or an Admin can delete team users."
            }
            require(normalizedEmail.isNotBlank() && normalizedEmail != OwnerPolicy.OWNER_EMAIL) {
                "The application owner cannot be deleted."
            }
            if (member.resolvedRole == UserRole.ADMIN && !callerRole.canManageAdmins) {
                throw IllegalStateException("Only the verified Owner can delete an Admin.")
            }
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeRuntimeSettings(): Flow<RuntimeSettings> {
        val docRef = db.collection(COLLECTION_SETTINGS).document(DOC_RUNTIME_CONFIG)
        return docRef.snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        RuntimeSettings::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: RuntimeSettings()
                } else {
                    RuntimeSettings()
                }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.GET, docRef.path)
                emit(RuntimeSettings())
            }
    }

    fun observeOwnerAdminConfig(isOwner: Boolean): Flow<OwnerAdminConfig?> {
        if (!isOwner) return flowOf(null)
        val docRef = db.collection(COLLECTION_SETTINGS).document(DOC_OWNER_ADMIN)
        return docRef.snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        OwnerAdminConfig::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    OwnerAdminConfig(ownerEmail = auth.currentUser?.email ?: OwnerPolicy.OWNER_EMAIL)
                }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.GET, docRef.path)
                emit(null)
            }
    }

    suspend fun saveWeightRecord(record: TruckWeightRecord): Result<TruckWeightRecord> {
        var docPath = COLLECTION_RECORDS
        return try {
            val uid = requireUserId()
            require(record.vehicleNumber.trim().length in 2..64) {
                "Vehicle number must be between 2 and 64 characters."
            }
            require(record.vehicleKey.trim().length in 2..64) {
                "Normalized vehicle key is invalid."
            }
            require(record.truckWeight > 0.0 && record.truckWeight <= 500_000.0) {
                "Truck weight must be greater than 0 and at most 500,000 KG."
            }
            require(record.weightDate.trim().length in 1..40) {
                "Weight date is required."
            }

            val docRef = db.collection(COLLECTION_RECORDS).document()
            docPath = docRef.path
            val payload = record.toCreateMap(uid)
            docRef.set(payload).await()
            Result.success(record.copy(id = docRef.id, userId = uid))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateTelegramMetadata(
        recordId: String,
        telegramMessageId: String,
        telegramFileId: String
    ): Result<Unit> {
        var docPath = "$COLLECTION_RECORDS/$recordId"
        return try {
            requireUserId()
            require(recordId.isNotBlank()) { "Record ID must not be blank." }
            val docRef = db.collection(COLLECTION_RECORDS).document(recordId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "telegramMessageId" to telegramMessageId.trim().take(128),
                    "documentImageTelegramFileId" to telegramFileId.trim().take(256),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteRecord(recordId: String): Result<Unit> {
        var docPath = "$COLLECTION_RECORDS/$recordId"
        return try {
            requireUserId()
            require(recordId.isNotBlank()) { "Record ID must not be blank." }
            val docRef = db.collection(COLLECTION_RECORDS).document(recordId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveOwnerSettings(
        runtime: RuntimeSettings,
        ownerAdmin: OwnerAdminConfig
    ): Result<Unit> {
        var docPath = "$COLLECTION_SETTINGS/$DOC_RUNTIME_CONFIG"
        return try {
            val user = auth.currentUser
                ?: throw IllegalStateException("Sign in required.")
            check(OwnerPolicy.isOwner(user)) {
                "Only the verified application owner can modify settings."
            }
            require(runtime.neutralMaxKg >= 0.0 && runtime.greenMaxKg >= runtime.neutralMaxKg && runtime.orangeMaxKg >= runtime.greenMaxKg) {
                "Thresholds must follow: Neutral <= Green <= Orange."
            }
            val cleanToken = runtime.appCheckDebugToken?.trim().orEmpty()
            if (cleanToken.isNotEmpty()) {
                require(AppCheckTokenManager.isValidDebugTokenUuid(cleanToken)) {
                    "Firebase App Check Debug Token must be a valid UUID (e.g. 95158850-D125-410E-A80F-3CF42093E1BB)."
                }
            }

            val normalizedRuntime = runtime.copy(
                appCheckDebugToken = if (cleanToken.isEmpty()) null else AppCheckTokenManager.normalizeToken(cleanToken)
            )

            val runtimeRef = db.collection(COLLECTION_SETTINGS).document(DOC_RUNTIME_CONFIG)
            docPath = runtimeRef.path
            val runtimeSnap = runtimeRef.get().await()
            if (runtimeSnap.exists()) {
                runtimeRef.update(normalizedRuntime.toUpdateMap()).await()
            } else {
                runtimeRef.set(normalizedRuntime.toCreateMap()).await()
            }

            val ownerRef = db.collection(COLLECTION_SETTINGS).document(DOC_OWNER_ADMIN)
            docPath = ownerRef.path
            val ownerSnap = ownerRef.get().await()
            if (ownerSnap.exists()) {
                ownerRef.update(ownerAdmin.toUpdateMap()).await()
            } else {
                ownerRef.set(ownerAdmin.toCreateMap()).await()
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    fun observeAdsConfig(): Flow<AdsBannerConfig> {
        val docRef = db.collection(COLLECTION_SETTINGS).document(DOC_ADS_CONFIG)
        return docRef.snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        AdsBannerConfig::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: AdsBannerConfig()
                } else {
                    AdsBannerConfig()
                }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.GET, docRef.path)
                emit(AdsBannerConfig())
            }
    }

    suspend fun saveAdsConfig(config: AdsBannerConfig): Result<Unit> {
        val docRef = db.collection(COLLECTION_SETTINGS).document(DOC_ADS_CONFIG)
        return try {
            val user = auth.currentUser
                ?: throw IllegalStateException("Sign in required.")
            check(OwnerPolicy.isOwner(user)) {
                "Only the verified application owner can configure ads."
            }
            val callerEmail = OwnerPolicy.normalizeEmail(user.email)
            check(OwnerPolicy.isValidEmail(callerEmail) && user.isEmailVerified) {
                "Verified owner email is required."
            }
            val cleanTargetUrl = config.sponsorTargetUrl.trim()
            if (cleanTargetUrl.isNotEmpty()) {
                require(AppUpdateUrlValidator.isSafeHttpsDownloadUrl(cleanTargetUrl)) {
                    "Sponsored link URL must begin with https:// and point to a valid host."
                }
            }

            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(config.toUpdateMap(callerEmail)).await()
            } else {
                docRef.set(config.toCreateMap(callerEmail)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeSlipTemplates(): Flow<List<SlipFormatTemplate>> {
        val path = COLLECTION_SLIP_TEMPLATES
        return db.collection(COLLECTION_SLIP_TEMPLATES)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    SlipFormatTemplate::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.LIST, path)
                emit(emptyList())
            }
    }

    suspend fun saveSlipTemplate(template: SlipFormatTemplate): Result<SlipFormatTemplate> {
        var docPath = COLLECTION_SLIP_TEMPLATES
        return try {
            val user = auth.currentUser
                ?: throw IllegalStateException("Sign in required.")
            check(OwnerPolicy.isOwner(user)) {
                "Only the verified application owner can upload custom slip formats."
            }
            val callerEmail = OwnerPolicy.normalizeEmail(user.email)
            check(OwnerPolicy.isValidEmail(callerEmail) && user.isEmailVerified) {
                "Verified owner email is required."
            }
            val cleanName = template.formatName.trim()
            require(cleanName.length in 2..80) {
                "Slip format name must be between 2 and 80 characters."
            }
            val vBox = template.vehicleBox
            require(vBox != null && vBox.isValid()) {
                "Please mark the Vehicle / Truck Number box on the sample slip image."
            }
            val wBox = template.weightBox
            require(wBox != null && wBox.isValid()) {
                "Please mark the Truck Weight (Empty/Tare) box on the sample slip image."
            }

            val docRef = db.collection(COLLECTION_SLIP_TEMPLATES).document()
            docPath = docRef.path
            val payload = template.toCreateMap(ownerEmail = callerEmail, ownerUid = user.uid)
            docRef.set(payload).await()
            Result.success(
                template.copy(
                    id = docRef.id,
                    formatName = cleanName,
                    createdByEmail = callerEmail,
                    createdByUid = user.uid
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteSlipTemplate(templateId: String): Result<Unit> {
        val docRef = db.collection(COLLECTION_SLIP_TEMPLATES).document(templateId)
        return try {
            val user = auth.currentUser
                ?: throw IllegalStateException("Sign in required.")
            check(OwnerPolicy.isOwner(user)) {
                "Only the verified application owner can delete slip formats."
            }
            require(templateId.isNotBlank()) { "Template ID must not be blank." }
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeAppUpdateRelease(): Flow<AppUpdateRelease?> {
        val docRef = db.collection(COLLECTION_SETTINGS).document(DOC_APP_UPDATE)
        return docRef.snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        AppUpdateRelease::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                handleFirestoreError(firestoreError ?: error, OperationType.GET, docRef.path)
                emit(null)
            }
    }

    suspend fun publishAppUpdate(
        release: AppUpdateRelease,
        callerRole: UserRole
    ): Result<AppUpdateRelease> {
        val docRef = db.collection(COLLECTION_SETTINGS).document(DOC_APP_UPDATE)
        return try {
            val user = auth.currentUser
                ?: throw IllegalStateException("Google Sign-In required.")
            check(callerRole.canManageUsers) {
                "Only the Owner or an Admin can upload and publish app updates."
            }
            val callerEmail = OwnerPolicy.normalizeEmail(user.email)
            check(OwnerPolicy.isValidEmail(callerEmail) && user.isEmailVerified) {
                "A verified Google email account is required."
            }
            require(release.versionCode in 1L..999_999_999L) {
                "Version code must be at least 1."
            }
            require(release.versionName.trim().length in 1..32) {
                "Version name is required (max 32 characters)."
            }
            require(AppUpdateUrlValidator.isValidApkFileName(release.fileName)) {
                "File name must end with .apk."
            }
            require(release.fileSizeBytes in 1024L..1_073_741_824L) {
                "APK size must be between 1 KB and 1 GB."
            }
            require(release.sha256Checksum.trim().matches(Regex("^[a-fA-F0-9]{64}$"))) {
                "Invalid APK SHA-256 checksum."
            }
            require(AppUpdateUrlValidator.isSafeHttpsDownloadUrl(release.downloadUrl)) {
                "Download URL must be a valid public HTTPS link."
            }

            val effectiveRole = if (OwnerPolicy.isOwner(user)) UserRole.OWNER else UserRole.ADMIN
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(
                    release.toUpdateMap(
                        publisherEmail = callerEmail,
                        publisherRole = effectiveRole,
                        publisherUid = user.uid
                    )
                ).await()
            } else {
                docRef.set(
                    release.toCreateMap(
                        publisherEmail = callerEmail,
                        publisherRole = effectiveRole,
                        publisherUid = user.uid
                    )
                ).await()
            }

            Result.success(
                release.copy(
                    publishedByEmail = callerEmail,
                    publishedByRole = effectiveRole.name,
                    publishedByUid = user.uid
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun clearAppUpdate(callerRole: UserRole): Result<Unit> {
        val docRef = db.collection(COLLECTION_SETTINGS).document(DOC_APP_UPDATE)
        return try {
            requireUserId()
            check(callerRole.canManageUsers) {
                "Only the Owner or an Admin can remove an app update notification."
            }
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    companion object {
        const val COLLECTION_AUTHORIZED_USERS = "authorized_users"
        const val COLLECTION_RECORDS = "truck_weight_records"
        const val COLLECTION_SETTINGS = "app_settings"
        const val COLLECTION_SLIP_TEMPLATES = "slip_templates"
        const val DOC_RUNTIME_CONFIG = "runtime_config"
        const val DOC_OWNER_ADMIN = "owner_admin"
        const val DOC_ADS_CONFIG = "ads_config"
        const val DOC_APP_UPDATE = "app_update"
    }
}
