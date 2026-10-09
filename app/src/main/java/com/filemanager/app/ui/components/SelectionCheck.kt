package com.filemanager.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * The round tick One UI marks a chosen file with: an empty ring, filled with
 * the accent and a white tick once chosen.
 *
 * [overImage] shades the empty ring, so it still shows on a photo.
 */
@Composable
fun SelectionCheck(checked: Boolean, modifier: Modifier = Modifier, overImage: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    val fill by animateColorAsState(
        targetValue = when {
            checked -> accent
            overImage -> Color.Black.copy(alpha = 0.3f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 150),
        label = "selectionCheck",
    )
    val ring = when {
        checked -> accent
        overImage -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, ring, CircleShape)
            .semantics { stateDescription = if (checked) "Selected" else "Not selected" },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                // White, as One UI draws it - the theme's own pick for text on
                // the accent is black on the dark theme's blue - unless the
                // accent is so pale that white would vanish into it.
                tint = if (accent.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
