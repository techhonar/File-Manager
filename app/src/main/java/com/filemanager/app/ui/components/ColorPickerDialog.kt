package com.filemanager.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.theme.AccentPalette
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.ui.theme.argbToHsv
import com.filemanager.app.ui.theme.hexOf
import com.filemanager.app.ui.theme.hsvToArgb
import com.filemanager.app.ui.theme.parseHexColor

/**
 * Pick any colour: drag across the square for how strong and how light, along
 * the bar for the hue, type a hex code, or tap a ready-made one.
 *
 * [onPick] gets the colour chosen, or null for Default - the part goes back to
 * whatever the theme gives it.
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Color,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember { argbToHsv(initial.toArgb()) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val argb = hsvToArgb(hue, saturation, value)
    // The field shows what is picked, but typing in it is left alone until it
    // reads as a colour - otherwise every keystroke would be rewritten.
    var hexText by remember { mutableStateOf(hexOf(argb)) }

    fun select(newArgb: Int) {
        val (h, s, v) = argbToHsv(newArgb).toList()
        // A grey has no hue of its own; keep the bar where it was.
        if (s > 0f) hue = h
        saturation = s
        value = v
        hexText = hexOf(newArgb)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OneUi.CardShape,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SaturationValueBox(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onChange = { s, v ->
                        saturation = s
                        value = v
                        hexText = hexOf(hsvToArgb(hue, s, v))
                    },
                )
                HueBar(
                    hue = hue,
                    onChange = {
                        hue = it
                        hexText = hexOf(hsvToArgb(it, saturation, value))
                    },
                )
                Row {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(argb))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text
                            parseHexColor(text)?.let { parsed ->
                                val (h, s, v) = argbToHsv(parsed).toList()
                                if (s > 0f) hue = h
                                saturation = s
                                value = v
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                }
                Presets(onPick = ::select)
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(argb) }) { Text("Done") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text("Default") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Saturation left to right, lightness top to bottom, for the current hue. */
@Composable
private fun SaturationValueBox(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    // The gesture handler outlives recompositions; it has to call the latest.
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(shape)
            .pointerInput(Unit) {
                trackPointer { position ->
                    change(
                        (position.x / size.width).coerceIn(0f, 1f),
                        1f - (position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvToArgb(hue, 1f, 1f)))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val thumb = Offset(saturation * size.width, (1f - value) * size.height)
        drawCircle(Color.White, radius = 11.dp.toPx(), center = thumb, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 13.dp.toPx(), center = thumb, style = Stroke(1.dp.toPx()))
    }
}

/** Every hue, left to right. */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val hues = remember { (0..6).map { Color(hsvToArgb(it * 60f, 1f, 1f)) } }
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(OneUi.PillShape)
            .pointerInput(Unit) {
                trackPointer { position ->
                    change((position.x / size.width).coerceIn(0f, 1f) * 360f)
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(hues))
        val x = (hue / 360f) * size.width
        drawCircle(
            Color.White,
            radius = size.height / 2 - 2.dp.toPx(),
            center = Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2),
            style = Stroke(3.dp.toPx()),
        )
    }
}

/** Follow one finger from where it lands until it lifts: taps and drags alike. */
private suspend fun PointerInputScope.trackPointer(onPosition: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onPosition(down.position)
        drag(down.id) { change ->
            onPosition(change.position)
            change.consume()
        }
    }
}

/** The theme accents, and the neutrals a background or text is usually wanted in. */
@Composable
private fun Presets(onPick: (Int) -> Unit) {
    val accents = AccentPalette.values.flatMap { listOf(it.light, it.dark) }
    val neutrals = listOf(0xFFFFFFFF, 0xFFF4F4F7, 0xFFC7C7CC, 0xFF6E7179, 0xFF2A2A2E, 0xFF000000)
        .map { Color(it) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (accents + neutrals).chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { color ->
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onPick(color.toArgb()) },
                    )
                }
            }
        }
    }
}
