package com.filemanager.app.data.editor

/**
 * Undo and redo for text being edited.
 *
 * Each step keeps only what changed - where, what went, what came - rather
 * than a copy of the whole text, which for a large file would be megabytes a
 * keystroke. Typing and deleting in one place within a moment of each other
 * make a single step, so undo takes back a word or a burst rather than a
 * letter; a new line, a pause or moving elsewhere starts the next.
 */
class EditHistory(
    private val maxSteps: Int = 500,
    /** Old steps are dropped past this much changed text in all. */
    private val maxChars: Int = 4_000_000,
) {
    private class Step(
        var at: Int,
        var removed: String,
        var inserted: String,
        val cursorBefore: Int,
        var cursorAfter: Int,
        var time: Long,
    ) {
        val size get() = removed.length + inserted.length
    }

    private val undoable = ArrayDeque<Step>()
    private val redoable = ArrayDeque<Step>()
    private var chars = 0

    val canUndo: Boolean get() = undoable.isNotEmpty()
    val canRedo: Boolean get() = redoable.isNotEmpty()

    /** [old] became [new]; the cursor was at [cursorBefore] and is now at [cursorAfter]. */
    fun record(old: String, new: String, cursorBefore: Int, cursorAfter: Int, now: Long) {
        if (old == new) return
        val shorter = minOf(old.length, new.length)
        var prefix = 0
        while (prefix < shorter && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < shorter - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        val removed = old.substring(prefix, old.length - suffix)
        val inserted = new.substring(prefix, new.length - suffix)

        redoable.clear()
        val last = undoable.lastOrNull()
        if (last != null && now - last.time < MERGE_MS && merge(last, prefix, removed, inserted)) {
            chars += removed.length + inserted.length
            last.cursorAfter = cursorAfter
            last.time = now
        } else {
            undoable.addLast(Step(prefix, removed, inserted, cursorBefore, cursorAfter, now))
            chars += removed.length + inserted.length
        }
        while (undoable.size > maxSteps || (chars > maxChars && undoable.size > 1)) {
            chars -= undoable.removeFirst().size
        }
    }

    /** Takes back the last step from [current]: the text before it, and where the cursor was. */
    fun undo(current: String): Pair<String, Int>? {
        val step = undoable.removeLastOrNull() ?: return null
        val end = step.at + step.inserted.length
        if (end > current.length || !current.regionMatches(step.at, step.inserted, 0, step.inserted.length)) {
            // The text changed without being recorded; the history no longer fits it.
            clear()
            return null
        }
        redoable.addLast(step)
        return current.substring(0, step.at) + step.removed + current.substring(end) to step.cursorBefore
    }

    fun redo(current: String): Pair<String, Int>? {
        val step = redoable.removeLastOrNull() ?: return null
        val end = step.at + step.removed.length
        if (end > current.length || !current.regionMatches(step.at, step.removed, 0, step.removed.length)) {
            clear()
            return null
        }
        undoable.addLast(step)
        return current.substring(0, step.at) + step.inserted + current.substring(end) to step.cursorAfter
    }

    fun clear() {
        undoable.clear()
        redoable.clear()
        chars = 0
    }

    /** Folds a change into [last] if it continues it, and says whether it did. */
    private fun merge(last: Step, at: Int, removed: String, inserted: String): Boolean {
        val typing = removed.isEmpty() && last.removed.isEmpty() && inserted.isNotEmpty()
        if (typing && at == last.at + last.inserted.length && '\n' !in inserted && '\n' !in last.inserted) {
            last.inserted += inserted
            return true
        }
        val deleting = inserted.isEmpty() && last.inserted.isEmpty() && removed.isNotEmpty()
        if (deleting && '\n' !in removed) {
            // Backspace: each removal ends where the last began.
            if (at + removed.length == last.at) {
                last.at = at
                last.removed = removed + last.removed
                return true
            }
            // Delete key: each removal starts at the same place.
            if (at == last.at) {
                last.removed += removed
                return true
            }
        }
        return false
    }

    private companion object {
        const val MERGE_MS = 1_000L
    }
}
