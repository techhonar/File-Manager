package com.filemanager.app.data

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A mounted volume the user can browse: internal storage, an SD card or a USB drive. */
data class StorageVolume(
    val name: String,
    val path: String,
    val isRemovable: Boolean,
    val isPrimary: Boolean,
    /**
     * A USB drive rather than a card. It keeps no trash, as on Samsung's
     * phones: what is deleted from it is gone, and the app says so first.
     */
    val isUsb: Boolean = false,
)

/** The volume [path] is on: the one whose folder holds it most closely. Null for a path on none. */
fun List<StorageVolume>.volumeOf(path: String): StorageVolume? =
    filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }

/** What a delete did: [trashed] went to the trash, [erased] were deleted for good from a USB drive. */
data class Deleted(val trashed: Int, val erased: Int) {
    /** "3 moved to trash", "2 deleted", or both. */
    val message: String
        get() = listOfNotNull(
            "$erased deleted".takeIf { erased > 0 },
            "$trashed moved to trash".takeIf { trashed > 0 },
        ).joinToString(", ").ifEmpty { "Nothing deleted" }
}

/** Paths about to be deleted, of which [onDrive] are on the USB drive [drive] and will be gone for good. */
data class PermanentDelete(val onDrive: Int, val total: Int, val drive: String, val onlyName: String?)

/**
 * What deleting [paths] can't undo: how many are on a USB drive, and which.
 * Null when none are, and everything goes to the trash as usual.
 */
fun List<StorageVolume>.permanentDelete(paths: Collection<String>): PermanentDelete? {
    val onDrive = paths.mapNotNull { path -> volumeOf(path)?.takeIf { it.isUsb }?.let { path to it } }
    if (onDrive.isEmpty()) return null
    return PermanentDelete(
        onDrive = onDrive.size,
        total = paths.size,
        drive = onDrive.first().second.name,
        onlyName = onDrive.singleOrNull()?.first?.substringAfterLast('/'),
    )
}

/**
 * Whether a removable volume is a USB drive. Android has no public answer,
 * so this goes by where it is: a USB drive is mounted for apps at
 * /mnt/media_rw/<id>, out of sight of the rest of storage, where a card is
 * at /storage/<id> - unless a phone's maker shows it there too, when its
 * name still says "USB".
 */
internal fun isUsbDrive(path: String, name: String?, removable: Boolean): Boolean =
    removable && (path.startsWith("/mnt/media_rw/") || name?.contains("USB", ignoreCase = true) == true)

/**
 * Discovers the device's storage roots.
 *
 * Kotlin's job rather than Rust's: only the Android framework knows which
 * mount points are user-visible volumes. Rust just gets handed the paths.
 */
object StorageVolumes {

    /**
     * Whether the device has a card slot at all, mounted or not.
     *
     * StorageManager lists a removable volume even when nothing is in it - the
     * state is unmounted rather than the volume being absent - so a slot with
     * no card still appears here, while a phone without a slot reports none.
     * That is the difference between "no card inserted" and "this phone cannot
     * take one", and showing an SD row on a device that has no slot is just
     * clutter the user can do nothing about.
     */
    fun hasRemovableSlot(context: Context): Boolean {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        // Not a USB drive, which is removable too: one plugged in as the app
        // started made a phone with no slot report an empty one once it was out.
        return manager.storageVolumes.any { volume ->
            volume.isRemovable &&
                !isUsbDrive(volume.directory?.absolutePath.orEmpty(), volume.getDescription(context), removable = true)
        }
    }

    fun list(context: Context): List<StorageVolume> {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val volumes = mutableListOf<StorageVolume>()

        for (volume in manager.storageVolumes) {
            // A volume that is not mounted has no usable directory.
            val directory = volume.directory ?: continue
            val name = volume.getDescription(context) ?: directory.name
            volumes += StorageVolume(
                name = name,
                path = directory.absolutePath,
                isRemovable = volume.isRemovable,
                isPrimary = volume.isPrimary,
                isUsb = isUsbDrive(directory.absolutePath, name, volume.isRemovable),
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

    /**
     * Whether the storage [path] lives on is mounted right now.
     *
     * Asked at the moment it matters rather than read from a list, which may
     * not yet know that a card has come out. A path on no known volume
     * reports false.
     */
    fun isMounted(path: String): Boolean = when (Environment.getExternalStorageState(File(path))) {
        Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY -> true
        else -> false
    }

    /** The path everything defaults to: /storage/emulated/0. */
    fun primaryPath(): String = Environment.getExternalStorageDirectory().absolutePath

    /**
     * The public Downloads folder, which the Downloads category lists.
     *
     * Whether or not it exists. It used to fall back to the storage root when
     * missing, which suited a tile that opened a folder - but as a category
     * that would list every file on the phone as downloaded. Missing, the
     * category is empty, which is the truth.
     */
    fun downloadsPath(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

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

/** "Internal storage/Download" for /storage/emulated/0/Download. */
fun shownPath(path: String, volumes: List<StorageVolume>): String {
    val volume = volumes.volumeOf(path) ?: return path
    val rest = path.removePrefix(volume.path).trim('/')
    return if (rest.isEmpty()) volume.name else "${volume.name}/$rest"
}

/**
 * The volumes as they are now. A USB drive is usually plugged in while the
 * app is open, and a list taken at startup left it out: Home had no row for
 * it, and Copy and Move had no chip for it and opened on internal storage
 * instead. Android says when a volume comes or goes; it is asked again on
 * every return to the app too, in case a change was missed.
 */
class LiveVolumes(private val context: Context) {
    private val _volumes = MutableStateFlow(StorageVolumes.list(context))
    val volumes: StateFlow<List<StorageVolume>> = _volumes.asStateFlow()

    /** The volumes mounted right now. */
    val current: List<StorageVolume> get() = _volumes.value

    fun refresh() {
        _volumes.value = StorageVolumes.list(context)
    }

    /** Follow volumes coming and going for as long as the process lives. */
    fun watch() {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        manager.registerStorageVolumeCallback(
            context.mainExecutor,
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: android.os.storage.StorageVolume) = refresh()
            },
        )
    }
}
