package com.filemanager.app.data

import uniffi.filemanager_core.ArchiveEntry

/** One line of an archive's contents as shown before extracting it. */
data class ArchiveRow(
    /** Where it sits inside the archive: "photos/2024/beach.jpg". */
    val path: String,
    val name: String,
    /** 0 at the top of the archive. */
    val depth: Int,
    val isDir: Boolean,
    /** A file's size; a folder's is everything inside it. */
    val size: Long,
    /** For a folder, the files anywhere inside it. */
    val files: Int,
)

/**
 * What [entries] hold, as a tree laid out row by row: each folder followed by
 * what is in it, folders before files at every level, each by name.
 *
 * A folder that only appears in the paths of the files inside it - most zips
 * name no folders at all, only "photos/beach.jpg" - is added, so every file
 * is shown under the folder it will land in.
 */
fun archiveTree(entries: List<ArchiveEntry>): List<ArchiveRow> {
    val root = Node("", "", isDir = true)
    for (entry in entries) {
        val parts = entry.name.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) continue
        var node = root
        parts.forEachIndexed { index, part ->
            val last = index == parts.lastIndex
            val isDir = !last || entry.isDir
            node = node.children.getOrPut(part) {
                Node(part, if (node.path.isEmpty()) part else "${node.path}/$part", isDir)
            }
            if (last && !isDir) node.size = entry.size.toLong()
        }
    }

    val rows = mutableListOf<ArchiveRow>()
    fun visit(node: Node, depth: Int) {
        val ordered = node.children.values.sortedWith(
            compareByDescending<Node> { it.isDir }.thenBy { it.name.lowercase() },
        )
        for (child in ordered) {
            rows += ArchiveRow(child.path, child.name, depth, child.isDir, child.total(), child.fileCount())
            if (child.isDir) visit(child, depth + 1)
        }
    }
    visit(root, 0)
    return rows
}

/**
 * The rows of [tree] that show when the folders in [open] are opened: a row
 * shows when every folder above it is open.
 */
fun visibleRows(tree: List<ArchiveRow>, open: Set<String>): List<ArchiveRow> =
    tree.filter { row ->
        val parent = row.path.substringBeforeLast('/', "")
        parent.isEmpty() || ancestorsOf(parent).all { it in open }
    }

/**
 * The folders to open on first showing [tree]: the one at the top when that is
 * all there is, which is how most archives are made - a single folder with
 * everything inside - and would otherwise show as one row saying nothing.
 */
fun initiallyOpen(tree: List<ArchiveRow>): Set<String> {
    val top = tree.filter { it.depth == 0 }
    return if (top.size == 1 && top.single().isDir) setOf(top.single().path) else emptySet()
}

/** "12 files in 3 folders · 45 MB". [size] formats a byte count. */
fun archiveSummary(tree: List<ArchiveRow>, size: (Long) -> String): String {
    val files = tree.count { !it.isDir }
    val folders = tree.count { it.isDir }
    val bytes = tree.filter { !it.isDir }.sumOf { it.size }
    val what = buildString {
        append(if (files == 1) "1 file" else "$files files")
        if (folders > 0) append(if (folders == 1) " in 1 folder" else " in $folders folders")
    }
    return if (bytes > 0) "$what · ${size(bytes)}" else what
}

/** "a/b/c", "a/b", "a" for "a/b/c". */
private fun ancestorsOf(path: String): List<String> =
    generateSequence(path) { it.substringBeforeLast('/', "").ifEmpty { null } }.toList()

private class Node(val name: String, val path: String, val isDir: Boolean) {
    val children = LinkedHashMap<String, Node>()
    var size = 0L

    fun total(): Long = if (isDir) children.values.sumOf { it.total() } else size

    fun fileCount(): Int = if (isDir) children.values.sumOf { it.fileCount() } else 1
}
