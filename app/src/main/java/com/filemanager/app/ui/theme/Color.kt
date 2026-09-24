package com.filemanager.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.filemanager.app.data.ThemeAccent

// One UI's palette is flatter and higher-contrast than stock Material: an
// almost-white page with pure white cards in light mode, and true black with
// lifted grey cards in dark mode (it targets AMOLED panels, where black costs
// no power). Accents are a single confident blue rather than a tonal spread.

// --- Light -----------------------------------------------------------------
val OneUiLightBackground = Color(0xFFF4F4F7)
val OneUiLightSurface = Color(0xFFFFFFFF)
/** Inputs, chips, the unfilled part of a progress track. */
val OneUiLightSurfaceVariant = Color(0xFFEDEEF2)
val OneUiLightOnSurface = Color(0xFF1B1B1E)
val OneUiLightOnSurfaceVariant = Color(0xFF6E7179)
val OneUiLightOutline = Color(0xFFE3E4E9)
val OneUiLightPrimary = Color(0xFF0F6FDE)
val OneUiLightPrimaryContainer = Color(0xFFDCEAFB)
val OneUiLightError = Color(0xFFD03A3A)

// --- Dark ------------------------------------------------------------------
// True black page with cards only slightly lifted off it, which is what the
// dark One UI home screen looks like on an AMOLED panel.
val OneUiDarkBackground = Color(0xFF000000)
val OneUiDarkSurface = Color(0xFF161618)
val OneUiDarkSurfaceVariant = Color(0xFF232326)
val OneUiDarkOnSurface = Color(0xFFFFFFFF)
val OneUiDarkOnSurfaceVariant = Color(0xFF9C9CA1)
val OneUiDarkOutline = Color(0xFF2B2B2E)
val OneUiDarkPrimary = Color(0xFF3A82F7)
val OneUiDarkPrimaryContainer = Color(0xFF14243D)
val OneUiDarkError = Color(0xFFFF8A80)

// --- Dim ------------------------------------------------------------------
// The softer dark style: charcoal rather than black, for LCD screens, where
// black saves nothing and a black page next to grey cards can look harsh.
val OneUiDimBackground = Color(0xFF1C1C1F)
val OneUiDimSurface = Color(0xFF2A2A2E)
val OneUiDimSurfaceVariant = Color(0xFF36363B)
val OneUiDimOnSurface = Color(0xFFF2F2F5)
val OneUiDimOnSurfaceVariant = Color(0xFFA1A1A8)
val OneUiDimOutline = Color(0xFF3A3A3F)

/** An accent in the shade used on a light page and the one used on a dark page. */
data class AccentShades(val light: Color, val dark: Color)

/**
 * The colour themes. Each light shade is at least 4.5:1 against white, so text
 * and buttons in it stay readable; each dark shade is lighter, for the same
 * reason against the dark cards. Wallpaper is not here: it is read from the
 * device when the theme is built.
 */
val AccentPalette: Map<ThemeAccent, AccentShades> = mapOf(
    ThemeAccent.BLUE to AccentShades(OneUiLightPrimary, OneUiDarkPrimary),
    ThemeAccent.TEAL to AccentShades(Color(0xFF00796B), Color(0xFF2BC4B4)),
    ThemeAccent.GREEN to AccentShades(Color(0xFF2E7D32), Color(0xFF5CC46A)),
    ThemeAccent.PURPLE to AccentShades(Color(0xFF7B3FE4), Color(0xFFA98BFA)),
    ThemeAccent.ORANGE to AccentShades(Color(0xFFB8520A), Color(0xFFFF9A4D)),
    ThemeAccent.PINK to AccentShades(Color(0xFFC2255C), Color(0xFFF7729E)),
)

/**
 * One accent per category, used everywhere that category appears: the home
 * tiles, list icons, the storage bar and the search filter chips.
 *
 * These are drawn as coloured *outline* glyphs on a neutral card, not as
 * white glyphs on a filled circle - which is what the category grid actually
 * looks like.
 */
@Immutable
data class CategoryPalette(
    val image: Color,
    val video: Color,
    val audio: Color,
    val document: Color,
    val downloads: Color,
    val apk: Color,
    val archive: Color,
    val directory: Color,
    val other: Color,
)

val DefaultCategoryPalette = CategoryPalette(
    image = Color(0xFFF25C7F),
    video = Color(0xFFA855F7),
    audio = Color(0xFF6D7FF0),
    document = Color(0xFFF0A63C),
    downloads = Color(0xFF2ECFB4),
    apk = Color(0xFFA8E05F),
    archive = Color(0xFF7C9BF5),
    directory = Color(0xFF9C9CA1),
    other = Color(0xFF9C9CA1),
)

/** The category colours in use, which the theme may have changed. */
val LocalCategoryPalette = compositionLocalOf { DefaultCategoryPalette }

/**
 * The category colours by name, as every screen reads them. They follow the
 * theme now - each can be set by hand - so they are read from the theme
 * rather than being fixed.
 */
object CategoryColors {
    val Image: Color @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current.image
    val Video: Color @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current.video
    val Audio: Color @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current.audio
    val Document: Color @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current.document
    val Downloads: Color @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current.downloads
    val Apk: Color @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current.apk
    val Archive: Color @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current.archive
    val Directory: Color @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current.directory
    val Other: Color @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current.other
}
