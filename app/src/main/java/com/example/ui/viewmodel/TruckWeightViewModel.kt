package com.example.ui.viewmodel

import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.core.OwnerFirebaseConfig
import com.example.core.OwnerFirebaseConfigStore
import com.example.core.OwnerPolicy
import com.example.data.model.AdsBannerConfig
import com.example.data.model.AlertStatus
import com.example.data.model.AppUpdateRelease
import com.example.data.model.AuthorizedMember
import com.example.data.model.OcrExtractionResult
import com.example.data.model.OwnerAdminConfig
import com.example.data.model.RuntimeSettings
import com.example.data.model.SlipFormatTemplate
import com.example.data.model.TruckWeightRecord
import com.example.data.model.UiState
import com.example.data.model.UserRole
import com.example.data.model.VerificationComparison
import com.example.data.ocr.WeighbridgeOcrService
import com.example.data.remote.ApkInspectionResult
import com.example.data.remote.AppUpdateUploadService
import com.example.data.remote.AppUpdateUrlValidator
import com.example.data.remote.DriveTokenExpiredException
import com.example.data.remote.TelegramDispatchResult
import com.example.data.remote.TelegramRelayService
import com.example.data.repository.TruckWeightRepository
import com.example.domain.WeightVerificationEngine
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "TruckWeightVM"

data class EditableAnalysisForm(
    val vehicleNumberInput: String = "",
    val weightInput: String = "",
    val weightDateInput: String = "",
    val sourceLabelInput: String = "Tare Weight",
    val notesInput: String = "",
    val extraction: OcrExtractionResult? = null,
    val comparison: VerificationComparison = VerificationComparison(
        currentWeightKg = null,
        previousWeightKg = null,
        previousRecordDate = null,
        previousRecordId = null,
        weightDifferenceKg = 0.0,
        alertStatus = AlertStatus.NEUTRAL,
        isDuplicateRisk = false
    ),
    val duplicateOverrideConfirmed: Boolean = false
)

sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState
    data object Analyzing : AnalysisUiState
    data class Review(
        val form: EditableAnalysisForm,
        val validationError: String? = null
    ) : AnalysisUiState

    data class Saving(val form: EditableAnalysisForm) : AnalysisUiState
    data class Completed(
        val savedRecord: TruckWeightRecord,
        val telegramResult: TelegramDispatchResult,
        val originalImageUri: Uri?
    ) : AnalysisUiState

    data class Error(val message: String) : AnalysisUiState
}

data class HistoryFilterState(
    val searchQuery: String = "",
    val selectedAlertStatus: AlertStatus? = null,
    val dateFilterQuery: String = ""
)

sealed interface SettingsActionState {
    data object Idle : SettingsActionState
    data object Saving : SettingsActionState
    data class Success(val message: String) : SettingsActionState
    data class Error(val message: String) : SettingsActionState
}

sealed interface OwnerConfigActionState {
    data object Idle : OwnerConfigActionState
    data object Saving : OwnerConfigActionState
    data class Success(val message: String) : OwnerConfigActionState
    data class Error(val message: String) : OwnerConfigActionState
}

sealed interface TeamActionState {
    data object Idle : TeamActionState
    data object Saving : TeamActionState
    data class Success(val message: String) : TeamActionState
    data class Error(val message: String) : TeamActionState
}

sealed interface StagedApkState {
    data object Idle : StagedApkState
    data object Inspecting : StagedApkState
    data class Ready(val apk: ApkInspectionResult) : StagedApkState
    data class Error(val message: String) : StagedApkState
}

sealed interface AppUpdatePublishState {
    data object Idle : AppUpdatePublishState
    data class Uploading(val progressPercent: Int, val statusLabel: String) : AppUpdatePublishState
    data class Success(val message: String) : AppUpdatePublishState
    data class Error(val message: String) : AppUpdatePublishState
}

sealed interface AdsActionState {
    data object Idle : AdsActionState
    data object Saving : AdsActionState
    data class Success(val message: String) : AdsActionState
    data class Error(val message: String) : AdsActionState
}

sealed interface SlipTemplateActionState {
    data object Idle : SlipTemplateActionState
    data object Saving : SlipTemplateActionState
    data class Success(val message: String) : SlipTemplateActionState
    data class Error(val message: String) : SlipTemplateActionState
}

