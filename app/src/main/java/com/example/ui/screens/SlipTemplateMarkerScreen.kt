package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.OwnerPolicy
import com.example.data.model.SlipFormatTemplate
import com.example.data.model.SlipRegionBox
import com.example.data.ocr.WeighbridgeOcrService
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
import com.example.ui.viewmodel.SlipTemplateActionState
import kotlinx.coroutines.launch

private enum class MarkerFieldType(
    val title: String,
    val badge: String,
    val color: Color
) {
    VEHICLE_NO("Truck / Vehicle No", "TRUCK NO", Color(0xFF2563EB)),
    TRUCK_WEIGHT("Truck Weight (KG)", "WEIGHT", Color(0xFFDC2626)),
    WEIGHT_DATE("Weight Date", "DATE", Color(0xFF16A34A))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SlipTemplateMarkerScreen(
    userEmail: String,
    isOwner: Boolean,
    templates: List<SlipFormatTemplate>,
    actionState: SlipTemplateActionState,
    onSaveTemplate: (SlipFormatTemplate) -> Unit,
    onDeleteTemplate: (String) -> Unit,
    onDismissActionState: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ocrHelper = remember(context) { WeighbridgeOcrService(context) }

    var sampleImageUri by remember { mutableStateOf<Uri?>(null) }
    var imageAspectRatio by remember { mutableFloatStateOf(0.75f) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }

    var activeMarker by remember { mutableStateOf(MarkerFieldType.VEHICLE_NO) }
    var vehicleBox by remember { mutableStateOf<SlipRegionBox?>(null) }
    var weightBox by remember { mutableStateOf<SlipRegionBox?>(null) }
    var dateBox by remember { mutableStateOf<SlipRegionBox?>(null) }

    var dragStartNorm by remember { mutableStateOf<Offset?>(null) }
    var dragCurrentNorm by remember { mutableStateOf<Offset?>(null) }

    var formatName by remember { mutableStateOf("") }
    var headerHint by remember { mutableStateOf("") }
    var vehicleLabelHint by remember { mutableStateOf("") }
    var weightLabelHint by remember { mutableStateOf("") }
    var dateLabelHint by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onDismissActionState()
            scope.launch {
                val cachedUri = runCatching { ocrHelper.copyUriToEditableCache(uri) }.getOrElse { uri }
                val ratio = ocrHelper.readImageAspectRatio(cachedUri)
                imageAspectRatio = ratio
                sampleImageUri = cachedUri
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Custom Scale Slip Formats",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Owner Control: Mark field positions for new slips",
                            style = MaterialTheme.typography.labelSmall,
                            color = SlateSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("slip_templates_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = PureWhite
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NavyPrimary)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SlateBackground)
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (!isOwner) {
                Card(
                    modifier = Modifier
                        .padding(16.dp)
                        .widthIn(max = 520.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = WarningOrangeContainer),
                    border = BorderStroke(1.dp, WarningOrange)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = WarningOrange)
                            Text(
                                text = "Owner Access Required",
                                style = MaterialTheme.typography.titleMedium,
                                color = NavyPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Signed in as $userEmail. Only the verified owner (${OwnerPolicy.OWNER_EMAIL}) can mark and upload new scale slip formats.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NavyPrimary
                        )
                        Button(
                            onClick = onBackClick,
                            colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Return")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = NavyContainer),
                            border = BorderStroke(1.dp, NavyPrimary.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Train Gemini OCR on Any New Slip Layout",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NavyPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "1. Pick a sample photo of the new scale slip.\n2. Select Truck No, Truck Weight, or Weight Date and drag your finger to mark each area on the slip.\n3. Upload the format — every user's scan will automatically extract data from those marked positions.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NavyPrimary
                                )
                            }
                        }
                    }

                    // Format Metadata + Sample Image Picker
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
                                Text(
                                    text = "1. New Slip Format Details",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NavyPrimary,
                                    fontWeight = FontWeight.Bold
                                )

                                OutlinedTextField(
                                    value = formatName,
                                    onValueChange = {
                                        formatName = it
                                        onDismissActionState()
                                    },
                                    label = { Text("Format / Scale Name *") },
                                    placeholder = { Text("e.g. Padma Digital Scale Slip") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("template_format_name_input")
                                )

                                OutlinedTextField(
                                    value = headerHint,
                                    onValueChange = {
                                        headerHint = it
                                        onDismissActionState()
                                    },
                                    label = { Text("Scale Header / Title Hint (Optional)") },
                                    placeholder = { Text("Printed title at top of slip for faster matching") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedButton(
                                    onClick = {
                                        imagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.2.dp, NavyPrimary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("pick_template_sample_image_button")
                                ) {
                                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = NavyPrimary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (sampleImageUri == null) "Select Sample Scale Slip Photo" else "Change Sample Slip Photo",
                                        color = NavyPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Interactive Canvas Marker Card
                    if (sampleImageUri != null) {
                        item {
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = PureWhite),
                                border = BorderStroke(1.5.dp, NavyPrimary.copy(alpha = 0.35f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Filled.CropFree, contentDescription = null, tint = NavyPrimary)
                                        Text(
                                            text = "2. Drag Boxes to Mark Locations",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = NavyPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        MarkerFieldType.entries.forEach { field ->
                                            val isSelected = activeMarker == field
                                            val isMarked = when (field) {
                                                MarkerFieldType.VEHICLE_NO -> vehicleBox != null
                                                MarkerFieldType.TRUCK_WEIGHT -> weightBox != null
                                                MarkerFieldType.WEIGHT_DATE -> dateBox != null
                                            }
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { activeMarker = field },
                                                label = {
                                                    Text(
                                                        text = if (isMarked) "${field.title} ✓" else field.title,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                    )
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = field.color.copy(alpha = 0.18f),
                                                    selectedLabelColor = field.color
                                                )
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Active tool: ${activeMarker.title} — drag a rectangle over its position on the slip below.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = activeMarker.color,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(imageAspectRatio)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.Black)
                                    ) {
                                        AsyncImage(
                                            model = sampleImageUri,
                                            contentDescription = "Sample scale slip for marking",
                                            contentScale = ContentScale.FillBounds,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .onSizeChanged { canvasSize = it }
                                        )

                                        Canvas(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .pointerInput(activeMarker, canvasSize) {
                                                    detectDragGestures(
                                                        onDragStart = { startOffset ->
                                                            if (canvasSize.width > 0 && canvasSize.height > 0) {
                                                                val nx = (startOffset.x / canvasSize.width.toFloat()).coerceIn(0f, 1f)
                                                                val ny = (startOffset.y / canvasSize.height.toFloat()).coerceIn(0f, 1f)
                                                                dragStartNorm = Offset(nx, ny)
                                                                dragCurrentNorm = Offset(nx, ny)
                                                            }
                                                        },
                                                        onDrag = { change, _ ->
                                                            change.consume()
                                                            if (canvasSize.width > 0 && canvasSize.height > 0) {
                                                                val nx = (change.position.x / canvasSize.width.toFloat()).coerceIn(0f, 1f)
                                                                val ny = (change.position.y / canvasSize.height.toFloat()).coerceIn(0f, 1f)
                                                                dragCurrentNorm = Offset(nx, ny)
                                                            }
                                                        },
                                                        onDragEnd = {
                                                            val s = dragStartNorm
                                                            val e = dragCurrentNorm
                                                            if (s != null && e != null) {
                                                                val box = SlipRegionBox(
                                                                    left = s.x.toDouble(),
                                                                    top = s.y.toDouble(),
                                                                    right = e.x.toDouble(),
                                                                    bottom = e.y.toDouble()
                                                                ).normalizedClamped()
                                                                when (activeMarker) {
                                                                    MarkerFieldType.VEHICLE_NO -> vehicleBox = box
                                                                    MarkerFieldType.TRUCK_WEIGHT -> weightBox = box
                                                                    MarkerFieldType.WEIGHT_DATE -> dateBox = box
                                                                }
                                                            }
                                                            dragStartNorm = null
                                                            dragCurrentNorm = null
                                                        },
                                                        onDragCancel = {
                                                            dragStartNorm = null
                                                            dragCurrentNorm = null
                                                        }
                                                    )
                                                }
                                        ) {
                                            fun drawMarkedBox(box: SlipRegionBox?, color: Color) {
                                                if (box == null || !box.isValid()) return
                                                val l = box.left.toFloat() * size.width
                                                val t = box.top.toFloat() * size.height
                                                val w = (box.right - box.left).toFloat() * size.width
                                                val h = (box.bottom - box.top).toFloat() * size.height
                                                drawRect(
                                                    color = color.copy(alpha = 0.22f),
                                                    topLeft = Offset(l, t),
                                                    size = Size(w, h)
                                                )
                                                drawRect(
                                                    color = color,
                                                    topLeft = Offset(l, t),
                                                    size = Size(w, h),
                                                    style = Stroke(width = 6f)
                                                )
                                            }

                                            drawMarkedBox(vehicleBox, MarkerFieldType.VEHICLE_NO.color)
                                            drawMarkedBox(weightBox, MarkerFieldType.TRUCK_WEIGHT.color)
                                            drawMarkedBox(dateBox, MarkerFieldType.WEIGHT_DATE.color)

                                            val s = dragStartNorm
                                            val e = dragCurrentNorm
                                            if (s != null && e != null) {
                                                val previewBox = SlipRegionBox(
                                                    left = s.x.toDouble(),
                                                    top = s.y.toDouble(),
                                                    right = e.x.toDouble(),
                                                    bottom = e.y.toDouble()
                                                ).normalizedClamped()
                                                drawMarkedBox(previewBox, activeMarker.color)
                                            }
                                        }
                                    }

                                    // Marked Coordinates Summary + Clear buttons
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        MarkedFieldSummaryRow(
                                            label = "Truck No (Blue)",
                                            box = vehicleBox,
                                            accent = MarkerFieldType.VEHICLE_NO.color,
                                            required = true,
                                            onClear = { vehicleBox = null }
                                        )
                                        MarkedFieldSummaryRow(
                                            label = "Truck Weight (Red)",
                                            box = weightBox,
                                            accent = MarkerFieldType.TRUCK_WEIGHT.color,
                                            required = true,
                                            onClear = { weightBox = null }
                                        )
                                        MarkedFieldSummaryRow(
                                            label = "Weight Date (Green)",
                                            box = dateBox,
                                            accent = MarkerFieldType.WEIGHT_DATE.color,
                                            required = false,
                                            onClear = { dateBox = null }
                                        )
                                    }

                                    // Optional printed label hints
                                    OutlinedTextField(
                                        value = vehicleLabelHint,
                                        onValueChange = { vehicleLabelHint = it },
                                        label = { Text("Truck No Label Hint (Optional)") },
                                        placeholder = { Text("e.g. Vehicle No :, Gari No") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = weightLabelHint,
                                        onValueChange = { weightLabelHint = it },
                                        label = { Text("Truck Weight Label Hint (Optional)") },
                                        placeholder = { Text("e.g. Empty Wt :, Tare Weight") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = dateLabelHint,
                                        onValueChange = { dateLabelHint = it },
                                        label = { Text("Weight Date Label Hint (Optional)") },
                                        placeholder = { Text("e.g. Date :, In Time") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = notes,
                                        onValueChange = { notes = it },
                                        label = { Text("Extraction Notes (Optional)") },
                                        placeholder = { Text("Any special rule for this slip") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    // Action feedback
                    item {
                        when (actionState) {
                            is SlipTemplateActionState.Success -> {
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
                            is SlipTemplateActionState.Error -> {
                                Surface(
                                    color = AlertRedContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, AlertRed)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AlertRed)
                                        Text(actionState.message, color = AlertRed, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }

                    item {
                        val isSaving = actionState is SlipTemplateActionState.Saving
                        Button(
                            onClick = {
                                onSaveTemplate(
                                    SlipFormatTemplate(
                                        formatName = formatName,
                                        headerHint = headerHint,
                                        vehicleBox = vehicleBox,
                                        vehicleLabelHint = vehicleLabelHint,
                                        weightBox = weightBox,
                                        weightLabelHint = weightLabelHint,
                                        dateBox = dateBox,
                                        dateLabelHint = dateLabelHint,
                                        notes = notes,
                                        isActive = true
                                    )
                                )
                            },
                            enabled = !isSaving && sampleImageUri != null,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("upload_slip_template_button")
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = PureWhite)
                            } else {
                                Icon(Icons.Filled.CloudUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Upload & Activate Slip Format",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Existing uploaded templates
                    item {
                        Text(
                            text = "Active Custom Slip Formats (${templates.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = NavyPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (templates.isEmpty()) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = PureWhite),
                                border = BorderStroke(1.dp, SlateBorder)
                            ) {
                                Text(
                                    text = "No custom slip formats uploaded yet. Built-in Bangladesh weighbridge formats remain active.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SlateTextSecondary,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        items(templates, key = { it.id.ifBlank { it.formatName } }) { tmpl ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = PureWhite),
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
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Filled.ReceiptLong, contentDescription = null, tint = NavyPrimary)
                                            Column {
                                                Text(
                                                    text = tmpl.formatName,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = NavyPrimary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                if (tmpl.headerHint.isNotBlank()) {
                                                    Text(
                                                        text = "Header: ${tmpl.headerHint}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = SlateTextSecondary
                                                    )
                                                }
                                            }
                                        }
                                        IconButton(
                                            onClick = { onDeleteTemplate(tmpl.id) },
                                            modifier = Modifier.testTag("delete_template_${tmpl.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.DeleteOutline,
                                                contentDescription = "Delete Format",
                                                tint = AlertRed
                                            )
                                        }
                                    }

                                    tmpl.vehicleBox?.let { box ->
                                        Text(
                                            text = "• Truck No: ${box.toPercentSummary()} ${if (tmpl.vehicleLabelHint.isNotBlank()) "(${tmpl.vehicleLabelHint})" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = MarkerFieldType.VEHICLE_NO.color
                                        )
                                    }
                                    tmpl.weightBox?.let { box ->
                                        Text(
                                            text = "• Truck Weight: ${box.toPercentSummary()} ${if (tmpl.weightLabelHint.isNotBlank()) "(${tmpl.weightLabelHint})" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = MarkerFieldType.TRUCK_WEIGHT.color
                                        )
                                    }
                                    tmpl.dateBox?.takeIf { it.isValid() }?.let { box ->
                                        Text(
                                            text = "• Weight Date: ${box.toPercentSummary()} ${if (tmpl.dateLabelHint.isNotBlank()) "(${tmpl.dateLabelHint})" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = MarkerFieldType.WEIGHT_DATE.color
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MarkedFieldSummaryRow(
    label: String,
    box: SlipRegionBox?,
    accent: Color,
    required: Boolean,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (required) "$label *" else "$label (Optional)",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = box?.takeIf { it.isValid() }?.toPercentSummary() ?: "Not marked yet — drag on image above",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = SlateTextSecondary
            )
        }
        if (box != null) {
            TextButton(onClick = onClear) {
                Text("Clear", color = AlertRed)
            }
        }
    }
}
