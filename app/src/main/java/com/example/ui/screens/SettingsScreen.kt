package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.OwnerPolicy
import com.example.data.model.OwnerAdminConfig
import com.example.data.model.RuntimeSettings
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertRedContainer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NavyContainer
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
import com.example.ui.viewmodel.SettingsActionState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    userEmail: String,
    isOwner: Boolean,
    runtimeSettings: RuntimeSettings,
    ownerAdminConfig: OwnerAdminConfig?,
    actionState: SettingsActionState,
    onSaveOwnerSettings: (
        neutralMaxKg: Double,
        greenMaxKg: Double,
        orangeMaxKg: Double,
        minOcrConfidence: Double,
        autoSendTelegram: Boolean,
        telegramRelayUrl: String,
        telegramChatIdLabel: String,
        firebaseConfigNote: String,
        ocrStrictFinalWeight: Boolean
    ) -> Unit,
    onDismissActionState: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val firestoreDbId = remember {
        runCatching { context.getString(R.string.firestore_database_id) }.getOrDefault("configured")
    }

    var neutralMaxText by remember(runtimeSettings) {
        mutableStateOf(runtimeSettings.neutralMaxKg.toInt().toString())
    }
    var greenMaxText by remember(runtimeSettings) {
        mutableStateOf(runtimeSettings.greenMaxKg.toInt().toString())
    }
    var orangeMaxText by remember(runtimeSettings) {
        mutableStateOf(runtimeSettings.orangeMaxKg.toInt().toString())
    }
    var minConfidence by remember(runtimeSettings) {
        mutableFloatStateOf(runtimeSettings.minOcrConfidence.toFloat())
    }
    var autoSendTelegram by remember(runtimeSettings) {
        mutableStateOf(runtimeSettings.autoSendTelegram)
    }
    var relayUrlText by remember(runtimeSettings) {
        mutableStateOf(runtimeSettings.telegramRelayUrl.orEmpty())
    }
    var chatIdLabelText by remember(ownerAdminConfig) {
        mutableStateOf(ownerAdminConfig?.telegramChatIdLabel.orEmpty())
    }
    var firebaseConfigNoteText by remember(ownerAdminConfig) {
        mutableStateOf(ownerAdminConfig?.firebaseConfigNote.orEmpty())
    }
    var strictFinalWeight by remember(ownerAdminConfig) {
        mutableStateOf(ownerAdminConfig?.ocrStrictFinalWeight ?: true)
    }

    LaunchedEffect(actionState) {
        if (actionState is SettingsActionState.Success) {
            // Keep visible briefly until user edits again
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SlateBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Settings & Owner Controls",
                    style = MaterialTheme.typography.headlineMedium,
                    color = NavyPrimary
                )
            }

            // Owner Access Banner
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isOwner) SignalGreenContainer else WarningOrangeContainer
                    ),
                    border = BorderStroke(1.dp, if (isOwner) SignalGreen else WarningOrange)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (isOwner) Icons.Filled.AdminPanelSettings else Icons.Filled.Lock,
                            contentDescription = null,
                            tint = if (isOwner) SignalGreen else WarningOrange,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = if (isOwner) {
                                    "Owner-Only Admin Unlocked"
                                } else {
                                    "Operator Mode (Settings Locked)"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = NavyPrimary
                            )
                            Text(
                                text = if (isOwner) {
                                    "Signed in as verified owner ($userEmail). All thresholds, OCR rules, and relay endpoints can be modified."
                                } else {
                                    "Signed in as $userEmail. Only the application owner (${OwnerPolicy.OWNER_EMAIL}) can modify configuration."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateTextSecondary
                            )
                        }
                    }
                }
            }

            // Highlighted Configuration Placeholders (Requirement 12)
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = NavyPrimary),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = null,
                                tint = SignalGreen
                            )
                            Text(
                                text = "Security & Integration Configuration",
                                style = MaterialTheme.typography.titleMedium,
                                color = PureWhite
                            )
                        }

                        ConfigPlaceholderBox(
                            title = "FIREBASE CONFIGURATION:",
                            placeholder = "[PASTE FIREBASE CONFIGURATION HERE]",
                            detail = "Active Database: $firestoreDbId"
                        )
                        ConfigPlaceholderBox(
                            title = "TELEGRAM BOT API TOKEN:",
                            placeholder = "[PASTE TELEGRAM BOT TOKEN HERE]",
                            detail = "Backend-only secret. Never store in frontend APK. Set via: firebase functions:secrets:set TELEGRAM_BOT_TOKEN"
                        )
                        ConfigPlaceholderBox(
                            title = "TELEGRAM CHAT ID:",
                            placeholder = "[PASTE TELEGRAM CHAT ID HERE]",
                            detail = "Backend secret. Set via: firebase functions:secrets:set TELEGRAM_CHAT_ID"
                        )
                    }
                }
            }

            // Telegram Relay Settings
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = PureWhite),
                    border = BorderStroke(1.dp, SlateBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = null,
                                tint = NavyPrimary
                            )
                            Text(
                                text = "Secure Backend Telegram Relay",
                                style = MaterialTheme.typography.titleMedium,
                                color = NavyPrimary
                            )
                        }

                        OutlinedTextField(
                            value = relayUrlText,
                            onValueChange = {
                                relayUrlText = it
                                onDismissActionState()
                            },
                            label = { Text("Cloud Function HTTPS Endpoint URL") },
                            placeholder = { Text("https://asia-southeast1-<project>.cloudfunctions.net/sendWeighbridgeToTelegram") },
                            enabled = isOwner,
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_relay_url_input")
                        )

                        if (isOwner) {
                            OutlinedTextField(
                                value = chatIdLabelText,
                                onValueChange = {
                                    chatIdLabelText = it
                                    onDismissActionState()
                                },
                                label = { Text("Telegram Chat ID Reference Label (Optional)") },
                                placeholder = { Text("e.g. Weighbridge Ops Group (-100...)") },
                                enabled = isOwner,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Send Original Slip to Telegram",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NavyPrimary
                                )
                                Text(
                                    text = "Dispatches original image + alert after Firestore save",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateTextSecondary
                                )
                            }
                            Switch(
                                checked = autoSendTelegram,
                                onCheckedChange = {
                                    autoSendTelegram = it
                                    onDismissActionState()
                                },
                                enabled = isOwner
                            )
                        }
                    }
                }
            }

            // Verification Thresholds & OCR Controls
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = PureWhite),
                    border = BorderStroke(1.dp, SlateBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = null,
                                tint = NavyPrimary
                            )
                            Text(
                                text = "Alert Thresholds & OCR Settings",
                                style = MaterialTheme.typography.titleMedium,
                                color = NavyPrimary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = neutralMaxText,
                                onValueChange = {
                                    neutralMaxText = it
                                    onDismissActionState()
                                },
                                label = { Text("Neutral Max (KG)") },
                                enabled = isOwner,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = greenMaxText,
                                onValueChange = {
                                    greenMaxText = it
                                    onDismissActionState()
                                },
                                label = { Text("Green Max (KG)") },
                                enabled = isOwner,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = orangeMaxText,
                                onValueChange = {
                                    orangeMaxText = it
                                    onDismissActionState()
                                },
                                label = { Text("Orange Max (KG)") },
                                enabled = isOwner,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text(
                            text = "Red Alert triggers automatically for differences strictly above Orange Max (${orangeMaxText.ifBlank { "19" }} KG).",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateTextSecondary
                        )

                        Column {
                            Text(
                                text = "Minimum OCR Auto-Accept Confidence: ${(minConfidence * 100).roundToInt()}%",
                                style = MaterialTheme.typography.titleSmall,
                                color = NavyPrimary
                            )
                            Slider(
                                value = minConfidence,
                                onValueChange = {
                                    minConfidence = it
                                    onDismissActionState()
                                },
                                valueRange = 0.50f..0.95f,
                                enabled = isOwner
                            )
                        }

                        if (isOwner) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Strict Second/Final Weighing Priority",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = NavyPrimary
                                    )
                                    Text(
                                        text = "Always select second/final weighing when multiple weighings exist",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateTextSecondary
                                    )
                                }
                                Switch(
                                    checked = strictFinalWeight,
                                    onCheckedChange = {
                                        strictFinalWeight = it
                                        onDismissActionState()
                                    },
                                    enabled = isOwner
                                )
                            }

                            OutlinedTextField(
                                value = firebaseConfigNoteText,
                                onValueChange = {
                                    firebaseConfigNoteText = it
                                    onDismissActionState()
                                },
                                label = { Text("Firebase Integration Notes (Owner-Only)") },
                                enabled = isOwner,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            item {
                when (actionState) {
                    is SettingsActionState.Success -> {
                        Surface(
                            color = SignalGreenContainer,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SignalGreen)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SignalGreen)
                                Text(actionState.message, color = NavyPrimary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    is SettingsActionState.Error -> {
                        Surface(
                            color = AlertRedContainer,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AlertRed)
                        ) {
                            Text(
                                text = actionState.message,
                                color = AlertRed,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    else -> Unit
                }
            }

            if (isOwner) {
                item {
                    val isSaving = actionState is SettingsActionState.Saving
                    Button(
                        onClick = {
                            val nMax = neutralMaxText.toDoubleOrNull() ?: 4.0
                            val gMax = greenMaxText.toDoubleOrNull() ?: 15.0
                            val oMax = orangeMaxText.toDoubleOrNull() ?: 19.0
                            onSaveOwnerSettings(
                                nMax,
                                gMax,
                                oMax,
                                minConfidence.toDouble(),
                                autoSendTelegram,
                                relayUrlText,
                                chatIdLabelText,
                                firebaseConfigNoteText,
                                strictFinalWeight
                            )
                        },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("save_owner_settings_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = PureWhite)
                        } else {
                            Icon(imageVector = Icons.Filled.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Save Owner Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ConfigPlaceholderBox(
    title: String,
    placeholder: String,
    detail: String
) {
    Surface(
        color = PureWhite.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = SignalGreen,
                fontWeight = FontWeight.Bold
            )
            Surface(
                color = NavyContainer.copy(alpha = 0.18f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = PureWhite,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = SlateSurfaceVariant.copy(alpha = 0.85f)
            )
        }
    }
}
