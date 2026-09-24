package com.filemanager.app.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.filemanager.app.data.ColorPart
import com.filemanager.app.data.DarkStyle
import com.filemanager.app.data.ThemeAccent
import com.filemanager.app.data.ThemeChoice
import com.filemanager.app.data.ThemeMode

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

private val DimColors = darkColorScheme(
    background = OneUiDimBackground,
    onBackground = OneUiDimOnSurface,
    surface = OneUiDimSurface,
    onSurface = OneUiDimOnSurface,
    surfaceVariant = OneUiDimSurfaceVariant,
    onSurfaceVariant = OneUiDimOnSurfaceVariant,
    outline = OneUiDimOutline,
    outlineVariant = OneUiDimOutline,
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
 * The app's theme: light, dark or the device's choice; black or dim when dark;
 * an accent from the palette or the wallpaper; and any colour set by hand.
 *
 * Every colour fades to its new value rather than jumping, so changing the
 * theme - or the device switching to night mode - looks like one change
 * instead of the whole screen flashing.
 */
@Composable
fun FileManagerTheme(
    theme: ThemeChoice = ThemeChoice(),
    content: @Composable () -> Unit,
) {
    val dark = isDark(theme.mode)
    val scheme = buildColorScheme(dark, theme.darkStyle, accentColor(theme.accent, dark), theme.custom)
    val categories = buildCategoryPalette(theme.custom)

    val shownScheme = animateColorScheme(scheme)
    val shownCategories = animateCategoryPalette(categories)
    SystemBars(shownScheme.background)

    CompositionLocalProvider(LocalCategoryPalette provides shownCategories) {
        MaterialTheme(
            colorScheme = shownScheme,
            typography = AppTypography,
            shapes = OneUiShapes,
            content = content,
        )
    }
}

@Composable
private fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The accent for a light or a dark page. The wallpaper's comes from Android
 * (12 and newer); on anything older it falls back to the One UI blue.
 */
@Composable
fun accentColor(accent: ThemeAccent, dark: Boolean): Color {
    if (accent == ThemeAccent.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return remember(context, dark) {
            if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
        }
    }
    val shades = AccentPalette[accent] ?: AccentPalette.getValue(ThemeAccent.BLUE)
    return if (dark) shades.dark else shades.light
}

/**
 * The colours the theme gives each part before any are set by hand - what a
 * part goes back to when its own colour is reset.
 */
@Composable
fun themeDefaults(theme: ThemeChoice): Pair<ColorScheme, CategoryPalette> {
    val dark = isDark(theme.mode)
    return buildColorScheme(dark, theme.darkStyle, accentColor(theme.accent, dark), emptyMap()) to
        buildCategoryPalette(emptyMap())
}

/** Where each part's colour lives in the theme. */
fun ColorPart.colorIn(scheme: ColorScheme, categories: CategoryPalette): Color = when (this) {
    ColorPart.ACCENT -> scheme.primary
    ColorPart.BACKGROUND -> scheme.background
    ColorPart.CARDS -> scheme.surface
    ColorPart.TEXT -> scheme.onSurface
    ColorPart.SECONDARY_TEXT -> scheme.onSurfaceVariant
    ColorPart.FOLDERS -> categories.directory
    ColorPart.IMAGES -> categories.image
    ColorPart.VIDEOS -> categories.video
    ColorPart.AUDIO -> categories.audio
    ColorPart.DOCUMENTS -> categories.document
    ColorPart.DOWNLOADS -> categories.downloads
    ColorPart.INSTALLERS -> categories.apk
    ColorPart.ARCHIVES -> categories.archive
}

/**
 * The full scheme from a page style, an accent and any hand-set colours.
 *
 * What Material draws from - containers, menus, dialogs, the selection tint -
 * is worked out from those few, so a card colour chosen by hand is the colour
 * of the menus and dialogs too, and the selection tint always belongs to the
 * accent in use.
 */
private fun buildColorScheme(
    dark: Boolean,
    style: DarkStyle,
    accent: Color,
    custom: Map<ColorPart, Int>,
): ColorScheme {
    val base = when {
        !dark -> LightColors
        style == DarkStyle.DIM -> DimColors
        else -> DarkColors
    }
    fun set(part: ColorPart) = custom[part]?.let { Color(it) }

    val text = set(ColorPart.TEXT) ?: base.onSurface
    val cards = set(ColorPart.CARDS)
    val surface = cards ?: base.surface
    val surfaceVariant = cards?.let { lerp(it, text, 0.08f) } ?: base.surfaceVariant
    val outline = cards?.let { lerp(it, text, 0.14f) } ?: base.outline
    val primary = set(ColorPart.ACCENT) ?: accent
    val onPrimary = readableOn(primary)
    val container = lerp(surface, primary, if (dark) 0.24f else 0.14f)

    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = primary,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = container,
        onSecondaryContainer = primary,
        tertiary = primary,
        onTertiary = onPrimary,
        background = set(ColorPart.BACKGROUND) ?: base.background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = set(ColorPart.SECONDARY_TEXT) ?: base.onSurfaceVariant,
        surfaceTint = primary,
        surfaceBright = surface,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surfaceVariant,
        inverseSurface = text,
        inverseOnSurface = surface,
        outline = outline,
        outlineVariant = outline,
    )
}

