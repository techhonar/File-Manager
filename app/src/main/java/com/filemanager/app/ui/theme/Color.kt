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
val OneUiDarkBackground = Color(0xFF000000)
val OneUiDarkSurface = Color(0xFF1B1B1E)
val OneUiDarkSurfaceVariant = Color(0xFF2A2B2F)
val OneUiDarkOnSurface = Color(0xFFF2F2F4)
val OneUiDarkOnSurfaceVariant = Color(0xFF9EA1A9)
val OneUiDarkOutline = Color(0xFF303136)
val OneUiDarkPrimary = Color(0xFF6BB0FF)
val OneUiDarkPrimaryContainer = Color(0xFF123B66)
val OneUiDarkError = Color(0xFFFF8A80)

/**
 * One accent per file category, used everywhere that category appears: the
 * home tiles, list icons, the storage bar and the search filter chips. Kept
 * saturated because One UI shows them as solid filled circles rather than
 * tinted outlines.
 */
object CategoryColors {
    val Image = Color(0xFF21A366)
    val Video = Color(0xFFE8453C)
    val Audio = Color(0xFFF29200)
    val Document = Color(0xFF2E7CF6)
    val Archive = Color(0xFF9B51E0)
    val Apk = Color(0xFF00A9B7)
    val Directory = Color(0xFF5B6B7C)
    val Other = Color(0xFF8A8F98)
}
