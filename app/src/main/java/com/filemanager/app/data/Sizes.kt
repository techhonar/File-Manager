package com.filemanager.app.data

import java.math.BigDecimal
import java.math.RoundingMode

private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

/**
 * "512 B", "9.4 MB", "512 MB": one decimal below 10, none above.
 *
 * Here rather than in the Rust core, where it was. Every row of every list
 * shows a size, and a call across to Rust costs far more than the formatting
 * does - tens of microseconds each, through JNA, against well under one here.
 */
fun formatSize(bytes: ULong): String {
    if (bytes < 1024uL) return "$bytes B"

    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024.0 && unit < UNITS.size - 1) {
        size /= 1024.0
        unit++
    }
    // Rounded as Rust rounded it, so that no size shown changes: the exact
    // value, half to even - 1.25 KB is "1.2 KB" - where String.format would
    // round half up.
    val decimals = if (size < 10.0) 1 else 0
    return BigDecimal(size).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString() + " " + UNITS[unit]
}