private fun buildCategoryPalette(custom: Map<ColorPart, Int>): CategoryPalette {
    fun pick(part: ColorPart, fallback: Color) = custom[part]?.let { Color(it) } ?: fallback
    val d = DefaultCategoryPalette
    return CategoryPalette(
        image = pick(ColorPart.IMAGES, d.image),
        video = pick(ColorPart.VIDEOS, d.video),
        audio = pick(ColorPart.AUDIO, d.audio),
        document = pick(ColorPart.DOCUMENTS, d.document),
        downloads = pick(ColorPart.DOWNLOADS, d.downloads),
        apk = pick(ColorPart.INSTALLERS, d.apk),
        archive = pick(ColorPart.ARCHIVES, d.archive),
        directory = pick(ColorPart.FOLDERS, d.directory),
        other = d.other,
    )
}

/** White or black, whichever reads better on [color]. */
private fun readableOn(color: Color): Color {
    val l = color.luminance()
    val onWhite = 1.05f / (l + 0.05f)
    val onBlack = (l + 0.05f) / 0.05f
    return if (onWhite >= onBlack) Color.White else Color.Black
}

private val ThemeFade = tween<Color>(durationMillis = 450)

@Composable
private fun Color.faded(label: String): Color =
    animateColorAsState(this, ThemeFade, label = label).value

@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme = target.copy(
    primary = target.primary.faded("primary"),
    onPrimary = target.onPrimary.faded("onPrimary"),
    primaryContainer = target.primaryContainer.faded("primaryContainer"),
    onPrimaryContainer = target.onPrimaryContainer.faded("onPrimaryContainer"),
    secondary = target.secondary.faded("secondary"),
    onSecondary = target.onSecondary.faded("onSecondary"),
    secondaryContainer = target.secondaryContainer.faded("secondaryContainer"),
    onSecondaryContainer = target.onSecondaryContainer.faded("onSecondaryContainer"),
    tertiary = target.tertiary.faded("tertiary"),
    onTertiary = target.onTertiary.faded("onTertiary"),
    background = target.background.faded("background"),
    onBackground = target.onBackground.faded("onBackground"),
    surface = target.surface.faded("surface"),
    onSurface = target.onSurface.faded("onSurface"),
    surfaceVariant = target.surfaceVariant.faded("surfaceVariant"),
    onSurfaceVariant = target.onSurfaceVariant.faded("onSurfaceVariant"),
    surfaceTint = target.surfaceTint.faded("surfaceTint"),
    surfaceBright = target.surfaceBright.faded("surfaceBright"),
    surfaceContainerLowest = target.surfaceContainerLowest.faded("surfaceContainerLowest"),
    surfaceContainerLow = target.surfaceContainerLow.faded("surfaceContainerLow"),
    surfaceContainer = target.surfaceContainer.faded("surfaceContainer"),
    surfaceContainerHigh = target.surfaceContainerHigh.faded("surfaceContainerHigh"),
    surfaceContainerHighest = target.surfaceContainerHighest.faded("surfaceContainerHighest"),
    inverseSurface = target.inverseSurface.faded("inverseSurface"),
    inverseOnSurface = target.inverseOnSurface.faded("inverseOnSurface"),
    outline = target.outline.faded("outline"),
    outlineVariant = target.outlineVariant.faded("outlineVariant"),
)

@Composable
private fun animateCategoryPalette(target: CategoryPalette) = CategoryPalette(
    image = target.image.faded("image"),
    video = target.video.faded("video"),
    audio = target.audio.faded("audio"),
    document = target.document.faded("document"),
    downloads = target.downloads.faded("downloads"),
    apk = target.apk.faded("apk"),
    archive = target.archive.faded("archive"),
    directory = target.directory.faded("directory"),
    other = target.other.faded("other"),
)

/**
 * Keeps the window and the status and navigation bar icons in step with the
 * page. The window shows through during screen transitions, so a Dim or
 * hand-set background would otherwise flash the XML theme's colour; and the
 * bar icons followed the device's night mode, so a light app on a dark
 * device drew white icons on a white page.
 */
@Composable
private fun SystemBars(background: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
        val lightPage = background.luminance() > 0.5f
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightPage
            isAppearanceLightNavigationBars = lightPage
        }
    }
}
