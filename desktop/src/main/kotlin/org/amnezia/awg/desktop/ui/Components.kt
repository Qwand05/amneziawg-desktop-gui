/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.desktop.model.ObservableTunnel
import org.amnezia.awg.desktop.util.Strings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Icon button with a tooltip, the desktop replacement for Android's long-press hints. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = description)
        }
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    TooltipIconButton(Icons.AutoMirrored.Filled.ArrowBack, Strings.BACK, onClick = onBack)
}

/** A titled card, like the Android `TvCardView`/`MaterialCardView` sections. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                actions()
            }
            content()
        }
    }
}

/** Read-only label/value pair; clicking copies the value, like the Android detail screen. */
@Composable
fun LabeledValue(
    label: String,
    value: String,
    onCopied: ((String) -> Unit)? = null,
    monospace: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (onCopied != null) it.clickable { copyToClipboard(value); onCopied(label) } else it }
            .padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/** Switch that reflects (and toggles) a tunnel's state, with a spinner while busy. */
@Composable
fun TunnelToggle(tunnel: ObservableTunnel, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    if (tunnel.isBusy) {
        CircularProgressIndicator(Modifier.padding(horizontal = 14.dp).size(24.dp), strokeWidth = 3.dp)
    } else {
        Switch(checked = tunnel.state == Tunnel.State.UP, onCheckedChange = onToggle, enabled = enabled)
    }
}

/** Short status line shown below a tunnel name. */
fun tunnelStatusText(tunnel: ObservableTunnel): String? = when {
    tunnel.state != Tunnel.State.UP -> null
    tunnel.connectionStatus == ObservableTunnel.ConnectionStatus.CONNECTED -> Strings.TUNNEL_STATUS_CONNECTED
    else -> Strings.TUNNEL_STATUS_CONNECTING
}

/** Settings style row with a title, summary and trailing switch. */
@Composable
fun SwitchRow(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Settings style row that performs an action when clicked. */
@Composable
fun ActionRow(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Vertical scrollbar along the end edge of a scrolling [Column]. */
@Composable
fun BoxScope.VerticalScrollbarFor(state: ScrollState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
}

/** Vertical scrollbar along the end edge of a [androidx.compose.foundation.lazy.LazyColumn]. */
@Composable
fun BoxScope.VerticalScrollbarFor(state: LazyListState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
}
