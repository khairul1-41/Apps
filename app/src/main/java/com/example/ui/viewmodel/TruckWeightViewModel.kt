package com.example.ui.viewmodel

import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AlertStatus
import com.example.data.model.OcrExtractionResult
import com.example.data.model.OwnerAdminConfig
import com.example.data.model.RuntimeSettings
import com.example.data.model.TruckWeightRecord
import com.example.data.model.UiState
import com.example.data.model.VerificationComparison
import com.example.data.ocr.WeighbridgeOcrService
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

class TruckWeightViewModel(
    private val repository: TruckWeightRepository,
    private val ocrService: WeighbridgeOcrService,
    private val telegramRelayService: TelegramRelayService,
    val currentUserId: String,
    val userEmail: String,
    val isOwner: Boolean
) : ViewModel() {

    val recordsState: StateFlow<UiState<List<TruckWeightRecord>>> =
        repository.observeRecords(currentUserId, isOwner)
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

    private val _retryingTelegramRecordId = MutableStateFlow<String?>(null)
    val retryingTelegramRecordId: StateFlow<String?> = _retryingTelegramRecordId.asStateFlow()

    private val _transientMessage = MutableStateFlow<String?>(null)
    val transientMessage: StateFlow<String?> = _transientMessage.asStateFlow()

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
                val extraction = ocrService.analyzeDocument(
                    imageUri = targetUri,
                    strictSecondWeightPriority = strictSecond,
                    minConfidenceThreshold = settings.minOcrConfidence
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

    fun openManualEntryFromError() {
        val existing = (recordsState.value as? UiState.Success)?.data.orEmpty()
        val fallbackDate = WeightVerificationEngine.currentFallbackDateString()
        val comparison = WeightVerificationEngine.buildComparison(
            vehicleNumberRaw = "",
            currentWeightKg = null,
            weightDate = fallbackDate,
            existingRecords = existing,
            settings = runtimeSettings.value
        )
        _analysisState.value = AnalysisUiState.Review(
            form = EditableAnalysisForm(
                vehicleNumberInput = "",
                weightInput = "",
                weightDateInput = fallbackDate,
                sourceLabelInput = "Manual Entry",
                notesInput = "Manual verification requested after OCR failure.",
                extraction = null,
                comparison = comparison
            )
        )
    }

    fun updateAnalysisForm(
        vehicleNumber: String? = null,
        weightText: String? = null,
        weightDate: String? = null,
        sourceLabel: String? = null,
        notes: String? = null,
        duplicateOverride: Boolean? = null
    ) {
        val currentReview = _analysisState.value as? AnalysisUiState.Review ?: return
        val currentForm = currentReview.form
        val nextVehicle = vehicleNumber ?: currentForm.vehicleNumberInput
        val nextWeightText = weightText ?: currentForm.weightInput
        val nextDate = weightDate ?: currentForm.weightDateInput
        val nextSource = sourceLabel ?: currentForm.sourceLabelInput
        val nextNotes = notes ?: currentForm.notesInput
        val nextOverride = duplicateOverride ?: if (vehicleNumber != null || weightText != null || weightDate != null) {
            false
        } else {
            currentForm.duplicateOverrideConfirmed
        }

        val parsedWeight = WeightVerificationEngine.parseWeightNumber(nextWeightText)
        val existing = (recordsState.value as? UiState.Success)?.data.orEmpty()
        val comparison = WeightVerificationEngine.buildComparison(
            vehicleNumberRaw = nextVehicle,
            currentWeightKg = parsedWeight,
            weightDate = nextDate,
            existingRecords = existing,
            settings = runtimeSettings.value
        )

        _analysisState.value = AnalysisUiState.Review(
            form = currentForm.copy(
                vehicleNumberInput = nextVehicle,
                weightInput = nextWeightText,
                weightDateInput = nextDate,
                sourceLabelInput = nextSource,
                notesInput = nextNotes,
                comparison = comparison,
                duplicateOverrideConfirmed = nextOverride
            ),
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
                "Please enter a valid Vehicle Number (at least 2 alphanumeric characters)."
            parsedWeight == null || parsedWeight <= 0.0 ->
                "Please enter a valid Truck Weight in KG greater than 0."
            cleanDate.isEmpty() ->
                "Please enter a valid Weight Date."
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
                telegramRelayUrl = telegramRelayUrl.trim()
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
}
