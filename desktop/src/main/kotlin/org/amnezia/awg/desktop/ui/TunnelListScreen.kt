/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Save
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.model.AppState
import org.amnezia.awg.desktop.model.ObservableTunnel
import org.amnezia.awg.desktop.model.Screen
import org.amnezia.awg.desktop.util.CameraDevice
import org.amnezia.awg.desktop.util.Cameras
import org.amnezia.awg.desktop.util.ErrorMessages
import org.amnezia.awg.desktop.util.FileDialogs
import org.amnezia.awg.desktop.util.Strings
import org.amnezia.awg.desktop.util.TunnelImporter
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/** Toggle [tunnel] and report failures, like `BaseFragment.setTunnelState`. */
fun AppState.toggleTunnel(tunnel: ObservableTunnel, checked: Boolean) {
    scope.launch {
        try {
            tunnel.setStateAsync(Tunnel.State.of(checked))
        } catch (e: Throwable) {
            val error = ErrorMessages[e]
            showSnackbar((if (checked) Strings.ERROR_UP else Strings.ERROR_DOWN).format(error))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TunnelListScreen(app: AppState, selectedTunnel: ObservableTunnel?) {
    val manager = app.tunnelManager
    val selection = remember { mutableStateListOf<ObservableTunnel>() }
    // Tunnels awaiting delete confirmation (from the selection bar or a context menu).
    var pendingDelete by remember { mutableStateOf<List<ObservableTunnel>?>(null) }
    // Context menu: the tunnel it is for and where it was opened.
    var contextMenu by remember { mutableStateOf<Pair<ObservableTunnel, Offset>?>(null) }
    var qrCode by remember { mutableStateOf<Pair<ObservableTunnel, Config>?>(null) }
    // Whether Ctrl was held on the last press, so that Ctrl+click selects.
    var ctrlClick by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    // Config text waiting for a name (QR code / clipboard import).
    var pendingConfig by remember { mutableStateOf<Config?>(null) }
    // Re-detected whenever the menu opens, so plugging a camera in is picked up.
    var cameras by remember { mutableStateOf(emptyList<CameraDevice>()) }
    var hasFfmpeg by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()

    fun importFromQrImage() {
        val file = FileDialogs.openFiles(Strings.CREATE_FROM_QR_IMAGE, ".png", ".jpg", ".jpeg", ".gif", ".bmp")
            .firstOrNull() ?: return
        app.scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { TunnelImporter.decodeQrImage(file) }
                pendingConfig = TunnelImporter.parseConfigText(text)
            } catch (e: Throwable) {
                app.showSnackbar(TunnelImporter.importResultMessage(emptyList(), listOf(e)))
            }
        }
    }

    fun importFromClipboard() {
        try {
            val text = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
            pendingConfig = TunnelImporter.parseConfigText(text)
        } catch (e: Throwable) {
            app.showSnackbar(TunnelImporter.importResultMessage(emptyList(), listOf(e)))
        }
    }

    fun importFromFiles() {
        val files = FileDialogs.openFiles(Strings.CREATE_FROM_FILE, ".conf", ".zip")
        if (files.isEmpty()) return
        app.scope.launch { app.showSnackbar(TunnelImporter.importFiles(manager, files)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) Strings.deleteTitle(selection.size).format(selection.size) else Strings.APP_NAME) },
                navigationIcon = {
                    if (selecting) TooltipIconButton(Icons.Default.Close, Strings.CANCEL) { selection.clear() }
                },
                actions = {
                    if (selecting) {
                        TooltipIconButton(Icons.Default.SelectAll, Strings.SELECT_ALL) {
                            selection.clear(); selection.addAll(manager.tunnels)
                        }
                        TooltipIconButton(Icons.Default.Delete, Strings.DELETE) { pendingDelete = selection.toList() }
                    } else {
                        TooltipIconButton(Icons.Default.Settings, Strings.SETTINGS) { app.navigate(Screen.Settings) }
                    }
                },
                colors = if (selecting) TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) else TopAppBarDefaults.topAppBarColors(),
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = {
                    cameras = Cameras.detect()
                    hasFfmpeg = Cameras.hasFfmpeg
                    addMenu = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = Strings.IMPORT_TUNNEL_TITLE)
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(Strings.CREATE_FROM_FILE) },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                        onClick = { addMenu = false; importFromFiles() },
                    )
                    val canScan = cameras.isNotEmpty() && hasFfmpeg
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(Strings.CREATE_FROM_QR_CODE)
                                if (!canScan) {
                                    Text(
                                        if (cameras.isEmpty()) Strings.NO_CAMERA else Strings.FFMPEG_MISSING,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                        enabled = canScan,
                        onClick = { addMenu = false; scanning = true },
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.CREATE_FROM_QR_IMAGE) },
                        leadingIcon = { Icon(Icons.Default.QrCode, null) },
                        onClick = { addMenu = false; importFromQrImage() },
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.CREATE_FROM_CLIPBOARD) },
                        leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                        onClick = { addMenu = false; importFromClipboard() },
                    )
                    DropdownMenuItem(
                        text = { Text(Strings.CREATE_EMPTY) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { addMenu = false; app.openEditor(null) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !manager.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                manager.tunnels.isEmpty() -> Text(
                    Strings.TUNNEL_LIST_PLACEHOLDER,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(manager.tunnels, key = { it }) { tunnel ->
                        val isSelected = tunnel in selection
                        fun toggleSelection() {
                            if (isSelected) selection.remove(tunnel) else selection.add(tunnel)
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.secondaryContainer
                                        tunnel === selectedTunnel -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                )
                                .onPrimaryPress { ctrlClick = it }
                                .onRightClick { contextMenu = tunnel to it }
                                .combinedClickable(
                                    onClick = {
                                        if (selecting || ctrlClick) toggleSelection() else app.openTunnel(tunnel)
                                    },
                                    onLongClick = { toggleSelection() },
                                )
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            StatusIndicator(tunnel, isSelected)
                            Column(Modifier.weight(1f)) {
                                Text(tunnel.name, style = MaterialTheme.typography.titleMedium)
                                tunnelStatusText(tunnel)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                }
                                tunnel.lastError?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                                }
                            }
                            TunnelToggle(tunnel) { app.toggleTunnel(tunnel, it) }

                            contextMenu?.takeIf { it.first === tunnel }?.let { (_, position) ->
                                val close = { contextMenu = null }
                                if (isSelected && selection.size > 1) {
                                    SelectionContextMenu(
                                        position = position,
                                        count = selection.size,
                                        onDismiss = close,
                                        onDelete = { close(); pendingDelete = selection.toList() },
                                        onSelectAll = { close(); selection.clear(); selection.addAll(manager.tunnels) },
                                        onClearSelection = { close(); selection.clear() },
                                    )
                                } else {
                                    TunnelContextMenu(
                                        app = app,
                                        tunnel = tunnel,
                                        position = position,
                                        isSelected = isSelected,
                                        onDismiss = close,
                                        onToggleSelection = { close(); toggleSelection() },
                                        onShowQrCode = { config -> close(); qrCode = tunnel to config },
                                        onDelete = { close(); pendingDelete = listOf(tunnel) },
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(Strings.deleteConfirmTitle(targets.size)) },
            text = { Text(targets.joinToString(", ") { it.name }) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    selection.removeAll(targets)
                    app.deleteTunnels(targets)
                }) { Text(Strings.DELETE) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(Strings.CANCEL) } },
        )
    }

    qrCode?.let { (tunnel, config) -> QrCodeDialog(tunnel.name, config) { qrCode = null } }

    if (scanning && cameras.isNotEmpty()) {
        QrScannerDialog(
            cameras = cameras,
            onDismiss = { scanning = false },
            onConfig = { scanning = false; pendingConfig = it },
        )
    }

    pendingConfig?.let { config ->
        ConfigNamingDialog(
            onDismiss = { pendingConfig = null },
            onCreate = { name ->
                app.scope.launch {
                    try {
                        val tunnel = manager.create(name, config)
                        pendingConfig = null
                        app.showSnackbar(Strings.IMPORT_SUCCESS.format(tunnel.name))
                    } catch (e: Throwable) {
                        app.showSnackbar(Strings.IMPORT_ERROR.format(ErrorMessages[e]))
                    }
                }
            },
        )
    }
}

