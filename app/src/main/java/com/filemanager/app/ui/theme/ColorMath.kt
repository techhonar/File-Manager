package com.filemanager.app.ui.theme

/**
 * The colour picker's arithmetic, on plain ARGB ints so it needs neither
 * Android nor Compose - and can be tested without either.
 */

/** "#1A2B3C" for an opaque colour; the alpha is not shown or kept. */
fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/**
 * An opaque colour from "#RRGGBB" or "RRGGBB", or the short "#RGB". Null for
 * anything else, so half-typed text leaves the colour where it was.
 */
fun parseHexColor(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    val full = when (digits.length) {
        6 -> digits
        3 -> digits.map { "$it$it" }.joinToString("")
        else -> return null
    }
    val rgb = full.toIntOrNull(16) ?: return null
    return (0xFF shl 24) or rgb
}

/** Hue 0..360, saturation and value 0..1. */
fun argbToHsv(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta).mod(6f))
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    val saturation = if (max == 0f) 0f else delta / max
    return floatArrayOf(hue, saturation, max)
}

/** An opaque colour from hue 0..360 and saturation, value 0..1. */
fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
    val h = (hue.mod(360f)) / 60f
    val c = value * saturation
    val x = c * (1 - kotlin.math.abs(h.mod(2f) - 1))
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = value - c
    fun channel(v: Float) = ((v + m) * 255f).let { kotlin.math.round(it).toInt().coerceIn(0, 255) }
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}
