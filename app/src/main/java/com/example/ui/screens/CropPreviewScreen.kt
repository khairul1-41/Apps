package com.example.ui.screens

import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.NavySecondary
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SignalGreen
import kotlin.math.abs

@Composable
fun CropPreviewScreen(
    imageUri: Uri?,
    isProcessing: Boolean,
    errorMessage: String?,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onApplyCrop: (RectF) -> Unit,
    onResetOriginal: () -> Unit,
    onRetake: () -> Unit,
    onAnalyzeSlip: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isCropMode by remember { mutableStateOf(false) }
    var cropLeft by remember { mutableFloatStateOf(0.08f) }
    var cropTop by remember { mutableFloatStateOf(0.12f) }
    var cropRight by remember { mutableFloatStateOf(0.92f) }
    var cropBottom by remember { mutableFloatStateOf(0.88f) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    var activeHandle by remember { mutableStateOf<Int?>(null) } // 0=TL,1=TR,2=BL,3=BR

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NavyPrimary)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
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
                text = if (isCropMode) "Adjust Crop Frame" else "Slip Preview & Adjust",
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite
            )
            OutlinedButton(
                onClick = onResetOriginal,
                enabled = !isProcessing,
                border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Reset",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reset", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Image Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Color.Black, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = "Captured weighbridge slip preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { canvasSize = it }
                )
            }

            if (isCropMode && canvasSize.width > 1 && canvasSize.height > 1) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val nx = offset.x / canvasSize.width.toFloat()
                                    val ny = offset.y / canvasSize.height.toFloat()
                                    val corners = listOf(
                                        0 to (abs(nx - cropLeft) + abs(ny - cropTop)),
                                        1 to (abs(nx - cropRight) + abs(ny - cropTop)),
                                        2 to (abs(nx - cropLeft) + abs(ny - cropBottom)),
                                        3 to (abs(nx - cropRight) + abs(ny - cropBottom))
                                    )
                                    activeHandle = corners.minByOrNull { it.second }?.first
                                },
                                onDragEnd = { activeHandle = null },
                                onDragCancel = { activeHandle = null },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val dx = dragAmount.x / canvasSize.width.toFloat()
                                    val dy = dragAmount.y / canvasSize.height.toFloat()
                                    val minSpan = 0.15f
                                    when (activeHandle) {
                                        0 -> {
                                            cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - minSpan)
                                            cropTop = (cropTop + dy).coerceIn(0f, cropBottom - minSpan)
                                        }
                                        1 -> {
                                            cropRight = (cropRight + dx).coerceIn(cropLeft + minSpan, 1f)
                                            cropTop = (cropTop + dy).coerceIn(0f, cropBottom - minSpan)
                                        }
                                        2 -> {
                                            cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - minSpan)
                                            cropBottom = (cropBottom + dy).coerceIn(cropTop + minSpan, 1f)
                                        }
                                        3 -> {
                                            cropRight = (cropRight + dx).coerceIn(cropLeft + minSpan, 1f)
                                            cropBottom = (cropBottom + dy).coerceIn(cropTop + minSpan, 1f)
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    val leftPx = cropLeft * size.width
                    val topPx = cropTop * size.height
                    val rightPx = cropRight * size.width
                    val bottomPx = cropBottom * size.height

                    // Dim outside area
                    drawRect(
                        color = Color.Black.copy(alpha = 0.45f),
                        topLeft = Offset.Zero,
                        size = Size(size.width, topPx)
                    )
                    drawRect(
                        color = Color.Black.copy(alpha = 0.45f),
                        topLeft = Offset(0f, bottomPx),
                        size = Size(size.width, size.height - bottomPx)
                    )
                    drawRect(
                        color = Color.Black.copy(alpha = 0.45f),
                        topLeft = Offset(0f, topPx),
                        size = Size(leftPx, bottomPx - topPx)
                    )
                    drawRect(
                        color = Color.Black.copy(alpha = 0.45f),
                        topLeft = Offset(rightPx, topPx),
                        size = Size(size.width - rightPx, bottomPx - topPx)
                    )

                    drawRect(
                        color = SignalGreen,
                        topLeft = Offset(leftPx, topPx),
                        size = Size(rightPx - leftPx, bottomPx - topPx),
                        style = Stroke(width = 4f)
                    )

                    listOf(
                        Offset(leftPx, topPx),
                        Offset(rightPx, topPx),
                        Offset(leftPx, bottomPx),
                        Offset(rightPx, bottomPx)
                    ).forEach { corner ->
                        drawCircle(color = PureWhite, radius = 18f, center = corner)
                        drawCircle(color = SignalGreen, radius = 12f, center = corner)
                    }
                }
            }

            if (isProcessing) {
                Surface(
                    color = NavyPrimary.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = PureWhite,
                            strokeWidth = 2.5.dp
                        )
                        Text("Processing image...", color = PureWhite)
                    }
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFCA5A5),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // Edit Toolbar
        Surface(
            color = NavySecondary,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    EditToolButton(
                        icon = Icons.AutoMirrored.Filled.RotateLeft,
                        label = "Rotate -90°",
                        enabled = !isProcessing && !isCropMode,
                        onClick = onRotateLeft,
                        testTag = "rotate_left_button"
                    )
                    EditToolButton(
                        icon = Icons.AutoMirrored.Filled.RotateRight,
                        label = "Rotate +90°",
                        enabled = !isProcessing && !isCropMode,
                        onClick = onRotateRight,
                        testTag = "rotate_right_button"
                    )
                    EditToolButton(
                        icon = if (isCropMode) Icons.Filled.Check else Icons.Filled.Crop,
                        label = if (isCropMode) "Apply Crop" else "Crop Slip",
                        enabled = !isProcessing,
                        isHighlighted = isCropMode,
                        onClick = {
                            if (isCropMode) {
                                onApplyCrop(RectF(cropLeft, cropTop, cropRight, cropBottom))
                                isCropMode = false
                            } else {
                                isCropMode = true
                            }
                        },
                        testTag = "crop_toggle_button"
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isCropMode) isCropMode = false else onRetake()
                        },
                        enabled = !isProcessing,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("retake_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite)
                    ) {
                        Text(if (isCropMode) "Cancel Crop" else "Retake")
                    }

                    Button(
                        onClick = {
                            if (isCropMode) {
                                onApplyCrop(RectF(cropLeft, cropTop, cropRight, cropBottom))
                                isCropMode = false
                            }
                            onAnalyzeSlip()
                        },
                        enabled = !isProcessing && imageUri != null,
                        modifier = Modifier
                            .weight(1.6f)
                            .height(52.dp)
                            .testTag("analyze_slip_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SignalGreen,
                            contentColor = PureWhite
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DocumentScanner,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Analyze Slip",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    isHighlighted: Boolean = false
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (isHighlighted) SignalGreen.copy(alpha = 0.25f) else PureWhite.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isHighlighted) SignalGreen else PureWhite.copy(alpha = 0.2f)),
        modifier = Modifier.testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isHighlighted) SignalGreen else PureWhite,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = PureWhite
            )
        }
    }
}
