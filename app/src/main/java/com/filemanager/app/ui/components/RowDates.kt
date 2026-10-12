package com.filemanager.app.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** "12 Sep 2026": when a file was last changed, as a row shows it. */
internal val RowDate = DatePattern("d MMM yyyy")

/** "12 Sep 2026, 16:05": the detailed list's fuller time. */
internal val RowTime = DatePattern("d MMM yyyy, HH:mm")

/**
 * A date pattern with its formatter kept. Making a SimpleDateFormat costs
 * several times what formatting with one does, and the lists were making one
 * for every row they drew. Kept per thread, as a SimpleDateFormat cannot be
 * shared between threads, and made again once the locale or the time zone
 * has changed.
 */
internal class DatePattern(private val pattern: String) {

    private class Made(val locale: Locale, val zone: String, val format: SimpleDateFormat)

    private val made = ThreadLocal<Made>()

    fun format(ms: Long): String {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        val current = made.get()?.takeIf { it.locale == locale && it.zone == zone.id }
            ?: Made(locale, zone.id, SimpleDateFormat(pattern, locale).apply { timeZone = zone })
                .also(made::set)
        return current.format.format(Date(ms))
    }
}
