package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.OperationType
import com.example.core.OwnerPolicy
import com.example.core.handleFirestoreError
import com.example.data.model.OwnerAdminConfig
import com.example.data.model.RuntimeSettings
import com.example.data.model.TruckWeightRecord
import com.example.domain.WeightVerificationEngine
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
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

    fun observeRecords(currentUserId: String, isOwner: Boolean): Flow<List<TruckWeightRecord>> = flow {
        require(currentUserId.isNotBlank()) { "Authenticated user ID is required." }
        val path = COLLECTION_RECORDS
        val query: Query = if (isOwner) {
            db.collection(COLLECTION_RECORDS)
                .orderBy("createdAt", Query.Direction.DESCENDING)
        } else {
            db.collection(COLLECTION_RECORDS)
                .whereEqualTo("userId", currentUserId)
        }

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

            val runtimeRef = db.collection(COLLECTION_SETTINGS).document(DOC_RUNTIME_CONFIG)
            docPath = runtimeRef.path
            val runtimeSnap = runtimeRef.get().await()
            if (runtimeSnap.exists()) {
                runtimeRef.update(runtime.toUpdateMap()).await()
            } else {
                runtimeRef.set(runtime.toCreateMap()).await()
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

    companion object {
        const val COLLECTION_RECORDS = "truck_weight_records"
        const val COLLECTION_SETTINGS = "app_settings"
        const val DOC_RUNTIME_CONFIG = "runtime_config"
        const val DOC_OWNER_ADMIN = "owner_admin"
    }
}
