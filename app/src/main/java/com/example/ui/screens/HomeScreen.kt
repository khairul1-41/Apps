package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.model.AdsBannerConfig
import com.example.data.model.AlertStatus
import com.example.data.model.AppUpdateRelease
import com.example.data.model.TruckWeightRecord
import com.example.data.model.UiState
import com.example.data.model.UserRole
import com.example.data.remote.AppUpdateUrlValidator
import com.example.ui.components.KpiMetricCard
import com.example.ui.components.OwnerAdBannerView
import com.example.ui.components.WeightRecordCard
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertRedContainer
import com.example.ui.theme.NavyContainer
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.NavySecondary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.SignalGreenContainer
import com.example.ui.theme.SlateBackground
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.SteelAccent
import com.example.ui.theme.SteelContainer

@Composable
fun HomeScreen(
    recordsState: UiState<List<TruckWeightRecord>>,
    userEmail: String,
    userRole: UserRole,
    availableUpdate: AppUpdateRelease? = null,
    adsConfig: AdsBannerConfig = AdsBannerConfig(),
    onOpenUpdatePopupClick: () -> Unit = {},
    onScanDocumentClick: () -> Unit,
    onUploadGalleryClick: () -> Unit,
    onManageTeamClick: () -> Unit,
    onRecordClick: (TruckWeightRecord) -> Unit,
    onViewAllHistoryClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val records = (recordsState as? UiState.Success)?.data.orEmpty()
    val totalRecords = records.size
    val verifiedVehicles = records.map { it.vehicleKey }.filter { it.isNotBlank() }.distinct().size
    val greenSignals = records.count { it.resolvedAlertStatus == AlertStatus.GREEN_SIGNAL }
    val redAlerts = records.count { it.resolvedAlertStatus == AlertStatus.RED_ALERT }

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
                HomeHeaderCard(
                    userEmail = userEmail,
                    userRole = userRole,
                    onManageTeamClick = onManageTeamClick,
                    onSignOutClick = onSignOutClick
                )
            }

            if (availableUpdate != null &&
                availableUpdate.versionCode > BuildConfig.VERSION_CODE.toLong() &&
                AppUpdateUrlValidator.isSafeHttpsDownloadUrl(availableUpdate.downloadUrl)
            ) {
                item {
                    Card(
                        onClick = onOpenUpdatePopupClick,
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SignalGreenContainer),
                        border = BorderStroke(1.dp, SignalGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_update_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SystemUpdate,
                                contentDescription = "New Update Available",
                                tint = SignalGreen,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "New App Update v${availableUpdate.versionName} Ready",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NavyPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Uploaded by ${availableUpdate.publishedByRole} • Tap to download",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NavyPrimary
                                )
                            }
                        }
                    }
                }
            }

            item {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isWide = maxWidth >= 600.dp
                    if (isWide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            KpiMetricCard(
                                title = "Total Weight Records",
                                value = totalRecords.toString(),
                                icon = Icons.Filled.Inventory2,
                                accentColor = NavyPrimary,
                                containerColor = NavyContainer,
                                modifier = Modifier.weight(1f),
                                testTag = "kpi_total_records"
                            )
                            KpiMetricCard(
                                title = "Verified Vehicles",
                                value = verifiedVehicles.toString(),
                                icon = Icons.Filled.LocalShipping,
                                accentColor = SteelAccent,
                                containerColor = SteelContainer,
                                modifier = Modifier.weight(1f),
                                testTag = "kpi_verified_vehicles"
                            )
                            KpiMetricCard(
                                title = "Green Signals",
                                value = greenSignals.toString(),
                                icon = Icons.Filled.CheckCircle,
                                accentColor = SignalGreen,
                                containerColor = SignalGreenContainer,
                                modifier = Modifier.weight(1f),
                                testTag = "kpi_green_signals"
                            )
                            KpiMetricCard(
                                title = "Red Alerts",
                                value = redAlerts.toString(),
                                icon = Icons.Filled.Error,
                                accentColor = AlertRed,
                                containerColor = AlertRedContainer,
                                modifier = Modifier.weight(1f),
                                testTag = "kpi_red_alerts"
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                KpiMetricCard(
                                    title = "Total Weight Records",
                                    value = totalRecords.toString(),
                                    icon = Icons.Filled.Inventory2,
                                    accentColor = NavyPrimary,
                                    containerColor = NavyContainer,
                                    modifier = Modifier.weight(1f),
                                    testTag = "kpi_total_records"
                                )
                                KpiMetricCard(
                                    title = "Verified Vehicles",
                                    value = verifiedVehicles.toString(),
                                    icon = Icons.Filled.LocalShipping,
                                    accentColor = SteelAccent,
                                    containerColor = SteelContainer,
                                    modifier = Modifier.weight(1f),
                                    testTag = "kpi_verified_vehicles"
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                KpiMetricCard(
                                    title = "Green Signals",
                                    value = greenSignals.toString(),
                                    icon = Icons.Filled.CheckCircle,
                                    accentColor = SignalGreen,
                                    containerColor = SignalGreenContainer,
                                    modifier = Modifier.weight(1f),
                                    testTag = "kpi_green_signals"
                                )
                                KpiMetricCard(
                                    title = "Red Alerts",
                                    value = redAlerts.toString(),
                                    icon = Icons.Filled.Error,
                                    accentColor = AlertRed,
                                    containerColor = AlertRedContainer,
                                    modifier = Modifier.weight(1f),
                                    testTag = "kpi_red_alerts"
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onScanDocumentClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(62.dp)
                            .testTag("scan_document_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NavyPrimary,
                            contentColor = PureWhite
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DocumentScanner,
                            contentDescription = "Scan Document",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Scan Document",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onUploadGalleryClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("upload_gallery_button"),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, NavyPrimary),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = PureWhite,
                            contentColor = NavyPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoLibrary,
                            contentDescription = "Upload From Gallery",
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Upload From Gallery",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Weight Records",
                        style = MaterialTheme.typography.titleLarge,
                        color = NavyPrimary
                    )
                    if (records.isNotEmpty()) {
                        TextButton(
                            onClick = onViewAllHistoryClick,
                            modifier = Modifier.testTag("view_all_history_button")
                        ) {
                            Text("View All (${records.size})")
                        }
                    }
                }
            }

            when (recordsState) {
                is UiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = NavyPrimary)
                        }
                    }
                }

                is UiState.Error -> {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = AlertRedContainer),
                            border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = recordsState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AlertRed,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                is UiState.Success -> {
                    if (records.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = PureWhite),
                                border = BorderStroke(1.dp, SlateBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DocumentScanner,
                                        contentDescription = null,
                                        tint = NavyPrimary.copy(alpha = 0.55f),
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Text(
                                        text = "No Weighbridge Records Yet",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = NavyPrimary
                                    )
                                    Text(
                                        text = "Tap Scan Document or Upload From Gallery to capture a weighbridge slip, verify tare weight, and log your first vehicle baseline.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SlateTextSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        items(records.take(6), key = { it.id }) { record ->
                            WeightRecordCard(
                                record = record,
                                onClick = { onRecordClick(record) }
                            )
                        }
                    }
                }
            }

            if (adsConfig.adsEnabled) {
                item {
                    OwnerAdBannerView(adsConfig = adsConfig)
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun HomeHeaderCard(
    userEmail: String,
    userRole: UserRole,
    onManageTeamClick: () -> Unit,
    onSignOutClick: () -> Unit
) {
    val isPrivileged = userRole.canManageUsers
    val badgeText = when (userRole) {
        UserRole.OWNER -> "OWNER ADMIN VERIFIED"
        UserRole.ADMIN -> "ADMIN ACCESS"
        UserRole.USER -> "SHARED TEAM USER"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(NavyPrimary, NavySecondary)
                )
            )
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PureWhite.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = "TruckWeight Guard",
                            tint = SignalGreen,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "TruckWeight Guard",
                            style = MaterialTheme.typography.titleLarge,
                            color = PureWhite
                        )
                        Text(
                            text = userEmail.ifBlank { "Authenticated User" },
                            style = MaterialTheme.typography.bodySmall,
                            color = PureWhite.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onSignOutClick,
                    modifier = Modifier.testTag("sign_out_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Sign Out",
                        tint = PureWhite
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (isPrivileged) SignalGreen.copy(alpha = 0.18f) else PureWhite.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AdminPanelSettings,
                            contentDescription = null,
                            tint = if (isPrivileged) SignalGreen else PureWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = PureWhite
                        )
                    }
                }

                if (userRole.canManageUsers) {
                    TextButton(
                        onClick = onManageTeamClick,
                        modifier = Modifier.testTag("home_manage_team_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Group,
                            contentDescription = "Manage Team",
                            tint = SignalGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Admin Panel",
                            style = MaterialTheme.typography.labelMedium,
                            color = SignalGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
