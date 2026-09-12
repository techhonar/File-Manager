package com.filemanager.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Layout constants for the One UI look.
 *
 * Two things carry most of the resemblance: corners far rounder than Material
 * defaults, and noticeably more breathing room. Collected here so the screens
 * stay consistent and a single edit retunes all of them.
 */
object OneUi {
    /** Side margin for all screen content. */
    val ScreenPadding = 20.dp

    /** Corner radius of the rounded containers that group related rows. */
    val GroupCorner = 26.dp
    val GroupShape = RoundedCornerShape(GroupCorner)

    /** Cards that sit on their own, like the storage summary. */
    val CardCorner = 22.dp
    val CardShape = RoundedCornerShape(CardCorner)

    /** Search field and filter chips are full pills. */
    val PillShape = RoundedCornerShape(percent = 50)

    /** Thumbnails and the square-ish icon tiles. */
    val ThumbCorner = 12.dp
    val ThumbShape = RoundedCornerShape(ThumbCorner)

    /** Minimum height of a list row. One UI rows are tall and easy to hit. */
    val RowHeight = 68.dp
    val RowIcon = 42.dp

    /** Diameter of the filled circle behind a category glyph. */
    val CategoryCircle = 52.dp

    /** Vertical gap between sections. */
    val SectionGap = 24.dp

    /** Height of the expanded large title before it collapses. */
    val LargeTitleHeight = 148.dp
}
