/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.amnezia.awg.desktop.theme.ThemeColors
import org.amnezia.awg.desktop.theme.parseHexColor
import org.amnezia.awg.desktop.theme.toHex
import org.amnezia.awg.desktop.util.Strings

/** Settings row showing a color swatch; clicking it opens [ColorPickerDialog]. */
@Composable
fun ColorRow(title: String, color: Color, onPick: (Color) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(color.toHex(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Swatch(color, size = 32)
    }
    if (picking) {
        ColorPickerDialog(
            title = title,
            initial = color,
            onDismiss = { picking = false },
            onConfirm = { picking = false; onPick(it) },
        )
    }
}

@Composable
private fun Swatch(color: Color, size: Int, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = if (color.luminance() > 0.4f) Color.Black else Color.White,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(title: String, initial: Color, onDismiss: () -> Unit, onConfirm: (Color) -> Unit) {
    var color by remember { mutableStateOf(initial) }
    var hex by remember { mutableStateOf(initial.toHex()) }
    fun select(newColor: Color) {
        color = newColor
        hex = newColor.toHex()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeColors.PRESETS.forEach { preset ->
                        Swatch(preset, size = 36, selected = preset == color) { select(preset) }
                    }
                }
                ChannelSlider("R", color.red, Color.Red) { select(color.copy(red = it)) }
                ChannelSlider("G", color.green, Color(0xFF2E7D32)) { select(color.copy(green = it)) }
                ChannelSlider("B", color.blue, Color.Blue) { select(color.copy(blue = it)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { text ->
                            hex = text.take(7)
                            parseHexColor(hex)?.let { color = it }
                        },
                        label = { Text(Strings.COLOR_HEX) },
                        isError = parseHexColor(hex) == null,
                        singleLine = true,
                        modifier = Modifier.width(140.dp),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(color),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color) }) { Text(Strings.APPLY) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}

@Composable
private fun ChannelSlider(label: String, value: Float, tint: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(20.dp), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
        )
        Text((value * 255).toInt().toString(), Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
    }
}
