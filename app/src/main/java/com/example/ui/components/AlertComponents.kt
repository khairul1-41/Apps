package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AlertStatus
import com.example.data.model.TruckWeightRecord
import com.example.domain.WeightVerificationEngine
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertRedContainer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.NeutralSlate
import com.example.ui.theme.NeutralSlateContainer
import com.example.ui.theme.OnAlertRedContainer
import com.example.ui.theme.OnNeutralSlateContainer
import com.example.ui.theme.OnSignalGreenContainer
import com.example.ui.theme.OnWarningOrangeContainer
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.SignalGreenContainer
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.WarningOrange
import com.example.ui.theme.WarningOrangeContainer

data class AlertVisualStyle(
    val accent: Color,
    val container: Color,
    val onContainer: Color,
    val icon: ImageVector
)

fun alertVisualStyle(status: AlertStatus): AlertVisualStyle = when (status) {
    AlertStatus.GREEN_SIGNAL -> AlertVisualStyle(
        accent = SignalGreen,
        container = SignalGreenContainer,
        onContainer = OnSignalGreenContainer,
        icon = Icons.Filled.CheckCircle
    )
    AlertStatus.ORANGE_WARNING -> AlertVisualStyle(
        accent = WarningOrange,
        container = WarningOrangeContainer,
        onContainer = OnWarningOrangeContainer,
        icon = Icons.Filled.Warning
    )
    AlertStatus.RED_ALERT -> AlertVisualStyle(
        accent = AlertRed,
        container = AlertRedContainer,
        onContainer = OnAlertRedContainer,
        icon = Icons.Filled.Error
    )
    AlertStatus.NEUTRAL -> AlertVisualStyle(
        accent = NeutralSlate,
        container = NeutralSlateContainer,
        onContainer = OnNeutralSlateContainer,
        icon = Icons.Filled.RemoveCircle
    )
}

@Composable
fun AlertStatusPill(
    status: AlertStatus,
    modifier: Modifier = Modifier
) {
    val style = alertVisualStyle(status)
    Surface(
        modifier = modifier,
        color = style.container,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, style.accent.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = status.displayTitle,
                tint = style.accent,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = status.shortBadge,
                style = MaterialTheme.typography.labelSmall,
                color = style.onContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun KpiMetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    accentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    testTag: String = ""
) {
    Card(
        modifier = modifier.then(
            if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier
        ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(1.dp, SlateBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(containerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = JetBrainsMonoFontFamily,
                color = NavyPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = SlateTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun WeightRecordCard(
    record: TruckWeightRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = record.resolvedAlertStatus
    val style = alertVisualStyle(status)
    val hasTelegram = !record.telegramMessageId.isNullOrBlank()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("weight_record_card_${record.id}")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(1.dp, SlateBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(style.accent)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocalShipping,
                            contentDescription = "Vehicle",
                            tint = NavyPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = record.vehicleNumber,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = NavyPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    AlertStatusPill(status = status)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = WeightVerificationEngine.formatWeightKg(record.truckWeight),
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = NavyPrimary
                        )
                        val prevText = if (record.previousWeight != null) {
                            "Prev ${WeightVerificationEngine.formatWeightKg(record.previousWeight)}  •  Δ ${WeightVerificationEngine.formatDifferenceKg(record.weightDifference)}"
                        } else {
                            "First recorded weighing for vehicle"
                        }
                        Text(
                            text = prevText,
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateTextSecondary
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = record.weightDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateTextSecondary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = if (hasTelegram) "Sent to Telegram" else "Pending Telegram",
                                tint = if (hasTelegram) SignalGreen else SlateTextSecondary.copy(alpha = 0.6f),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (hasTelegram) "TG Sent" else "TG Pending",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasTelegram) SignalGreen else SlateTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
