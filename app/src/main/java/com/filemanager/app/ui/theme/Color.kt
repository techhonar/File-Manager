package com.filemanager.app.ui.theme

import androidx.compose.ui.graphics.Color

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

/**
 * One accent per category, used everywhere that category appears: the home
 * tiles, list icons, the storage bar and the search filter chips.
 *
 * These are drawn as coloured *outline* glyphs on a neutral card, not as
 * white glyphs on a filled circle - which is what the category grid actually
 * looks like.
 */
object CategoryColors {
    val Image = Color(0xFFF25C7F)
    val Video = Color(0xFFA855F7)
    val Audio = Color(0xFF6D7FF0)
    val Document = Color(0xFFF0A63C)
    val Downloads = Color(0xFF2ECFB4)
    val Apk = Color(0xFFA8E05F)
    val Archive = Color(0xFF7C9BF5)
    val Directory = Color(0xFF9C9CA1)
    val Other = Color(0xFF9C9CA1)
}
