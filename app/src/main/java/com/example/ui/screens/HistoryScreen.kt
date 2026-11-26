package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AlertStatus
import com.example.data.model.TruckWeightRecord
import com.example.data.model.UiState
import com.example.domain.WeightVerificationEngine
import com.example.ui.components.AlertStatusPill
import com.example.ui.components.WeightRecordCard
import com.example.ui.components.alertVisualStyle
import com.example.ui.theme.AlertRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SlateBackground
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.viewmodel.HistoryFilterState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    recordsState: UiState<List<TruckWeightRecord>>,
    filteredRecords: List<TruckWeightRecord>,
    filterState: HistoryFilterState,
    initialSelectedVehicleKey: String?,
    onClearInitialVehicle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onAlertFilterChange: (AlertStatus?) -> Unit,
    onDateFilterChange: (String) -> Unit,
    onDeleteRecord: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allRecords = (recordsState as? UiState.Success)?.data.orEmpty()
    var selectedRecordForSheet by remember { mutableStateOf<TruckWeightRecord?>(null) }

    val activeVehicleSheetKey = selectedRecordForSheet?.vehicleKey ?: initialSelectedVehicleKey

    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val csv = WeightVerificationEngine.exportRecordsToCsv(filteredRecords)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                Toast.makeText(
                    context,
                    "Exported ${filteredRecords.size} records to CSV.",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    err.localizedMessage ?: "CSV export failed.",
                    Toast.LENGTH_LONG
                ).show()
            }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Verification History",
                            style = MaterialTheme.typography.headlineMedium,
                            color = NavyPrimary
                        )
                        Text(
                            text = "${filteredRecords.size} of ${allRecords.size} records shown",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlateTextSecondary
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val filename = "truckweight_records_${System.currentTimeMillis()}.csv"
                            csvExportLauncher.launch(filename)
                        },
                        enabled = filteredRecords.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, NavyPrimary),
                        modifier = Modifier.testTag("export_csv_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Export CSV",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export CSV")
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = filterState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search by vehicle number (e.g. BUG T-11-1633)") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = "Search")
                    },
                    trailingIcon = {
                        if (filterState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(imageVector = Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("history_search_input")
                )
            }

            item {
                OutlinedTextField(
                    value = filterState.dateFilterQuery,
                    onValueChange = onDateFilterChange,
                    placeholder = { Text("Filter by date (e.g. 22/11/2026 or 2026-11)") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Filled.CalendarToday, contentDescription = "Date filter")
                    },
                    trailingIcon = {
                        if (filterState.dateFilterQuery.isNotEmpty()) {
                            IconButton(onClick = { onDateFilterChange("") }) {
                                Icon(imageVector = Icons.Filled.Clear, contentDescription = "Clear date filter")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("history_date_filter_input")
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filterState.selectedAlertStatus == null,
                        onClick = { onAlertFilterChange(null) },
                        label = { Text("All Alerts") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NavyPrimary,
                            selectedLabelColor = PureWhite
                        )
                    )
                    AlertStatus.entries.forEach { status ->
                        val style = alertVisualStyle(status)
                        FilterChip(
                            selected = filterState.selectedAlertStatus == status,
                            onClick = {
                                onAlertFilterChange(
                                    if (filterState.selectedAlertStatus == status) null else status
                                )
                            },
                            label = { Text(status.displayTitle) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = style.accent,
                                selectedLabelColor = PureWhite
                            )
                        )
                    }
                }
            }

            if (filteredRecords.isEmpty()) {
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
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = SlateTextSecondary,
                                modifier = Modifier.size(38.dp)
                            )
                            Text(
                                text = "No Matching Weight Records",
                                style = MaterialTheme.typography.titleMedium,
                                color = NavyPrimary
                            )
                            Text(
                                text = "Adjust your search or alert filters, or scan a new weighbridge slip from Home.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateTextSecondary
                            )
                        }
                    }
                }
            } else {
                items(filteredRecords, key = { it.id }) { record ->
                    WeightRecordCard(
                        record = record,
                        onClick = { selectedRecordForSheet = record }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (!activeVehicleSheetKey.isNullOrBlank()) {
        val vehicleRecords = allRecords
            .filter { it.vehicleKey == activeVehicleSheetKey }
            .sortedByDescending { WeightVerificationEngine.recordSortMillis(it) }
        val displayPlate = vehicleRecords.firstOrNull()?.vehicleNumber ?: activeVehicleSheetKey
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = {
                selectedRecordForSheet = null
                onClearInitialVehicle()
            },
            sheetState = sheetState,
            containerColor = PureWhite
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocalShipping,
                            contentDescription = null,
                            tint = NavyPrimary
                        )
                        Column {
                            Text(
                                text = displayPlate,
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = NavyPrimary
                            )
                            Text(
                                text = "${vehicleRecords.size} recorded weighings",
                                style = MaterialTheme.typography.bodySmall,
                                color = SlateTextSecondary
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            selectedRecordForSheet = null
                            onClearInitialVehicle()
                        }
                    ) {
                        Text("Close")
                    }
                }

                HorizontalDivider(color = SlateBorder)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(vehicleRecords, key = { it.id }) { item ->
                        val style = alertVisualStyle(item.resolvedAlertStatus)
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = SlateBackground),
                            border = BorderStroke(1.dp, SlateBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = WeightVerificationEngine.formatWeightKg(item.truckWeight),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = NavyPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    AlertStatusPill(status = item.resolvedAlertStatus)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (item.previousWeight != null) {
                                            "Prev: ${WeightVerificationEngine.formatWeightKg(item.previousWeight)}  •  Δ ${WeightVerificationEngine.formatDifferenceKg(item.weightDifference)}"
                                        } else {
                                            "Baseline first record"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = style.onContainer
                                    )
                                    Text(
                                        text = item.weightDate,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SlateTextSecondary
                                    )
                                }

                                val confPct = (item.ocrConfidence * 100).roundToInt()
                                Text(
                                    text = "Label: ${item.sourceLabel ?: "Tare"}  •  OCR: $confPct%  •  Synced: ${WeightVerificationEngine.formatTimestamp(item.createdAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SlateTextSecondary
                                )

                                if (!item.telegramMessageId.isNullOrBlank()) {
                                    Surface(
                                        color = PureWhite,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, SlateBorder)
                                    ) {
                                        Text(
                                            text = "Telegram Msg #${item.telegramMessageId} • File: ${item.documentImageTelegramFileId ?: "stored"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NavyPrimary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = { onDeleteRecord(item.id) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteOutline,
                                            contentDescription = "Delete record",
                                            tint = AlertRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Delete", color = AlertRed)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
