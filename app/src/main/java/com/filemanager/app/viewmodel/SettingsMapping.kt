package com.filemanager.app.viewmodel

import com.filemanager.app.data.SortKeySetting
import com.filemanager.app.data.ViewModeSetting
import uniffi.filemanager_core.SortKey

/**
 * Translations between the stored settings and the types the UI and the core
 * use.
 *
 * In their own file because more than one view model needs them - they were
 * file-private in the browser's, where the search screen could not reach them.
 *
 * Written out rather than mapped by ordinal on purpose: matching positions
 * would compile happily and start returning the wrong value the moment either
 * side gained a variant.
 */
internal fun ViewModeSetting.toViewMode(): ViewMode = when (this) {
    ViewModeSetting.LIST -> ViewMode.LIST
    ViewModeSetting.DETAILED -> ViewMode.DETAILED
    ViewModeSetting.GRID -> ViewMode.GRID
}

internal fun ViewMode.toSetting(): ViewModeSetting = when (this) {
    ViewMode.LIST -> ViewModeSetting.LIST
    ViewMode.DETAILED -> ViewModeSetting.DETAILED
    ViewMode.GRID -> ViewModeSetting.GRID
}

internal fun SortKeySetting.toSortKey(): SortKey = when (this) {
    SortKeySetting.NAME -> SortKey.NAME
    SortKeySetting.SIZE -> SortKey.SIZE
    SortKeySetting.MODIFIED -> SortKey.MODIFIED
    SortKeySetting.TYPE -> SortKey.TYPE
}

internal fun SortKey.toSetting(): SortKeySetting = when (this) {
    SortKey.NAME -> SortKeySetting.NAME
    SortKey.SIZE -> SortKeySetting.SIZE
    SortKey.MODIFIED -> SortKeySetting.MODIFIED
    SortKey.TYPE -> SortKeySetting.TYPE
}
