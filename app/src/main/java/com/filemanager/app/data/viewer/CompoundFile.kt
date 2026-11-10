package com.filemanager.app.data.viewer

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/*
 * The container Office kept its files in before 2007 - a Word .doc, an
 * Excel .xls, a PowerPoint .ppt: a small file system inside the file, of
 * fixed-size sectors chained one to the next by a table, with a directory
 * of named streams. Only reading, and only whole streams.
 *
 * Every chain is followed with a count, and every sector number checked,
 * so a damaged or hostile file fails to open rather than looping or
 * reading past its end.
 */
internal class CompoundFile(file: File) : Closeable {

    private class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    private val input = RandomAccessFile(file, "r")
    private val length = input.length()
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private var miniStream: ByteArray? = null

    init {
        try {
            if (length < HEADER_SIZE) throw IOException("Not a compound file")
            val header = ByteArray(HEADER_SIZE)
            input.seek(0)
            input.readFully(header)
            if (!header.startsWith(SIGNATURE)) throw IOException("Not a compound file")
            val major = u16(header, 0x1A)
            val sectorShift = u16(header, 0x1E)
            if (sectorShift != 9 && sectorShift != 12) throw IOException("Damaged compound file")
            sectorSize = 1 shl sectorShift
            miniSectorSize = 1 shl u16(header, 0x20).coerceIn(6, 12)
            val fatSectors = s32(header, 0x2C)
            val firstDirectory = s32(header, 0x30)
            miniCutoff = s32(header, 0x38).toLong() and 0xFFFFFFFFL
            val firstMiniFat = s32(header, 0x3C)
            val firstDifat = s32(header, 0x44)

            // Where the table's own sectors are: 109 in the header, the rest
            // in a chain of sectors that list them.
            val maxSectors = (length / sectorSize).toInt() + 1
            val fatAt = ArrayList<Int>()
            for (i in 0 until 109) s32(header, 0x4C + i * 4).takeIf { it >= 0 }?.let { fatAt += it }
            var difat = firstDifat
            var hops = 0
            while (difat >= 0 && fatAt.size < fatSectors) {
                if (++hops > maxSectors) throw IOException("Damaged compound file")
                val sector = readSector(difat)
                for (i in 0 until sectorSize / 4 - 1) s32(sector, i * 4).takeIf { it >= 0 }?.let { fatAt += it }
                difat = s32(sector, sectorSize - 4)
            }
            val perSector = sectorSize / 4
            val table = IntArray(minOf(fatAt.size, maxSectors) * perSector)
            for ((n, at) in fatAt.take(table.size / perSector).withIndex()) {
                val sector = readSector(at)
                for (i in 0 until perSector) table[n * perSector + i] = s32(sector, i * 4)
            }
            fat = table

            val directory = readChain(firstDirectory, fat, sectorSize, ::readSector, Long.MAX_VALUE)
            entries = (0 until directory.size / DIRECTORY_ENTRY).map { i ->
                val at = i * DIRECTORY_ENTRY
                val nameBytes = u16(directory, at + 0x40).coerceIn(0, 64)
                val name = String(directory, at, (nameBytes - 2).coerceAtLeast(0), Charsets.UTF_16LE)
                val size = if (major == 3) {
                    s32(directory, at + 0x78).toLong() and 0xFFFFFFFFL
                } else {
                    (s32(directory, at + 0x78).toLong() and 0xFFFFFFFFL) or (s32(directory, at + 0x7C).toLong() shl 32)
                }
                Entry(name, directory[at + 0x42].toInt() and 0xFF, s32(directory, at + 0x74), size)
            }
            miniFat = if (firstMiniFat >= 0) {
                val bytes = readChain(firstMiniFat, fat, sectorSize, ::readSector, Long.MAX_VALUE)
                IntArray(bytes.size / 4) { s32(bytes, it * 4) }
            } else {
                IntArray(0)
            }
        } catch (e: Throwable) {
            input.close()
            throw e
        }
    }

    /** The stream called [name], whole, or null if there is none. */
    fun stream(name: String): ByteArray? {
        val entry = entries.firstOrNull { it.type == STREAM && it.name.equals(name, ignoreCase = true) } ?: return null
        if (entry.size > MAX_STREAM) throw IOException("Too large")
        return if (entry.size < miniCutoff) {
            val mini = miniStream ?: rootStream().also { miniStream = it }
            readChain(entry.start, miniFat, miniSectorSize, { sector -> miniSector(mini, sector) }, entry.size)
        } else {
            readChain(entry.start, fat, sectorSize, ::readSector, entry.size)
        }
    }

    override fun close() = input.close()

    /** Where the small streams are kept, together: the root entry's own stream. */
    private fun rootStream(): ByteArray {
        val root = entries.firstOrNull { it.type == ROOT } ?: return ByteArray(0)
        if (root.size > MAX_STREAM) throw IOException("Too large")
        return readChain(root.start, fat, sectorSize, ::readSector, root.size)
    }

    private fun miniSector(mini: ByteArray, sector: Int): ByteArray {
        val from = sector.toLong() * miniSectorSize
        if (from < 0 || from >= mini.size) throw IOException("Damaged compound file")
        return mini.copyOfRange(from.toInt(), minOf(mini.size, from.toInt() + miniSectorSize))
    }

    /** One sector; the end of a file cut short reads as zeros. */
    private fun readSector(sector: Int): ByteArray {
        val at = (sector.toLong() + 1) * sectorSize
        if (sector < 0 || at >= length) throw IOException("Damaged compound file")
        val bytes = ByteArray(sectorSize)
        input.seek(at)
        var read = 0
        while (read < sectorSize) {
            val n = input.read(bytes, read, sectorSize - read)
            if (n <= 0) break
            read += n
        }
        return bytes
    }

    private companion object {
        const val HEADER_SIZE = 512
        const val DIRECTORY_ENTRY = 128
        const val STREAM = 2
        const val ROOT = 5

        /** Far more than any document's text; past it, a file is taken as damaged. */
        const val MAX_STREAM = 256L * 1024 * 1024

        val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        /**
         * The sectors chained from [start] through [table], joined, up to
         * [size] bytes. Fails on a chain that loops or leaves the table.
         */
        fun readChain(start: Int, table: IntArray, sectorSize: Int, read: (Int) -> ByteArray, size: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var sector = start
            var hops = 0
            while (sector >= 0 && out.size() < size) {
                if (sector >= table.size || ++hops > table.size) throw IOException("Damaged compound file")
                val bytes = read(sector)
                out.write(bytes, 0, minOf(bytes.size.toLong(), size - out.size()).toInt())
                sector = table[sector]
            }
            return out.toByteArray()
        }
    }
}

internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/** A little-endian unsigned 16-bit value at [at]; 0 past the end. */
internal fun u16(bytes: ByteArray, at: Int): Int =
    if (at < 0 || at + 2 > bytes.size) 0 else (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

/** A little-endian signed 32-bit value at [at]; 0 past the end. */
internal fun s32(bytes: ByteArray, at: Int): Int =
    if (at < 0 || at + 4 > bytes.size) 0 else u16(bytes, at) or (u16(bytes, at + 2) shl 16)