/** Remembers whether Ctrl (or Meta) was held when the primary button went down. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.onPrimaryPress(onPress: (ctrl: Boolean) -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        if (event.button == PointerButton.Primary)
            onPress(event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed)
    }

/** Replaces the Android checkbox: a check mark when selected, otherwise a status dot. */
@Composable
private fun StatusIndicator(tunnel: ObservableTunnel, selected: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Icon(Icons.Default.CheckCircle, contentDescription = Strings.SELECTED, tint = MaterialTheme.colorScheme.primary)
        } else {
            val color = when {
                tunnel.state != Tunnel.State.UP -> MaterialTheme.colorScheme.outlineVariant
                tunnel.connectionStatus == ObservableTunnel.ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
    }
}

/** Right-click menu for a single tunnel. */
@Composable
private fun TunnelContextMenu(
    app: AppState,
    tunnel: ObservableTunnel,
    position: Offset,
    isSelected: Boolean,
    onDismiss: () -> Unit,
    onToggleSelection: () -> Unit,
    onShowQrCode: (Config) -> Unit,
    onDelete: () -> Unit,
) {
    /** Run [block] with the tunnel's config, reporting load errors. */
    fun withConfig(block: (Config) -> Unit) {
        onDismiss()
        app.scope.launch {
            try {
                block(tunnel.getConfigAsync())
            } catch (e: Throwable) {
                app.showSnackbar(ErrorMessages[e])
            }
        }
    }

    val up = tunnel.state == Tunnel.State.UP
    ContextMenu(position, onDismiss) {
        ContextMenuItem(
            if (up) Strings.DISCONNECT else Strings.CONNECT,
            if (up) Icons.Default.LinkOff else Icons.Default.Link,
            enabled = !tunnel.isBusy,
        ) { onDismiss(); app.toggleTunnel(tunnel, !up) }
        ContextMenuSeparator()
        ContextMenuItem(Strings.SHOW_DETAILS, Icons.Default.Info) { onDismiss(); app.openTunnel(tunnel) }
        ContextMenuItem(Strings.EDIT, Icons.Default.Edit) {
            onDismiss()
            app.openTunnel(tunnel)
            app.navigate(Screen.TunnelEditor(tunnel))
        }
        ContextMenuItem(Strings.EXPORT_CONFIG, Icons.Default.Save) { withConfig { app.exportConfig(tunnel, it) } }
        ContextMenuItem(Strings.SHOW_QR_CODE, Icons.Default.QrCode2) { withConfig(onShowQrCode) }
        ContextMenuSeparator()
        ContextMenuItem(
            if (isSelected) Strings.DESELECT else Strings.SELECT,
            if (isSelected) Icons.Default.RemoveDone else Icons.Default.Done,
            onClick = onToggleSelection,
        )
        ContextMenuSeparator()
        ContextMenuItem(Strings.DELETE, Icons.Default.Delete, onClick = onDelete)
    }
}

