package com.filemanager.app.data

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** A mounted volume the user can browse: internal storage or an SD card. */
data class StorageVolume(
    val name: String,
    val path: String,
    val isRemovable: Boolean,
    val isPrimary: Boolean,
)

/**
 * Discovers the device's storage roots.
 *
 * Kotlin's job rather than Rust's: only the Android framework knows which
 * mount points are user-visible volumes. Rust just gets handed the paths.
 */
object StorageVolumes {

    fun list(context: Context): List<StorageVolume> {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val volumes = mutableListOf<StorageVolume>()

        for (volume in manager.storageVolumes) {
            // A volume that is not mounted has no usable directory.
            val directory = volume.directory ?: continue
            volumes += StorageVolume(
                name = volume.getDescription(context) ?: directory.name,
                path = directory.absolutePath,
                isRemovable = volume.isRemovable,
                isPrimary = volume.isPrimary,
            )
        }

        // Guarantee at least internal storage, in case the framework returns
        // nothing useful on an unusual build.
        if (volumes.isEmpty()) {
            val fallback = Environment.getExternalStorageDirectory()
            volumes += StorageVolume("Internal storage", fallback.absolutePath, false, true)
        }
        return volumes
    }

    /** The path everything defaults to: /storage/emulated/0. */
    fun primaryPath(): String = Environment.getExternalStorageDirectory().absolutePath

    /**
     * Well-known folders shown as shortcuts, skipping any the device lacks.
     */
    fun standardFolders(): List<Pair<String, File>> = listOf(
        "Downloads" to Environment.DIRECTORY_DOWNLOADS,
        "Documents" to Environment.DIRECTORY_DOCUMENTS,
        "Pictures" to Environment.DIRECTORY_PICTURES,
        "DCIM" to Environment.DIRECTORY_DCIM,
        "Movies" to Environment.DIRECTORY_MOVIES,
        "Music" to Environment.DIRECTORY_MUSIC,
    ).map { (label, type) ->
        label to Environment.getExternalStoragePublicDirectory(type)
    }.filter { (_, dir) -> dir.exists() }
}
