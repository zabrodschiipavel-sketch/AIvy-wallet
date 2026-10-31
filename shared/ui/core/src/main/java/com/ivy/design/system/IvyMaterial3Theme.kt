package com.ivy.design.system

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.ivy.design.system.colors.IvyColors

@Composable
fun IvyMaterial3Theme(
    isTrueBlack: Boolean,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) ivyDarkColorScheme(isTrueBlack) else ivyLightColorScheme(),
        content = content,
    )
}

private fun ivyLightColorScheme(): ColorScheme = ColorScheme(
    primary = IvyColors.Purple.primary,
    onPrimary = IvyColors.White,
    primaryContainer = IvyColors.Purple.light,
    onPrimaryContainer = IvyColors.White,
    inversePrimary = IvyColors.Purple.dark,
    secondary = IvyColors.Green.primary,
    onSecondary = IvyColors.White,
    secondaryContainer = IvyColors.Green.light,
    onSecondaryContainer = IvyColors.White,
    tertiary = IvyColors.Green.primary,
    onTertiary = IvyColors.White,
    tertiaryContainer = IvyColors.Green.light,
    onTertiaryContainer = IvyColors.White,

    error = IvyColors.Red.primary,
    onError = IvyColors.White,
    errorContainer = IvyColors.Red.light,
    onErrorContainer = IvyColors.White,

    background = IvyColors.White,
    onBackground = IvyColors.Black,
    surface = IvyColors.White,
    onSurface = IvyColors.Black,
    surfaceVariant = IvyColors.ExtraLightGray,
    onSurfaceVariant = IvyColors.Black,
    surfaceDim = IvyColors.ExtraLightGray,
    surfaceBright = IvyColors.White,
    surfaceContainerLowest = IvyColors.White,
    surfaceContainerLow = IvyColors.ExtraLightGray,
    surfaceContainer = IvyColors.ExtraLightGray,
    surfaceContainerHigh = IvyColors.ExtraLightGray,
    surfaceContainerHighest = IvyColors.ExtraLightGray,
    surfaceTint = IvyColors.Black,
    inverseSurface = IvyColors.DarkGray,
    inverseOnSurface = IvyColors.White,

    outline = IvyColors.Gray,
    outlineVariant = IvyColors.DarkGray,
    scrim = IvyColors.ExtraDarkGray.copy(alpha = 0.8f)
).withElevatedSurfaceColor()

private fun ivyDarkColorScheme(isTrueBlack: Boolean): ColorScheme {
    val backgroundColor = if (isTrueBlack) IvyColors.TrueBlack else IvyColors.Black
    return ColorScheme(
        primary = IvyColors.Purple.primary,
        onPrimary = IvyColors.White,
        primaryContainer = IvyColors.Purple.light,
        onPrimaryContainer = IvyColors.White,
        inversePrimary = IvyColors.Purple.dark,
        secondary = IvyColors.Green.primary,
        onSecondary = IvyColors.White,
        secondaryContainer = IvyColors.Green.light,
        onSecondaryContainer = IvyColors.White,
        tertiary = IvyColors.Green.primary,
        onTertiary = IvyColors.White,
        tertiaryContainer = IvyColors.Green.light,
        onTertiaryContainer = IvyColors.White,

        error = IvyColors.Red.primary,
        onError = IvyColors.White,
        errorContainer = IvyColors.Red.light,
        onErrorContainer = IvyColors.White,

        background = backgroundColor,
        onBackground = IvyColors.White,
        surface = backgroundColor,
        onSurface = IvyColors.White,
        surfaceVariant = IvyColors.ExtraDarkGray,
        onSurfaceVariant = IvyColors.White,
        surfaceDim = backgroundColor,
        surfaceBright = IvyColors.ExtraDarkGray,
        surfaceContainerLowest = backgroundColor,
        surfaceContainerLow = IvyColors.ExtraDarkGray,
        surfaceContainer = IvyColors.ExtraDarkGray,
        surfaceContainerHigh = IvyColors.ExtraDarkGray,
        surfaceContainerHighest = IvyColors.ExtraDarkGray,
        surfaceTint = IvyColors.White,
        inverseSurface = IvyColors.LightGray,
        inverseOnSurface = backgroundColor,

        outline = IvyColors.Gray,
        outlineVariant = IvyColors.LightGray,
        scrim = IvyColors.ExtraLightGray.copy(alpha = 0.8f)
    ).withElevatedSurfaceColor()
}

/** Elevated cards retain the existing one-dp tinted surface. */
private fun ColorScheme.withElevatedSurfaceColor(): ColorScheme = copy(
    surfaceContainerLow = surfaceColorAtElevation(1.dp),
)