/** Right-click menu on a row that is part of a multiple selection. */
@Composable
private fun SelectionContextMenu(
    position: Offset,
    count: Int,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    ContextMenu(position, onDismiss) {
        ContextMenuItem(Strings.SELECT_ALL, Icons.Default.SelectAll, onClick = onSelectAll)
        ContextMenuItem(Strings.CLEAR_SELECTION, Icons.Default.RemoveDone, onClick = onClearSelection)
        ContextMenuSeparator()
        ContextMenuItem(Strings.deleteSelected(count), Icons.Default.Delete, onClick = onDelete)
    }
}

/** Port of `ConfigNamingDialogFragment`. */
@Composable
fun ConfigNamingDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val valid = !Tunnel.isNameInvalid(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.IMPORT_FROM_QR_CODE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Strings.IMPORT_DISCLAIMER, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter(::isNameChar).take(Tunnel.NAME_MAX_LENGTH) },
                    label = { Text(Strings.TUNNEL_NAME) },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onCreate(name) }) { Text(Strings.CREATE_TUNNEL) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}

/** Characters allowed in tunnel names, like the Android `NameInputFilter`. */
fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() && c.code < 128 || c in "_=+.-"

fun AppState.deleteTunnels(targets: List<ObservableTunnel>) {
    scope.launch {
        val failures = ArrayList<Throwable>()
        for (tunnel in targets) {
            try {
                tunnel.deleteAsync()
                forgetTunnel(tunnel)
            } catch (e: Throwable) {
                failures.add(e)
            }
        }
        showSnackbar(
            if (failures.isEmpty()) Strings.deleteSuccess(targets.size).format(targets.size)
            else Strings.deleteError(failures.size, ErrorMessages[failures.first()])
        )
    }
}
