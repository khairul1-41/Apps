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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.core.OwnerFirebaseConfig
import com.example.core.OwnerPolicy
import com.example.data.model.AdsBannerConfig
import com.example.data.model.AdsDisplayMode
import com.example.data.model.AuthorizedMember
import com.example.data.model.OwnerAdminConfig
import com.example.data.model.RuntimeSettings
import com.example.data.model.SlipFormatTemplate
import com.example.data.model.UiState
import com.example.data.model.UserRole
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
import com.example.ui.viewmodel.AdsActionState
import com.example.ui.viewmodel.SettingsActionState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    userEmail: String,
    userRole: UserRole,
    runtimeSettings: RuntimeSettings,
    ownerAdminConfig: OwnerAdminConfig?,
    savedOwnerFirebaseConfig: OwnerFirebaseConfig?,
    authorizedMembersState: UiState<List<AuthorizedMember>>,
    actionState: SettingsActionState,
    adsConfig: AdsBannerConfig = AdsBannerConfig(),
    adsActionState: AdsActionState = AdsActionState.Idle,
    slipTemplates: List<SlipFormatTemplate> = emptyList(),
    onOpenTeamManagement: () -> Unit,
    onOpenOwnerSettings: () -> Unit,
    onOpenSlipTemplates: () -> Unit = {},
    onSaveAdsConfig: (AdsBannerConfig) -> Unit = {},
    onDismissAdsActionState: () -> Unit = {},
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
    val isOwner = userRole == UserRole.OWNER
    val canManageUsers = userRole.canManageUsers
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

    // Owner-only Ads Control state (strictly invisible to Admins & Users)
    var adsEnabled by remember(adsConfig) { mutableStateOf(adsConfig.adsEnabled) }
    var adsMode by remember(adsConfig) { mutableStateOf(adsConfig.resolvedMode) }
    var adCodeSnippet by remember(adsConfig) { mutableStateOf(adsConfig.adCodeSnippet) }
    var sponsorTitle by remember(adsConfig) { mutableStateOf(adsConfig.sponsorTitle) }
    var sponsorSubtitle by remember(adsConfig) { mutableStateOf(adsConfig.sponsorSubtitle) }
    var sponsorCtaLabel by remember(adsConfig) { mutableStateOf(adsConfig.sponsorCtaLabel.ifBlank { "Open" }) }
    var sponsorTargetUrl by remember(adsConfig) { mutableStateOf(adsConfig.sponsorTargetUrl) }

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

            // Role Access Banner
            item {
                val bannerColor = when (userRole) {
                    UserRole.OWNER -> SignalGreenContainer
                    UserRole.ADMIN -> NavyContainer
                    UserRole.USER -> WarningOrangeContainer
                }
                val borderColor = when (userRole) {
                    UserRole.OWNER -> SignalGreen
                    UserRole.ADMIN -> NavyPrimary
                    UserRole.USER -> WarningOrange
                }
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = bannerColor),
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (canManageUsers) Icons.Filled.AdminPanelSettings else Icons.Filled.Lock,
                            contentDescription = null,
                            tint = borderColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = when (userRole) {
                                    UserRole.OWNER -> "Owner Admin Unlocked"
                                    UserRole.ADMIN -> "Team Admin Unlocked"
                                    UserRole.USER -> "Shared Team User Mode"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = NavyPrimary
                            )
                            Text(
                                text = when (userRole) {
                                    UserRole.OWNER -> "Signed in as verified owner ($userEmail). You can manage Admins, Users, thresholds, and Firebase config."
                                    UserRole.ADMIN -> "Signed in as Admin ($userEmail). You can add or delete team Users and manage shared truck records."
                                    UserRole.USER -> "Signed in as $userEmail. You can scan slips and view all shared truck records. Settings are managed by Owner (${OwnerPolicy.OWNER_EMAIL})."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateTextSecondary
                            )
                        }
                    }
                }
            }

            // Team & User Management Card (Visible to Owner and Admins)
            if (canManageUsers) {
                item {
                    val members = (authorizedMembersState as? UiState.Success)?.data.orEmpty()
                    val adminCount = members.count { it.resolvedRole == UserRole.ADMIN }
                    val userCount = members.count { it.resolvedRole == UserRole.USER }

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                        border = BorderStroke(1.5.dp, NavyPrimary.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Group,
                                    contentDescription = null,
                                    tint = NavyPrimary
                                )
                                Column {
                                    Text(
                                        text = if (isOwner) "Admin Panel, Team & App Updates" else "Admin Panel: Users & App Updates",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = NavyPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Active team: $adminCount Admins • $userCount Users",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateTextSecondary
                                    )
                                }
                            }

                            Text(
                                text = if (isOwner) {
                                    "Promote Admins, add or delete Users, and upload new .apk app updates with pop-up notifications for all users."
                                } else {
                                    "Add or delete team Users and upload new .apk app updates so all users receive a download pop-up."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateTextSecondary
                            )

                            Button(
                                onClick = onOpenTeamManagement,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("open_team_management_button")
                            ) {
                                Icon(imageVector = Icons.Filled.Group, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isOwner) "Open Admin Panel (Team & APK Updates)" else "Open Admin Panel (Users & APK Updates)",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Owner-Only Custom Scale Slip Format Marking Card (Hidden from Admins & Users)
            if (isOwner) {
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                        border = BorderStroke(1.5.dp, SignalGreen.copy(alpha = 0.6f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CropFree,
                                    contentDescription = null,
                                    tint = SignalGreen
                                )
                                Column {
                                    Text(
                                        text = "Custom Scale Slip Formats (OCR Marker)",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = NavyPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Active custom formats: ${slipTemplates.size} • Owner-Only",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateTextSecondary
                                    )
                                }
                            }
                            Text(
                                text = "Have a new scale slip where Truck No or Truck Weight is printed differently? Mark the exact field boxes on a sample photo and upload so every user's scan collects data accurately.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateTextSecondary
                            )
                            Button(
                                onClick = onOpenSlipTemplates,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SignalGreen,
                                    contentColor = NavyPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("open_slip_templates_button")
                            ) {
                                Icon(imageVector = Icons.Filled.CropFree, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Mark & Upload Slip Format",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Owner-Only Ads Section (Strictly Hidden from Admins & Users)
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                        border = BorderStroke(1.dp, NavyPrimary.copy(alpha = 0.35f)),
                        modifier = Modifier.testTag("owner_only_ads_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Campaign,
                                        contentDescription = null,
                                        tint = NavyPrimary
                                    )
                                    Column {
                                        Text(
                                            text = "Ads Control (Owner Only)",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = NavyPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Hidden from Admins & Users • Manage app ads code",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SlateTextSecondary
                                        )
                                    }
                                }
                                Switch(
                                    checked = adsEnabled,
                                    onCheckedChange = {
                                        adsEnabled = it
                                        onDismissAdsActionState()
                                    },
                                    modifier = Modifier.testTag("owner_ads_enabled_switch")
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = adsMode == AdsDisplayMode.AD_CODE,
                                    onClick = {
                                        adsMode = AdsDisplayMode.AD_CODE
                                        onDismissAdsActionState()
                                    },
                                    label = { Text("Ads Code / HTTPS Tag") }
                                )
                                FilterChip(
                                    selected = adsMode == AdsDisplayMode.SPONSORED_CARD,
                                    onClick = {
                                        adsMode = AdsDisplayMode.SPONSORED_CARD
                                        onDismissAdsActionState()
                                    },
                                    label = { Text("Sponsored Banner") }
                                )
                            }

                            if (adsMode == AdsDisplayMode.AD_CODE) {
                                OutlinedTextField(
                                    value = adCodeSnippet,
                                    onValueChange = {
                                        adCodeSnippet = it
                                        onDismissAdsActionState()
                                    },
                                    label = { Text("Ads Code / AdMob Unit ID / HTTPS Script") },
                                    placeholder = { Text("ca-app-pub-xxxxxxxxxxxxxxxx/xxxxxxxxxx or <script src=\"https://...\"></script>") },
                                    minLines = 3,
                                    maxLines = 6,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("owner_ads_code_input")
                                )
                            } else {
                                OutlinedTextField(
                                    value = sponsorTitle,
                                    onValueChange = {
                                        sponsorTitle = it
                                        onDismissAdsActionState()
                                    },
                                    label = { Text("Ad / Banner Title *") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = sponsorSubtitle,
                                    onValueChange = {
                                        sponsorSubtitle = it
                                        onDismissAdsActionState()
                                    },
                                    label = { Text("Description / Message") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = sponsorCtaLabel,
                                        onValueChange = {
                                            sponsorCtaLabel = it
                                            onDismissAdsActionState()
                                        },
                                        label = { Text("Button Label") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.4f)
                                    )
                                    OutlinedTextField(
                                        value = sponsorTargetUrl,
                                        onValueChange = {
                                            sponsorTargetUrl = it
                                            onDismissAdsActionState()
                                        },
                                        label = { Text("HTTPS Link") },
                                        placeholder = { Text("https://...") },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.6f)
                                    )
                                }
                            }

                            when (adsActionState) {
                                is AdsActionState.Success -> {
                                    Surface(
                                        color = SignalGreenContainer,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, SignalGreen)
                                    ) {
                                        Text(
                                            text = adsActionState.message,
                                            color = NavyPrimary,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                                is AdsActionState.Error -> {
                                    Surface(
                                        color = AlertRedContainer,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, AlertRed)
                                    ) {
                                        Text(
                                            text = adsActionState.message,
                                            color = AlertRed,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                                else -> Unit
                            }

                            Button(
                                onClick = {
                                    onSaveAdsConfig(
                                        AdsBannerConfig(
                                            adsEnabled = adsEnabled,
                                            adsMode = adsMode.name,
                                            adCodeSnippet = adCodeSnippet,
                                            sponsorTitle = sponsorTitle,
                                            sponsorSubtitle = sponsorSubtitle,
                                            sponsorCtaLabel = sponsorCtaLabel,
                                            sponsorTargetUrl = sponsorTargetUrl
                                        )
                                    )
                                },
                                enabled = adsActionState !is AdsActionState.Saving,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("save_owner_ads_button")
                            ) {
                                if (adsActionState is AdsActionState.Saving) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = PureWhite)
                                } else {
                                    Text("Save Ads Settings", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Owner Firebase Project Preferences Card
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
                                imageVector = Icons.Filled.EnhancedEncryption,
                                contentDescription = null,
                                tint = SignalGreen
                            )
                            Text(
                                text = "Owner Settings — Firebase Project",
                                style = MaterialTheme.typography.titleMedium,
                                color = PureWhite
                            )
                        }

                        ConfigPlaceholderBox(
                            title = "ENCRYPTED LOCAL FIREBASE CONFIG:",
                            placeholder = if (savedOwnerFirebaseConfig != null) {
                                "Project: ${savedOwnerFirebaseConfig.projectId} (${savedOwnerFirebaseConfig.maskedApiKey()})"
                            } else {
                                "Not configured in local encrypted preferences"
                            },
                            detail = "Active Provisioned DB: $firestoreDbId • Stored using Android Keystore AES-256-GCM"
                        )

                        if (isOwner) {
                            Button(
                                onClick = onOpenOwnerSettings,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SignalGreen,
                                    contentColor = NavyPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("open_owner_settings_button")
                            ) {
                                Icon(imageVector = Icons.Filled.Security, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Configure Firebase Project Settings",
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null
                                )
                            }
                        }

                        ConfigPlaceholderBox(
                            title = "TELEGRAM BOT RELAY SECRETS:",
                            placeholder = "Server-Side Secret Manager (TELEGRAM_BOT_TOKEN & TELEGRAM_CHAT_ID)",
                            detail = "Set via: firebase functions:secrets:set TELEGRAM_BOT_TOKEN && firebase functions:secrets:set TELEGRAM_CHAT_ID"
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
                                imageVector = Icons.AutoMirrored.Filled.Send,
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
                                        text = "Prefer second weighing when two empty check weighings appear (lighter empty/tare weight is always kept when Gross/Net differs)",
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
