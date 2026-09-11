package com.filemanager.app.ui.theme

import androidx.compose.ui.graphics.Color

// A calm blue-grey palette. The category accents below are what actually give
// the home screen its character -- each file type gets a consistent colour
// that then repeats in list icons, the storage bar and search filter chips.

val Blue40 = Color(0xFF1565C0)
val Blue80 = Color(0xFF90CAF9)
val BlueGrey40 = Color(0xFF455A64)
val BlueGrey80 = Color(0xFFB0BEC5)
val Teal40 = Color(0xFF00796B)
val Teal80 = Color(0xFF80CBC4)

val SurfaceLight = Color(0xFFFAFAFA)
val SurfaceDark = Color(0xFF121212)

/** One accent per file category, used everywhere that category appears. */
object CategoryColors {
    val Image = Color(0xFF43A047)
    val Video = Color(0xFFE53935)
    val Audio = Color(0xFFFB8C00)
    val Document = Color(0xFF1E88E5)
    val Archive = Color(0xFF8E24AA)
    val Apk = Color(0xFF00ACC1)
    val Directory = Color(0xFF546E7A)
    val Other = Color(0xFF757575)
}
