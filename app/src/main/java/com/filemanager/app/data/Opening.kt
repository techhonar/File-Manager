package com.filemanager.app.data

import com.filemanager.app.data.install.isBundle
import com.filemanager.app.data.viewer.viewerKind

/** What tapping a file does - decided by its name, the same on every screen. */
enum class Opening {
    /** A split-APK bundle, which the app installs: nothing else on the phone takes one. */
    INSTALL,

    /** An archive: what is inside is shown first, and where to extract it asked. */
    EXTRACT,

    /** Text, PDF and Word documents, in the app's own viewer. */
    VIEW,

    /** Anything else, handed to whichever app on the phone opens it. */
    HAND_ON,
}

/**
 * How the file named [name] opens.
 *
 * An archive used to be handed on like anything else, from every screen
 * but a folder and search - which ask about extracting it themselves - so
 * a zip tapped in recent files, favourites or storage did nothing at all on
 * a phone with no other app that takes one.
 */
fun openingFor(name: String): Opening = when {
    isBundle(name) -> Opening.INSTALL
    isExtractable(name) -> Opening.EXTRACT
    viewerKind(name) != null -> Opening.VIEW
    else -> Opening.HAND_ON
}