class TruckWeightViewModel(
    private val repository: TruckWeightRepository,
    private val ocrService: WeighbridgeOcrService,
    private val telegramRelayService: TelegramRelayService,
    private val appUpdateUploadService: AppUpdateUploadService,
    private val ownerConfigStore: OwnerFirebaseConfigStore,
    val currentUserId: String,
    val userEmail: String,
    val userRole: UserRole
) : ViewModel() {

    val isOwner: Boolean
        get() = userRole == UserRole.OWNER

    val canManageUsers: Boolean
        get() = userRole.canManageUsers

    val canManageAdmins: Boolean
        get() = userRole.canManageAdmins

    fun canDeleteRecord(record: TruckWeightRecord): Boolean =
        canManageUsers || record.userId == currentUserId

    val recordsState: StateFlow<UiState<List<TruckWeightRecord>>> =
        repository.observeRecords(currentUserId)
            .map<List<TruckWeightRecord>, UiState<List<TruckWeightRecord>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing truck weight records", rootError)
                emit(UiState.Error(rootError.localizedMessage ?: "Failed to load weight records."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    val authorizedMembersState: StateFlow<UiState<List<AuthorizedMember>>> =
        (if (canManageUsers) {
            repository.observeAuthorizedMembers(canManageUsers = true)
                .map<List<AuthorizedMember>, UiState<List<AuthorizedMember>>> { UiState.Success(it) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(TAG, "Error observing authorized members", rootError)
                    emit(UiState.Error(rootError.localizedMessage ?: "Failed to load team directory."))
                }
        } else {
            flowOf(UiState.Success(emptyList()))
        }).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    val runtimeSettings: StateFlow<RuntimeSettings> =
        repository.observeRuntimeSettings()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = RuntimeSettings()
            )

    val ownerAdminConfig: StateFlow<OwnerAdminConfig?> =
        repository.observeOwnerAdminConfig(isOwner)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = if (isOwner) OwnerAdminConfig(ownerEmail = userEmail) else null
            )

    private val _originalImageUri = MutableStateFlow<Uri?>(null)
    val originalImageUri: StateFlow<Uri?> = _originalImageUri.asStateFlow()

    private val _workingImageUri = MutableStateFlow<Uri?>(null)
    val workingImageUri: StateFlow<Uri?> = _workingImageUri.asStateFlow()

    private val _isProcessingImage = MutableStateFlow(false)
    val isProcessingImage: StateFlow<Boolean> = _isProcessingImage.asStateFlow()

    private val _imageError = MutableStateFlow<String?>(null)
    val imageError: StateFlow<String?> = _imageError.asStateFlow()

    private val _analysisState = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val analysisState: StateFlow<AnalysisUiState> = _analysisState.asStateFlow()

    private val _historyFilter = MutableStateFlow(HistoryFilterState())
    val historyFilter: StateFlow<HistoryFilterState> = _historyFilter.asStateFlow()

    private val _settingsActionState = MutableStateFlow<SettingsActionState>(SettingsActionState.Idle)
    val settingsActionState: StateFlow<SettingsActionState> = _settingsActionState.asStateFlow()

    private val _teamActionState = MutableStateFlow<TeamActionState>(TeamActionState.Idle)
    val teamActionState: StateFlow<TeamActionState> = _teamActionState.asStateFlow()

    private val _retryingTelegramRecordId = MutableStateFlow<String?>(null)
    val retryingTelegramRecordId: StateFlow<String?> = _retryingTelegramRecordId.asStateFlow()

    val savedOwnerFirebaseConfig: StateFlow<OwnerFirebaseConfig?> =
        (if (isOwner) ownerConfigStore.configFlow else flowOf(null))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null
            )

    private val _ownerConfigActionState = MutableStateFlow<OwnerConfigActionState>(OwnerConfigActionState.Idle)
    val ownerConfigActionState: StateFlow<OwnerConfigActionState> = _ownerConfigActionState.asStateFlow()

    private val _transientMessage = MutableStateFlow<String?>(null)
    val transientMessage: StateFlow<String?> = _transientMessage.asStateFlow()

    val adsConfig: StateFlow<AdsBannerConfig> =
        repository.observeAdsConfig()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = AdsBannerConfig()
            )

    private val _adsActionState = MutableStateFlow<AdsActionState>(AdsActionState.Idle)
    val adsActionState: StateFlow<AdsActionState> = _adsActionState.asStateFlow()

    val slipTemplates: StateFlow<List<SlipFormatTemplate>> =
        repository.observeSlipTemplates()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList()
            )

    private val _slipTemplateActionState = MutableStateFlow<SlipTemplateActionState>(SlipTemplateActionState.Idle)
    val slipTemplateActionState: StateFlow<SlipTemplateActionState> = _slipTemplateActionState.asStateFlow()

    val appUpdateRelease: StateFlow<AppUpdateRelease?> =
        repository.observeAppUpdateRelease()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null
            )

    private val _dismissedUpdateVersionCode = MutableStateFlow<Long?>(null)

    val activeUpdatePopup: StateFlow<AppUpdateRelease?> = combine(
        appUpdateRelease,
        _dismissedUpdateVersionCode
    ) { release, dismissedVersion ->
        if (release != null &&
            release.versionCode > BuildConfig.VERSION_CODE.toLong() &&
            AppUpdateUrlValidator.isSafeHttpsDownloadUrl(release.downloadUrl) &&
            (release.isMandatory || dismissedVersion != release.versionCode)
        ) {
            release
        } else {
            null
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = null
    )

    private val _stagedApkState = MutableStateFlow<StagedApkState>(StagedApkState.Idle)
    val stagedApkState: StateFlow<StagedApkState> = _stagedApkState.asStateFlow()

    private val _appUpdatePublishState = MutableStateFlow<AppUpdatePublishState>(AppUpdatePublishState.Idle)
    val appUpdatePublishState: StateFlow<AppUpdatePublishState> = _appUpdatePublishState.asStateFlow()

    // Held strictly in memory; never persisted to preferences or Firestore.
    private val _driveAccessToken = MutableStateFlow<String?>(null)
    val hasDriveAccess: StateFlow<Boolean> = _driveAccessToken
        .map { !it.isNullOrBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = false
        )

    val filteredRecords: StateFlow<List<TruckWeightRecord>> = combine(
        recordsState,
        _historyFilter
    ) { state, filter ->
        val list = (state as? UiState.Success)?.data.orEmpty()
        val queryKey = WeightVerificationEngine.normalizeVehicleKey(filter.searchQuery)
        val rawSearch = filter.searchQuery.trim().lowercase()
        val dateFilter = filter.dateFilterQuery.trim().lowercase()

        list.filter { rec ->
            val matchesSearch = if (rawSearch.isEmpty()) {
                true
            } else {
                rec.vehicleNumber.lowercase().contains(rawSearch) ||
                    (queryKey.isNotEmpty() && rec.vehicleKey.contains(queryKey))
            }
            val matchesAlert = filter.selectedAlertStatus == null ||
                rec.resolvedAlertStatus == filter.selectedAlertStatus
            val matchesDate = if (dateFilter.isEmpty()) {
                true
            } else {
                rec.weightDate.lowercase().contains(dateFilter) ||
                    WeightVerificationEngine.formatTimestamp(rec.createdAt).lowercase().contains(dateFilter)
            }
            matchesSearch && matchesAlert && matchesDate
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    fun clearTransientMessage() {
        _transientMessage.value = null
    }

    fun preparePickedImage(uri: Uri, onReady: () -> Unit) {
        viewModelScope.launch {
            _isProcessingImage.value = true
            _imageError.value = null
            _analysisState.value = AnalysisUiState.Idle
            try {
                val cached = ocrService.copyUriToEditableCache(uri)
                _originalImageUri.value = cached
                _workingImageUri.value = cached
                onReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load selected image", e)
                _imageError.value = e.localizedMessage ?: "Failed to load image."
                _transientMessage.value = _imageError.value
            } finally {
                _isProcessingImage.value = false
            }
        }
    }

    fun setCapturedFileUri(uri: Uri) {
        _originalImageUri.value = uri
        _workingImageUri.value = uri
        _imageError.value = null
        _analysisState.value = AnalysisUiState.Idle
    }

    fun rotateWorkingImage(degrees: Float) {
        val current = _workingImageUri.value ?: return
        viewModelScope.launch {
            _isProcessingImage.value = true
            _imageError.value = null
            try {
                val rotated = ocrService.rotateSlipImage(current, degrees)
                _workingImageUri.value = rotated
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Image rotation failed", e)
                _imageError.value = e.localizedMessage ?: "Failed to rotate image."
            } finally {
                _isProcessingImage.value = false
            }
        }
    }

    fun cropWorkingImage(normalizedCrop: RectF) {
        val current = _workingImageUri.value ?: return
        viewModelScope.launch {
            _isProcessingImage.value = true
            _imageError.value = null
            try {
                val cropped = ocrService.cropSlipImage(current, normalizedCrop)
                _workingImageUri.value = cropped
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Image crop failed", e)
                _imageError.value = e.localizedMessage ?: "Failed to crop image."
            } finally {
                _isProcessingImage.value = false
            }
        }
    }

    fun resetWorkingImageToOriginal() {
        _workingImageUri.value = _originalImageUri.value
        _imageError.value = null
    }

    fun runOcrAnalysis() {
        val targetUri = _workingImageUri.value ?: _originalImageUri.value
        if (targetUri == null) {
            _analysisState.value = AnalysisUiState.Error("No document image selected.")
            return
        }

        viewModelScope.launch {
            _analysisState.value = AnalysisUiState.Analyzing
            try {
                val settings = runtimeSettings.value
                val strictSecond = ownerAdminConfig.value?.ocrStrictFinalWeight ?: true
                val templates = slipTemplates.value
                val extraction = ocrService.analyzeDocument(
                    imageUri = targetUri,
                    strictSecondWeightPriority = strictSecond,
                    minConfidenceThreshold = settings.minOcrConfidence,
                    customTemplates = templates
                )
                val existing = (recordsState.value as? UiState.Success)?.data.orEmpty()
                val weightText = extraction.truckWeightKg?.let {
                    if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
                }.orEmpty()
                val dateText = extraction.weightDate.ifBlank {
                    WeightVerificationEngine.currentFallbackDateString()
                }
                val comparison = WeightVerificationEngine.buildComparison(
                    vehicleNumberRaw = extraction.vehicleNumber,
                    currentWeightKg = extraction.truckWeightKg,
                    weightDate = dateText,
                    existingRecords = existing,
                    settings = settings
                )
                _analysisState.value = AnalysisUiState.Review(
                    form = EditableAnalysisForm(
                        vehicleNumberInput = extraction.vehicleNumber,
                        weightInput = weightText,
                        weightDateInput = dateText,
                        sourceLabelInput = extraction.sourceLabel,
                        notesInput = extraction.reasoning,
                        extraction = extraction,
                        comparison = comparison
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OCR analysis failed", e)
                _analysisState.value = AnalysisUiState.Error(
                    e.localizedMessage ?: "OCR extraction failed. You can enter values manually or retry."
                )
            }
        }
    }

    fun setDuplicateOverrideConfirmed(confirmed: Boolean) {
        val currentReview = _analysisState.value as? AnalysisUiState.Review ?: return
        _analysisState.value = currentReview.copy(
            form = currentReview.form.copy(duplicateOverrideConfirmed = confirmed),
            validationError = null
        )
    }

    fun confirmAndSaveRecord() {
        val currentReview = _analysisState.value as? AnalysisUiState.Review ?: return
        val form = currentReview.form

        val displayVehicle = WeightVerificationEngine.normalizeVehicleDisplay(form.vehicleNumberInput)
        val vehicleKey = WeightVerificationEngine.normalizeVehicleKey(displayVehicle)
        val parsedWeight = WeightVerificationEngine.parseWeightNumber(form.weightInput)
        val cleanDate = form.weightDateInput.trim()

        val errorMsg = when {
            displayVehicle.length < 2 || vehicleKey.length < 2 ->
                "OCR could not read a valid Vehicle Number. Please re-crop or rescan the weighbridge slip."
            parsedWeight == null || parsedWeight <= 0.0 ->
                "OCR could not read a valid Truck Weight greater than 0 KG. Please re-crop or rescan the slip."
            cleanDate.isEmpty() ->
                "OCR could not read a valid Weight Date. Please re-crop or rescan the slip."
            form.comparison.isDuplicateRisk && !form.duplicateOverrideConfirmed ->
                "A record for $displayVehicle with ${WeightVerificationEngine.formatWeightKg(parsedWeight)} on $cleanDate already exists. Toggle 'Confirm Duplicate Save' if intentional."
            else -> null
        }

        if (errorMsg != null) {
            _analysisState.value = currentReview.copy(validationError = errorMsg)
            return
        }

        val existing = (recordsState.value as? UiState.Success)?.data.orEmpty()
        val settings = runtimeSettings.value
        val freshComparison = WeightVerificationEngine.buildComparison(
            vehicleNumberRaw = displayVehicle,
            currentWeightKg = parsedWeight,
            weightDate = cleanDate,
            existingRecords = existing,
            settings = settings
        )

        val recordToSave = TruckWeightRecord(
            userId = currentUserId,
            createdByEmail = OwnerPolicy.normalizeEmail(userEmail).ifEmpty { null },
            createdByRole = userRole.name,
            vehicleNumber = displayVehicle,
            vehicleKey = vehicleKey,
            truckWeight = parsedWeight!!,
            weightDate = cleanDate,
            previousWeight = freshComparison.previousWeightKg,
            weightDifference = freshComparison.weightDifferenceKg,
            alertStatus = freshComparison.alertStatus.name,
            ocrConfidence = form.extraction?.confidence ?: 1.0,
            sourceLabel = form.sourceLabelInput.trim().ifEmpty { "Manual Entry" },
            notes = form.notesInput.trim().takeIf { it.isNotEmpty() }
        )

        viewModelScope.launch {
            _analysisState.value = AnalysisUiState.Saving(form)
            val saveResult = repository.saveWeightRecord(recordToSave)
            saveResult.fold(
                onSuccess = { saved ->
                    val imageToSend = _originalImageUri.value ?: _workingImageUri.value
                    val telegramResult = if (settings.autoSendTelegram && imageToSend != null) {
                        val dispatch = telegramRelayService.dispatchRecordPhoto(
                            relayUrl = settings.telegramRelayUrl,
                            record = saved,
                            imageUri = imageToSend
                        )
                        if (dispatch is TelegramDispatchResult.Sent) {
                            repository.updateTelegramMetadata(
                                recordId = saved.id,
                                telegramMessageId = dispatch.messageId,
                                telegramFileId = dispatch.fileId
                            )
                        }
                        dispatch
                    } else {
                        TelegramDispatchResult.NotConfigured(
                            if (!settings.autoSendTelegram) {
                                "Auto-send to Telegram is disabled in Settings."
                            } else {
                                "Original image URI unavailable for upload."
                            }
                        )
                    }

                    val finalRecord = if (telegramResult is TelegramDispatchResult.Sent) {
                        saved.copy(
                            telegramMessageId = telegramResult.messageId,
                            documentImageTelegramFileId = telegramResult.fileId
                        )
                    } else {
                        saved
                    }

                    _analysisState.value = AnalysisUiState.Completed(
                        savedRecord = finalRecord,
                        telegramResult = telegramResult,
                        originalImageUri = imageToSend
                    )
                },
                onFailure = { error ->
                    _analysisState.value = AnalysisUiState.Review(
                        form = form,
                        validationError = error.localizedMessage ?: "Failed to save record to Firestore."
                    )
                }
            )
        }
    }

    fun retryTelegramFromCompleted() {
        val completed = _analysisState.value as? AnalysisUiState.Completed ?: return
        val imageUri = completed.originalImageUri ?: return
        val relayUrl = runtimeSettings.value.telegramRelayUrl
        viewModelScope.launch {
            _retryingTelegramRecordId.value = completed.savedRecord.id
            val result = telegramRelayService.dispatchRecordPhoto(
                relayUrl = relayUrl,
                record = completed.savedRecord,
                imageUri = imageUri
            )
            if (result is TelegramDispatchResult.Sent) {
                repository.updateTelegramMetadata(
                    recordId = completed.savedRecord.id,
                    telegramMessageId = result.messageId,
                    telegramFileId = result.fileId
                )
                _analysisState.value = completed.copy(
                    savedRecord = completed.savedRecord.copy(
                        telegramMessageId = result.messageId,
                        documentImageTelegramFileId = result.fileId
                    ),
                    telegramResult = result
                )
            } else {
                _analysisState.value = completed.copy(telegramResult = result)
            }
            _retryingTelegramRecordId.value = null
        }
    }

    fun resetAnalysis() {
        _analysisState.value = AnalysisUiState.Idle
    }

    fun updateSearchQuery(query: String) {
        _historyFilter.update { it.copy(searchQuery = query) }
    }

    fun updateAlertFilter(status: AlertStatus?) {
        _historyFilter.update { it.copy(selectedAlertStatus = status) }
    }

    fun updateDateFilter(dateQuery: String) {
        _historyFilter.update { it.copy(dateFilterQuery = dateQuery) }
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            repository.deleteRecord(recordId).fold(
                onSuccess = { _transientMessage.value = "Weight record deleted." },
                onFailure = { err -> _transientMessage.value = err.localizedMessage ?: "Delete failed." }
            )
        }
    }

    fun saveOwnerSettings(
        neutralMaxKg: Double,
        greenMaxKg: Double,
        orangeMaxKg: Double,
        minOcrConfidence: Double,
        autoSendTelegram: Boolean,
        telegramRelayUrl: String,
        telegramChatIdLabel: String,
        firebaseConfigNote: String,
        ocrStrictFinalWeight: Boolean
    ) {
        if (!isOwner) {
            _settingsActionState.value = SettingsActionState.Error(
                "Access denied: Only the verified application owner can modify settings."
            )
            return
        }
        viewModelScope.launch {
            _settingsActionState.value = SettingsActionState.Saving
            val runtime = RuntimeSettings(
                neutralMaxKg = neutralMaxKg,
                greenMaxKg = greenMaxKg,
                orangeMaxKg = orangeMaxKg,
                minOcrConfidence = minOcrConfidence,
                autoSendTelegram = autoSendTelegram,
                telegramRelayUrl = telegramRelayUrl.trim(),
                appCheckDebugToken = null
            )
            val admin = OwnerAdminConfig(
                ownerEmail = userEmail,
                ocrStrictFinalWeight = ocrStrictFinalWeight,
                telegramChatIdLabel = telegramChatIdLabel.trim(),
                firebaseConfigNote = firebaseConfigNote.trim()
            )
            repository.saveOwnerSettings(runtime, admin).fold(
                onSuccess = {
                    _settingsActionState.value = SettingsActionState.Success(
                        "Owner settings saved and synced to Firestore."
                    )
                },
                onFailure = { error ->
                    _settingsActionState.value = SettingsActionState.Error(
                        error.localizedMessage ?: "Failed to save owner settings."
                    )
                }
            )
        }
    }

    fun clearSettingsActionState() {
        _settingsActionState.value = SettingsActionState.Idle
    }

    fun saveOwnerAdsConfig(config: AdsBannerConfig) {
        if (!isOwner) {
            _adsActionState.value = AdsActionState.Error(
                "Access denied: Only the verified owner can manage Ads settings."
            )
            return
        }
        val validationError = com.example.ui.components.AdsCodeValidator.validateAdsConfig(config)
        if (validationError != null) {
            _adsActionState.value = AdsActionState.Error(validationError)
            return
        }
        viewModelScope.launch {
            _adsActionState.value = AdsActionState.Saving
            repository.saveAdsConfig(config).fold(
                onSuccess = {
                    _adsActionState.value = AdsActionState.Success(
                        if (config.adsEnabled) {
                            "Owner Ads settings saved and activated."
                        } else {
                            "Ads disabled and settings saved."
                        }
                    )
                },
                onFailure = { err ->
                    _adsActionState.value = AdsActionState.Error(
                        err.localizedMessage ?: "Failed to save Ads configuration."
                    )
                }
            )
        }
    }

    fun clearAdsActionState() {
        _adsActionState.value = AdsActionState.Idle
    }

    fun saveSlipTemplate(template: SlipFormatTemplate) {
        if (!isOwner) {
            _slipTemplateActionState.value = SlipTemplateActionState.Error(
                "Access denied: Only the verified owner can upload custom slip formats."
            )
            return
        }
        viewModelScope.launch {
            _slipTemplateActionState.value = SlipTemplateActionState.Saving
            repository.saveSlipTemplate(template).fold(
                onSuccess = { saved ->
                    _slipTemplateActionState.value = SlipTemplateActionState.Success(
                        "Uploaded \"${saved.formatName}\". All users' OCR scans will now extract data using your marked positions."
                    )
                },
                onFailure = { err ->
                    _slipTemplateActionState.value = SlipTemplateActionState.Error(
                        err.localizedMessage ?: "Failed to upload custom slip format."
                    )
                }
            )
        }
    }

    fun deleteSlipTemplate(templateId: String) {
        if (!isOwner) return
        viewModelScope.launch {
            _slipTemplateActionState.value = SlipTemplateActionState.Saving
            repository.deleteSlipTemplate(templateId).fold(
                onSuccess = {
                    _slipTemplateActionState.value = SlipTemplateActionState.Success("Slip format removed.")
                },
                onFailure = { err ->
                    _slipTemplateActionState.value = SlipTemplateActionState.Error(
                        err.localizedMessage ?: "Failed to delete slip format."
                    )
                }
            )
        }
    }

    fun clearSlipTemplateActionState() {
        _slipTemplateActionState.value = SlipTemplateActionState.Idle
    }

    fun saveOwnerFirebaseConfig(config: OwnerFirebaseConfig) {
        if (!isOwner) {
            _ownerConfigActionState.value = OwnerConfigActionState.Error(
                "Access denied: Only the verified application owner can modify Firebase configuration."
            )
            return
        }
        viewModelScope.launch {
            _ownerConfigActionState.value = OwnerConfigActionState.Saving
            ownerConfigStore.saveConfig(config).fold(
                onSuccess = { saved ->
                    _ownerConfigActionState.value = OwnerConfigActionState.Success(
                        "Firebase project configuration (${saved.projectId}) encrypted and saved to application preferences."
                    )
                },
                onFailure = { err ->
                    _ownerConfigActionState.value = OwnerConfigActionState.Error(
                        err.localizedMessage ?: "Failed to save Firebase project configuration."
                    )
                }
            )
        }
    }

    fun clearOwnerFirebaseConfig() {
        if (!isOwner) return
        viewModelScope.launch {
            _ownerConfigActionState.value = OwnerConfigActionState.Saving
            ownerConfigStore.clearConfig().fold(
                onSuccess = {
                    _ownerConfigActionState.value = OwnerConfigActionState.Success(
                        "Encrypted Firebase project configuration cleared from local preferences."
                    )
                },
                onFailure = { err ->
                    _ownerConfigActionState.value = OwnerConfigActionState.Error(
                        err.localizedMessage ?: "Failed to clear saved configuration."
                    )
                }
            )
        }
    }

    fun clearOwnerConfigActionState() {
        _ownerConfigActionState.value = OwnerConfigActionState.Idle
    }

    fun addTeamMember(email: String, displayName: String, requestedRole: UserRole) {
        val effectiveRole = if (canManageAdmins && requestedRole == UserRole.ADMIN) {
            UserRole.ADMIN
        } else {
            UserRole.USER
        }
        viewModelScope.launch {
            _teamActionState.value = TeamActionState.Saving
            repository.addOrUpdateMember(
                rawEmail = email,
                rawDisplayName = displayName,
                assignedRole = effectiveRole,
                callerRole = userRole
            ).fold(
                onSuccess = { saved ->
                    _teamActionState.value = TeamActionState.Success(
                        "${saved.email} granted ${saved.resolvedRole.displayTitle} access."
                    )
                },
                onFailure = { err ->
                    _teamActionState.value = TeamActionState.Error(
                        err.localizedMessage ?: "Failed to add team member."
                    )
                }
            )
        }
    }

    fun setMemberRole(member: AuthorizedMember, newRole: UserRole) {
        viewModelScope.launch {
            _teamActionState.value = TeamActionState.Saving
            repository.updateMemberRole(member, newRole, userRole).fold(
                onSuccess = {
                    val label = if (newRole == UserRole.ADMIN) "promoted to Admin" else "changed to User"
                    _teamActionState.value = TeamActionState.Success("${member.email} $label.")
                },
                onFailure = { err ->
                    _teamActionState.value = TeamActionState.Error(
                        err.localizedMessage ?: "Failed to update role."
                    )
                }
            )
        }
    }

    fun removeTeamMember(member: AuthorizedMember) {
        viewModelScope.launch {
            _teamActionState.value = TeamActionState.Saving
            repository.deleteMember(member, userRole).fold(
                onSuccess = {
                    _teamActionState.value = TeamActionState.Success(
                        "Removed ${member.email} from TruckWeight Guard."
                    )
                },
                onFailure = { err ->
                    _teamActionState.value = TeamActionState.Error(
                        err.localizedMessage ?: "Failed to remove team member."
                    )
                }
            )
        }
    }

    fun clearTeamActionState() {
        _teamActionState.value = TeamActionState.Idle
    }

    fun stagePickedApk(uri: Uri) {
        if (!canManageUsers) {
            _stagedApkState.value = StagedApkState.Error(
                "Only the Owner or an Admin can upload new APK files."
            )
            return
        }
        viewModelScope.launch {
            _stagedApkState.value = StagedApkState.Inspecting
            _appUpdatePublishState.value = AppUpdatePublishState.Idle
            appUpdateUploadService.inspectPickedApk(uri).fold(
                onSuccess = { info ->
                    _stagedApkState.value = StagedApkState.Ready(info)
                },
                onFailure = { err ->
                    _stagedApkState.value = StagedApkState.Error(
                        err.localizedMessage ?: "Failed to inspect selected APK file."
                    )
                }
            )
        }
    }

    fun setDriveAccessToken(token: String?) {
        _driveAccessToken.value = token?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun reportDriveAuthError(message: String) {
        _appUpdatePublishState.value = AppUpdatePublishState.Error(message)
    }

    fun clearStagedApk() {
        _stagedApkState.value = StagedApkState.Idle
    }

    fun publishAppUpdate(
        versionCodeText: String,
        versionNameText: String,
        releaseNotesText: String,
        manualHttpsUrlText: String,
        isMandatory: Boolean,
        driveAccessTokenOverride: String? = null
    ) {
        if (!canManageUsers) {
            _appUpdatePublishState.value = AppUpdatePublishState.Error(
                "Only the Owner or an Admin can upload and publish app updates."
            )
            return
        }
        val staged = (_stagedApkState.value as? StagedApkState.Ready)?.apk
        if (staged == null) {
            _appUpdatePublishState.value = AppUpdatePublishState.Error(
                "Please select the new .apk file from your device first."
            )
            return
        }
        val parsedVersionCode = versionCodeText.trim().toLongOrNull()
        if (parsedVersionCode == null || parsedVersionCode < 1L) {
            _appUpdatePublishState.value = AppUpdatePublishState.Error(
                "Version Code must be a whole number (1 or higher)."
            )
            return
        }
        val cleanVersionName = versionNameText.trim()
        if (cleanVersionName.isEmpty() || cleanVersionName.length > 32) {
            _appUpdatePublishState.value = AppUpdatePublishState.Error(
                "Please provide a Version Name (1–32 characters)."
            )
            return
        }
        val cleanManualUrl = manualHttpsUrlText.trim()
        if (cleanManualUrl.isNotEmpty() && !AppUpdateUrlValidator.isSafeHttpsDownloadUrl(cleanManualUrl)) {
            _appUpdatePublishState.value = AppUpdatePublishState.Error(
                "Download link must start with https:// and point to a valid host."
            )
            return
        }

        val effectiveDriveToken = driveAccessTokenOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?.also { _driveAccessToken.value = it }
            ?: _driveAccessToken.value

        viewModelScope.launch {
            val resolvedUrl = if (cleanManualUrl.isNotEmpty()) {
                cleanManualUrl
            } else if (!effectiveDriveToken.isNullOrBlank()) {
                _appUpdatePublishState.value = AppUpdatePublishState.Uploading(
                    progressPercent = 1,
                    statusLabel = "Uploading ${staged.fileName} to Google Drive..."
                )
                val driveRes = appUpdateUploadService.uploadApkToGoogleDrive(
                    apk = staged,
                    versionCode = parsedVersionCode,
                    versionName = cleanVersionName,
                    accessToken = effectiveDriveToken,
                    onProgress = { pct ->
                        val status = if (pct >= 94) {
                            "Configuring Google Drive download link..."
                        } else {
                            "Uploading ${staged.fileName} to Google Drive ($pct%)..."
                        }
                        _appUpdatePublishState.value = AppUpdatePublishState.Uploading(
                            progressPercent = pct,
                            statusLabel = status
                        )
                    }
                )
                driveRes.getOrElse { err ->
                    if (err is DriveTokenExpiredException) {
                        _driveAccessToken.value = null
                    }
                    _appUpdatePublishState.value = AppUpdatePublishState.Error(
                        err.localizedMessage ?: "Google Drive APK upload failed."
                    )
                    return@launch
                }
            } else {
                _appUpdatePublishState.value = AppUpdatePublishState.Uploading(
                    progressPercent = 0,
                    statusLabel = "Uploading ${staged.fileName}..."
                )
                val uploadRes = appUpdateUploadService.uploadApkToBackendOrStorage(
                    apk = staged,
                    versionCode = parsedVersionCode,
                    savedOwnerConfig = savedOwnerFirebaseConfig.value,
                    telegramRelayUrl = runtimeSettings.value.telegramRelayUrl,
                    onProgress = { pct ->
                        _appUpdatePublishState.value = AppUpdatePublishState.Uploading(
                            progressPercent = pct,
                            statusLabel = "Uploading ${staged.fileName} ($pct%)..."
                        )
                    }
                )
                uploadRes.getOrElse { err ->
                    _appUpdatePublishState.value = AppUpdatePublishState.Error(
                        err.localizedMessage ?: "APK file upload failed."
                    )
                    return@launch
                }
            }

            _appUpdatePublishState.value = AppUpdatePublishState.Uploading(
                progressPercent = 100,
                statusLabel = "Notifying all team users..."
            )

            val release = AppUpdateRelease(
                versionCode = parsedVersionCode,
                versionName = cleanVersionName,
                fileName = staged.fileName,
                fileSizeBytes = staged.fileSizeBytes,
                sha256Checksum = staged.sha256Checksum,
                downloadUrl = resolvedUrl,
                releaseNotes = releaseNotesText.trim().ifEmpty {
                    "New TruckWeight Guard v$cleanVersionName is ready to download."
                },
                isMandatory = isMandatory
            )

            repository.publishAppUpdate(release, userRole).fold(
                onSuccess = { saved ->
                    _stagedApkState.value = StagedApkState.Idle
                    _appUpdatePublishState.value = AppUpdatePublishState.Success(
                        "Uploaded & published v${saved.versionName} (Build ${saved.versionCode}). All users will now receive a download pop-up."
                    )
                },
                onFailure = { err ->
                    _appUpdatePublishState.value = AppUpdatePublishState.Error(
                        err.localizedMessage ?: "Failed to publish update notification."
                    )
                }
            )
        }
    }

    fun removePublishedAppUpdate() {
        if (!canManageUsers) return
        viewModelScope.launch {
            _appUpdatePublishState.value = AppUpdatePublishState.Uploading(0, "Removing active update notice...")
            repository.clearAppUpdate(userRole).fold(
                onSuccess = {
                    _appUpdatePublishState.value = AppUpdatePublishState.Success("Active update notification cleared.")
                },
                onFailure = { err ->
                    _appUpdatePublishState.value = AppUpdatePublishState.Error(
                        err.localizedMessage ?: "Failed to clear update notification."
                    )
                }
            )
        }
    }

    fun dismissUpdatePopup(versionCode: Long) {
        _dismissedUpdateVersionCode.value = versionCode
    }

    fun reopenUpdatePopup() {
        _dismissedUpdateVersionCode.value = null
    }

    fun clearAppUpdatePublishState() {
        _appUpdatePublishState.value = AppUpdatePublishState.Idle
    }
}
