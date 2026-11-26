package com.example.ui.screens

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.remote.TelegramDispatchResult
import com.example.domain.WeightVerificationEngine
import com.example.ui.components.AlertStatusPill
import com.example.ui.components.alertVisualStyle
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertRedContainer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.SignalGreenContainer
import com.example.ui.theme.SlateBackground
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurfaceVariant
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.WarningOrange
import com.example.ui.theme.WarningOrangeContainer
import com.example.ui.viewmodel.AnalysisUiState
import com.example.ui.viewmodel.EditableAnalysisForm
import kotlin.math.roundToInt

@Composable
fun AnalysisScreen(
    analysisState: AnalysisUiState,
    previewImageUri: Uri?,
    isRetryingTelegram: Boolean,
    onUpdateForm: (
        vehicleNumber: String?,
        weightText: String?,
        weightDate: String?,
        sourceLabel: String?,
        notes: String?,
        duplicateOverride: Boolean?
    ) -> Unit,
    onConfirmAndSave: () -> Unit,
    onRetryOcr: () -> Unit,
    onEnterManually: () -> Unit,
    onRetryTelegram: () -> Unit,
    onGoToSettings: () -> Unit,
    onDoneHome: () -> Unit,
    onScanNext: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlateBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = NavyPrimary,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = PureWhite
                    )
                }
                Text(
                    text = "OCR Verification & Alert Engine",
                    style = MaterialTheme.typography.titleLarge,
                    color = PureWhite
                )
            }
        }

        Box(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (analysisState) {
                is AnalysisUiState.Idle,
                is AnalysisUiState.Analyzing -> {
                    AnalyzingAnimationView(imageUri = previewImageUri)
                }

                is AnalysisUiState.Review -> {
                    AnalysisReviewView(
                        form = analysisState.form,
                        validationError = analysisState.validationError,
                        previewImageUri = previewImageUri,
                        isSaving = false,
                        onUpdateForm = onUpdateForm,
                        onConfirmAndSave = onConfirmAndSave
                    )
                }

                is AnalysisUiState.Saving -> {
                    AnalysisReviewView(
                        form = analysisState.form,
                        validationError = null,
                        previewImageUri = previewImageUri,
                        isSaving = true,
                        onUpdateForm = onUpdateForm,
                        onConfirmAndSave = {}
                    )
                }

                is AnalysisUiState.Completed -> {
                    AnalysisCompletedView(
                        state = analysisState,
                        isRetryingTelegram = isRetryingTelegram,
                        onRetryTelegram = onRetryTelegram,
                        onGoToSettings = onGoToSettings,
                        onDoneHome = onDoneHome,
                        onScanNext = onScanNext
                    )
                }

                is AnalysisUiState.Error -> {
                    AnalysisErrorView(
                        message = analysisState.message,
                        onRetryOcr = onRetryOcr,
                        onEnterManually = onEnterManually,
                        onBack = onBackClick
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalyzingAnimationView(imageUri: Uri?) {
    val transition = rememberInfiniteTransition(label = "ocr_scan")
    val scanProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_line"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(290.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NavyPrimary),
            contentAlignment = Alignment.Center
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = "Analyzing weighbridge slip",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    alpha = 0.7f
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val y = size.height * scanProgress
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            SignalGreen.copy(alpha = 0.35f),
                            SignalGreen.copy(alpha = 0.85f),
                            Color.Transparent
                        ),
                        startY = (y - 36f).coerceAtLeast(0f),
                        endY = (y + 36f).coerceAtMost(size.height)
                    ),
                    topLeft = Offset(0f, (y - 36f).coerceAtLeast(0f)),
                    size = androidx.compose.ui.geometry.Size(size.width, 72f)
                )
                drawLine(
                    color = SignalGreen,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 5f
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(
                    color = NavyPrimary,
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp
                )
                Column {
                    Text(
                        text = "Analyzing Weighbridge Document...",
                        style = MaterialTheme.typography.titleMedium,
                        color = NavyPrimary
                    )
                    Text(
                        text = "Extracting vehicle number, final truck/tare weight, and date using layout-aware OCR.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateTextSecondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisReviewView(
    form: EditableAnalysisForm,
    validationError: String?,
    previewImageUri: Uri?,
    isSaving: Boolean,
    onUpdateForm: (String?, String?, String?, String?, String?, Boolean?) -> Unit,
    onConfirmAndSave: () -> Unit
) {
    val extraction = form.extraction
    val comparison = form.comparison
    val alertStyle = alertVisualStyle(comparison.alertStatus)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (previewImageUri != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = previewImageUri,
                    contentDescription = "Slip thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Column(modifier = Modifier.weight(1f)) {
                    val confPct = ((extraction?.confidence ?: 1.0) * 100).roundToInt()
                    Text(
                        text = if (extraction != null) "OCR Confidence: $confPct%" else "Manual Verification Entry",
                        style = MaterialTheme.typography.titleMedium,
                        color = NavyPrimary
                    )
                    if (!extraction?.reasoning.isNullOrBlank()) {
                        Text(
                            text = extraction!!.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateTextSecondary,
                            maxLines = 3
                        )
                    }
                }
            }
        }

        if (extraction?.requiresManualConfirmation == true) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = WarningOrangeContainer),
                border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Manual confirmation required",
                        tint = WarningOrange
                    )
                    Text(
                        text = "Manual confirmation recommended: Please verify vehicle number, final truck/tare weight, and date against the slip before saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NavyPrimary
                    )
                }
            }
        }

        if (extraction != null && (extraction.grossWeightKg != null || extraction.netWeightKg != null || extraction.firstWeightKg != null || extraction.secondWeightKg != null)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                extraction.firstWeightKg?.let {
                    AssistChip(onClick = {}, label = { Text("1st Wt: ${WeightVerificationEngine.formatWeightKg(it)}") })
                }
                extraction.secondWeightKg?.let {
                    AssistChip(onClick = {}, label = { Text("2nd Wt: ${WeightVerificationEngine.formatWeightKg(it)}") })
                }
                extraction.grossWeightKg?.let {
                    AssistChip(onClick = {}, label = { Text("Gross: ${WeightVerificationEngine.formatWeightKg(it)}") })
                }
                extraction.netWeightKg?.let {
                    AssistChip(onClick = {}, label = { Text("Net: ${WeightVerificationEngine.formatWeightKg(it)}") })
                }
            }
        }

        // Editable Fields Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Extracted Weighbridge Data (Editable)",
                    style = MaterialTheme.typography.titleMedium,
                    color = NavyPrimary
                )

                OutlinedTextField(
                    value = form.vehicleNumberInput,
                    onValueChange = { onUpdateForm(it, null, null, null, null, null) },
                    label = { Text("Vehicle Number (e.g. BUG T-11-1633)") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("analysis_vehicle_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = form.weightInput,
                        onValueChange = { onUpdateForm(null, it, null, null, null, null) },
                        label = { Text("Truck Weight (KG)") },
                        singleLine = true,
                        enabled = !isSaving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("analysis_weight_input")
                    )
                    OutlinedTextField(
                        value = form.weightDateInput,
                        onValueChange = { onUpdateForm(null, null, it, null, null, null) },
                        label = { Text("Weight Date") },
                        singleLine = true,
                        enabled = !isSaving,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("analysis_date_input")
                    )
                }

                OutlinedTextField(
                    value = form.sourceLabelInput,
                    onValueChange = { onUpdateForm(null, null, null, it, null, null) },
                    label = { Text("Detected Weight Label") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Live Weight Verification Comparison Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = alertStyle.container),
            border = BorderStroke(1.5.dp, alertStyle.accent)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Weight Verification Result",
                        style = MaterialTheme.typography.titleMedium,
                        color = NavyPrimary
                    )
                    AlertStatusPill(status = comparison.alertStatus)
                }

                HorizontalDivider(color = alertStyle.accent.copy(alpha = 0.25f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Previous Weight", style = MaterialTheme.typography.bodySmall, color = SlateTextSecondary)
                        Text(
                            text = WeightVerificationEngine.formatWeightKg(comparison.previousWeightKg),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = NavyPrimary
                        )
                        if (!comparison.previousRecordDate.isNullOrBlank()) {
                            Text(
                                text = "Date: ${comparison.previousRecordDate}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SlateTextSecondary
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Difference", style = MaterialTheme.typography.bodySmall, color = SlateTextSecondary)
                        Text(
                            text = WeightVerificationEngine.formatDifferenceKg(comparison.weightDifferenceKg),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = alertStyle.accent,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("Current Weight", style = MaterialTheme.typography.bodySmall, color = SlateTextSecondary)
                        Text(
                            text = WeightVerificationEngine.formatWeightKg(comparison.currentWeightKg),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = NavyPrimary
                        )
                    }
                }

                Text(
                    text = if (comparison.previousWeightKg == null) {
                        "No prior saved weighing found for this vehicle. Saving establishes the baseline record."
                    } else {
                        comparison.alertStatus.summary
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = alertStyle.onContainer
                )
            }
        }

        if (comparison.isDuplicateRisk) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = AlertRedContainer),
                border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Duplicate Record Guard",
                            style = MaterialTheme.typography.titleSmall,
                            color = AlertRed
                        )
                        Text(
                            text = "Matching vehicle, weight, and date already saved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NavyPrimary
                        )
                    }
                    Switch(
                        checked = form.duplicateOverrideConfirmed,
                        onCheckedChange = { onUpdateForm(null, null, null, null, null, it) },
                        enabled = !isSaving
                    )
                }
            }
        }

        if (!validationError.isNullOrBlank()) {
            Surface(
                color = AlertRedContainer,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AlertRed)
            ) {
                Text(
                    text = validationError,
                    style = MaterialTheme.typography.bodySmall,
                    color = AlertRed,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Button(
            onClick = onConfirmAndSave,
            enabled = !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("confirm_save_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NavyPrimary,
                contentColor = PureWhite
            )
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = PureWhite,
                    strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Saving & Dispatching...")
            } else {
                Icon(imageVector = Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Confirm, Save & Send to Telegram",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun AnalysisCompletedView(
    state: AnalysisUiState.Completed,
    isRetryingTelegram: Boolean,
    onRetryTelegram: () -> Unit,
    onGoToSettings: () -> Unit,
    onDoneHome: () -> Unit,
    onScanNext: () -> Unit
) {
    val record = state.savedRecord
    val tg = state.telegramResult

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SignalGreenContainer),
            border = BorderStroke(1.dp, SignalGreen)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Saved",
                        tint = SignalGreen,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Record Saved to Firestore",
                        style = MaterialTheme.typography.titleLarge,
                        color = NavyPrimary
                    )
                }
                Text(
                    text = "Vehicle: ${record.vehicleNumber}  •  Weight: ${WeightVerificationEngine.formatWeightKg(record.truckWeight)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = NavyPrimary
                )
                AlertStatusPill(status = record.resolvedAlertStatus)
                Text(
                    text = "Record ID: ${record.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateTextSecondary
                )
            }
        }

        // Telegram Status Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (tg) {
                    is TelegramDispatchResult.Sent -> SignalGreenContainer
                    is TelegramDispatchResult.NotConfigured -> WarningOrangeContainer
                    is TelegramDispatchResult.Failed -> AlertRedContainer
                }
            ),
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Telegram Document Relay",
                    style = MaterialTheme.typography.titleMedium,
                    color = NavyPrimary
                )
                when (tg) {
                    is TelegramDispatchResult.Sent -> {
                        Text(
                            text = "Original slip photo and alert payload delivered to Telegram.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NavyPrimary
                        )
                        Text(
                            text = "Message ID: ${tg.messageId}\nFile ID: ${tg.fileId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateTextSecondary
                        )
                    }
                    is TelegramDispatchResult.NotConfigured -> {
                        Text(
                            text = tg.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = NavyPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onGoToSettings) {
                                Text("Configure in Settings")
                            }
                            Button(
                                onClick = onRetryTelegram,
                                enabled = !isRetryingTelegram,
                                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary)
                            ) {
                                Text("Retry Send")
                            }
                        }
                    }
                    is TelegramDispatchResult.Failed -> {
                        Text(
                            text = "Dispatch failed: ${tg.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AlertRed
                        )
                        Button(
                            onClick = onRetryTelegram,
                            enabled = !isRetryingTelegram,
                            colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary)
                        ) {
                            if (isRetryingTelegram) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = PureWhite)
                            } else {
                                Text("Retry Telegram Dispatch")
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDoneHome,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("done_home_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Dashboard")
            }
            Button(
                onClick = onScanNext,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("scan_next_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary)
            ) {
                Text("Scan Next Slip")
            }
        }
    }
}

@Composable
private fun AnalysisErrorView(
    message: String,
    onRetryOcr: () -> Unit,
    onEnterManually: () -> Unit,
    onBack: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "OCR Error",
                tint = AlertRed,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = "OCR Recognition Interrupted",
                style = MaterialTheme.typography.titleLarge,
                color = NavyPrimary
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = SlateTextSecondary
            )
            Button(
                onClick = onRetryOcr,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary)
            ) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry OCR Analysis")
            }
            OutlinedButton(
                onClick = onEnterManually,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Enter Weight Manually")
            }
            OutlinedButton(onClick = onBack) {
                Text("Back to Preview")
            }
        }
    }
}
