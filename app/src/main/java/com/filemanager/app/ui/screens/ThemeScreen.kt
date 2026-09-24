package com.filemanager.app.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.AppSettings
import com.filemanager.app.data.ColorPart
import com.filemanager.app.data.DarkStyle
import com.filemanager.app.data.ThemeAccent
import com.filemanager.app.data.ThemeChoice
import com.filemanager.app.data.ThemeMode
import com.filemanager.app.ui.components.ColorPickerDialog
import com.filemanager.app.ui.components.OneUiGroup
import com.filemanager.app.ui.components.OneUiRow
import com.filemanager.app.ui.components.OneUiRowDivider
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.OneUiSectionHeader
import com.filemanager.app.ui.theme.LocalCategoryPalette
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.ui.theme.accentColor
import com.filemanager.app.ui.theme.colorIn
import com.filemanager.app.ui.theme.hexOf
import com.filemanager.app.ui.theme.themeDefaults

/**
 * Everything about how the app looks: light or dark, which dark, the accent
 * colour, and any part's colour set by hand.
 *
 * Changes apply as they are made - the theme fades across - so there is
 * nothing to save.
 */
@Composable
fun ThemeScreen(
    theme: ThemeChoice,
    settings: AppSettings,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var picking by remember { mutableStateOf<ColorPart?>(null) }

    OneUiScreen(
        title = "Theme",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            OneUiSectionHeader("Mode")
            OneUiGroup {
                ChoiceRow("Follow system", theme.mode == ThemeMode.SYSTEM) {
                    settings.setThemeMode(ThemeMode.SYSTEM)
                }
                OneUiRowDivider(inset = false)
                ChoiceRow("Light", theme.mode == ThemeMode.LIGHT) { settings.setThemeMode(ThemeMode.LIGHT) }
                OneUiRowDivider(inset = false)
                ChoiceRow("Dark", theme.mode == ThemeMode.DARK) { settings.setThemeMode(ThemeMode.DARK) }
            }

            OneUiSectionHeader("Dark style")
            OneUiGroup {
                ChoiceRow(
                    title = "Black",
                    subtitle = "True black - saves power on OLED screens",
                    selected = theme.darkStyle == DarkStyle.BLACK,
                ) { settings.setDarkStyle(DarkStyle.BLACK) }
                OneUiRowDivider(inset = false)
                ChoiceRow(
                    title = "Dim",
                    subtitle = "Dark grey - softer, and kinder to LCD screens",
                    selected = theme.darkStyle == DarkStyle.DIM,
                ) { settings.setDarkStyle(DarkStyle.DIM) }
            }

            OneUiSectionHeader("Colour")
            OneUiGroup {
                AccentGrid(
                    selected = theme.accent,
                    onSelect = settings::setAccent,
                    modifier = Modifier.padding(16.dp),
                )
            }

            OneUiSectionHeader("Custom colours")
            Text(
                text = "Set the colour of any part by hand. It overrides the theme, in light and dark alike.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = OneUi.ScreenPadding + 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            OneUiGroup {
                val scheme = MaterialTheme.colorScheme
                val categories = LocalCategoryPalette.current
                ColorPart.entries.forEachIndexed { index, part ->
                    if (index > 0) OneUiRowDivider()
                    val custom = theme.custom[part]
                    OneUiRow(
                        title = part.label(),
                        subtitle = custom?.let { hexOf(it) } ?: "From the theme",
                        onClick = { picking = part },
                        trailing = { Swatch(part.colorIn(scheme, categories)) },
                    )
                }
            }
            TextButton(
                onClick = settings::resetCustomColors,
                enabled = theme.custom.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OneUi.ScreenPadding, vertical = 8.dp),
            ) {
                Text("Reset all colours")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    picking?.let { part ->
        val (defaultScheme, defaultCategories) = themeDefaults(theme)
        val current = theme.custom[part]?.let { Color(it) }
            ?: part.colorIn(defaultScheme, defaultCategories)
        ColorPickerDialog(
            title = part.label(),
            initial = current,
            onPick = { argb ->
                settings.setCustomColor(part, argb)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    selected: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    OneUiRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = { RadioButton(selected = selected, onClick = onClick) },
    )
}

/**
 * The accents as swatches, each drawn in the shade it will actually take in
 * the current mode. Wallpaper only where Android can supply one.
 */
@Composable
private fun AccentGrid(
    selected: ThemeAccent,
    onSelect: (ThemeAccent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val offered = ThemeAccent.entries.filter {
        it != ThemeAccent.WALLPAPER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        offered.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { accent ->
                    val color = accentColor(accent, dark)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { onSelect(accent) }
                            .padding(4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(color, CircleShape)
                                .then(
                                    if (accent == selected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (accent == selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = accent.label(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        Modifier
            .size(30.dp)
            .background(color, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

private fun ThemeAccent.label(): String = when (this) {
    ThemeAccent.BLUE -> "Blue"
    ThemeAccent.TEAL -> "Teal"
    ThemeAccent.GREEN -> "Green"
    ThemeAccent.PURPLE -> "Purple"
    ThemeAccent.ORANGE -> "Orange"
    ThemeAccent.PINK -> "Pink"
    ThemeAccent.WALLPAPER -> "Wallpaper"
}

private fun ColorPart.label(): String = when (this) {
    ColorPart.ACCENT -> "Accent"
    ColorPart.BACKGROUND -> "Background"
    ColorPart.CARDS -> "Cards and menus"
    ColorPart.TEXT -> "Text"
    ColorPart.SECONDARY_TEXT -> "Secondary text"
    ColorPart.FOLDERS -> "Folders"
    ColorPart.IMAGES -> "Images"
    ColorPart.VIDEOS -> "Videos"
    ColorPart.AUDIO -> "Audio"
    ColorPart.DOCUMENTS -> "Documents"
    ColorPart.DOWNLOADS -> "Downloads"
    ColorPart.INSTALLERS -> "Installation files"
    ColorPart.ARCHIVES -> "Archives"
}
