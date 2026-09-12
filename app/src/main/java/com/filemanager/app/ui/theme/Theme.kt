package com.filemanager.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = OneUiLightPrimary,
    onPrimary = OneUiLightSurface,
    primaryContainer = OneUiLightPrimaryContainer,
    onPrimaryContainer = OneUiLightPrimary,
    secondary = OneUiLightPrimary,
    background = OneUiLightBackground,
    onBackground = OneUiLightOnSurface,
    surface = OneUiLightSurface,
    onSurface = OneUiLightOnSurface,
    surfaceVariant = OneUiLightSurfaceVariant,
    onSurfaceVariant = OneUiLightOnSurfaceVariant,
    outline = OneUiLightOutline,
    outlineVariant = OneUiLightOutline,
    error = OneUiLightError,
)

private val DarkColors = darkColorScheme(
    primary = OneUiDarkPrimary,
    onPrimary = OneUiDarkBackground,
    primaryContainer = OneUiDarkPrimaryContainer,
    onPrimaryContainer = OneUiDarkPrimary,
    secondary = OneUiDarkPrimary,
    background = OneUiDarkBackground,
    onBackground = OneUiDarkOnSurface,
    surface = OneUiDarkSurface,
    onSurface = OneUiDarkOnSurface,
    surfaceVariant = OneUiDarkSurfaceVariant,
    onSurfaceVariant = OneUiDarkOnSurfaceVariant,
    outline = OneUiDarkOutline,
    outlineVariant = OneUiDarkOutline,
    error = OneUiDarkError,
)

private val OneUiShapes = Shapes(
    extraSmall = OneUi.ThumbShape,
    small = OneUi.ThumbShape,
    medium = OneUi.CardShape,
    large = OneUi.GroupShape,
    extraLarge = OneUi.GroupShape,
)

/**
 * Material You is deliberately off by default.
 *
 * Dynamic colour recolours the app from the user's wallpaper, which is the
 * opposite of what is wanted here - the whole point is the Samsung blue and
 * the fixed category accents. It stays available as a parameter for anyone
 * who prefers wallpaper theming.
 */
@Composable
fun FileManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = OneUiShapes,
        content = content,
    )
}
