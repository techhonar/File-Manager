package com.filemanager.app.data.transfer

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Copying and moving files on the phone itself, for TransferCenter.
 *
 * An interface rather than FileRepository, which reaches the Rust core: the
 * transfer center's tests run on the JVM without it, and a fake answers here.
 */
fun interface LocalFiles {
    /**
     * Copy or [move] [sources] into the folder [into], each item whole or
     * not at all. [progress] is told how far it has got, [placed] where each
     * item landed - under its own name, or a numbered one where that was
     * taken. Returns once all are done; throws if it is stopped through
     * [stop] or fails, with what was placed before that left in place.
     */
    suspend fun place(
        sources: List<String>,
        into: String,
        move: Boolean,
        progress: (filesDone: Long, filesTotal: Long, bytesDone: Long, bytesTotal: Long) -> Unit,
        placed: (source: String, target: String) -> Unit,
        stop: StopSignal,
    )
}

/**
 * Asks a running copy to stop, from any thread: whoever runs it registers
 * what stopping means for them - for the Rust core, cancelling its token.
 */
class StopSignal {
    private val hooks = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var stopped = false
        private set

    fun stop() {
        stopped = true
        hooks.forEach { it() }
    }

    /** Run [hook] on stopping - at once, if that has already happened. */
    fun onStop(hook: () -> Unit) {
        hooks += hook
        if (stopped) hook()
    }
}
