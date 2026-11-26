package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val IndustrialLightColorScheme = lightColorScheme(
    primary = NavyPrimary,
    onPrimary = PureWhite,
    primaryContainer = NavyContainer,
    onPrimaryContainer = OnNavyContainer,
    secondary = NavySecondary,
    onSecondary = PureWhite,
    secondaryContainer = SteelContainer,
    onSecondaryContainer = OnSteelContainer,
    tertiary = SignalGreen,
    onTertiary = PureWhite,
    tertiaryContainer = SignalGreenContainer,
    onTertiaryContainer = OnSignalGreenContainer,
    background = PureWhite,
    onBackground = SlateTextPrimary,
    surface = PureWhite,
    onSurface = SlateTextPrimary,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = SlateTextSecondary,
    outline = SlateBorder,
    error = AlertRed,
    onError = PureWhite,
    errorContainer = AlertRedContainer,
    onErrorContainer = OnAlertRedContainer
)

private val IndustrialShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TruckWeightGuardTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IndustrialLightColorScheme,
        typography = AppTypography,
        shapes = IndustrialShapes,
        content = content
    )
}
