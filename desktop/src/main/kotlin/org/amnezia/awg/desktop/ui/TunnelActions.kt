/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.model.AppState
import org.amnezia.awg.desktop.model.ObservableTunnel
import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.DesktopShortcuts
import org.amnezia.awg.desktop.util.ErrorMessages
import org.amnezia.awg.desktop.util.FileDialogs
import org.amnezia.awg.desktop.util.Strings
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/*
 * Tunnel actions shared by the detail screen, the list's context menu, the
 * tray menu and desktop shortcuts.
 */

/** Switch [tunnel] on or off and report failures, like `BaseFragment.setTunnelState`. */
fun AppState.toggleTunnel(tunnel: ObservableTunnel, checked: Boolean) {
    scope.launch { setTunnelState(tunnel, checked) }
}

/** Switch [tunnel] on or off; returns false after reporting a failure. */
suspend fun AppState.setTunnelState(tunnel: ObservableTunnel, checked: Boolean): Boolean {
    try {
        tunnel.setStateAsync(Tunnel.State.of(checked))
        return true
    } catch (e: Throwable) {
        val error = ErrorMessages[e]
        AppLog.e("Tunnels", "Switching ${tunnel.name} ${if (checked) "on" else "off"} failed: $error")
        showSnackbar(if (checked) Strings.errorUp(error) else Strings.errorDown(error))
        return false
    }
}

/**
 * `--toggle <name>` from a desktop shortcut: connect the tunnel if it is down,
 * disconnect it otherwise. [onFailure] runs when there is no such tunnel or
 * the switch fails, so the (possibly hidden) window can show the error.
 */
fun AppState.toggleTunnelNamed(name: String, onFailure: () -> Unit) {
    scope.launch {
        val tunnel = tunnelManager.tunnels.firstOrNull { it.name == name }
        if (tunnel == null) {
            AppLog.w("Tunnels", "Shortcut for $name: no such tunnel")
            showSnackbar(Strings.tunnelNotFound(name))
            onFailure()
            return@launch
        }
        // A second click while the first is still switching is ignored.
        if (tunnel.isBusy) return@launch
        val up = tunnel.state == Tunnel.State.UP
        AppLog.i("Tunnels", "Shortcut for $name: switching ${if (up) "off" else "on"}")
        if (!setTunnelState(tunnel, !up)) onFailure()
    }
}

/** Put a shortcut that toggles [tunnel] on the Desktop. */
fun AppState.createDesktopShortcut(tunnel: ObservableTunnel) {
    val launcher = DesktopShortcuts.launcher
    if (launcher == null) {
        showSnackbar(Strings.SHORTCUT_UNAVAILABLE)
        return
    }
    scope.launch {
        try {
            val file = withContext(Dispatchers.IO) { DesktopShortcuts.create(tunnel.name, launcher) }
            AppLog.i("Shortcuts", "Created $file")
            showSnackbar(Strings.shortcutCreated(file.absolutePath))
        } catch (e: Throwable) {
            AppLog.e("Shortcuts", "Could not create a shortcut for ${tunnel.name}", e)
            showSnackbar(Strings.shortcutError(ErrorMessages[e]))
        }
    }
}

/** Save [config] as `<name>.conf`, owner-only since it contains the private key. */
fun AppState.exportConfig(tunnel: ObservableTunnel, config: Config) {
    val target = FileDialogs.saveFile(Strings.EXPORT_CONFIG, "${tunnel.name}.conf") ?: return
    try {
        val path = target.toPath()
        if (!Files.exists(path))
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(
            path,
            config.toAwgQuickString().toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        showSnackbar(Strings.zipExportSuccess(target.absolutePath))
    } catch (e: Throwable) {
        showSnackbar(Strings.zipExportError(ErrorMessages[e]))
    }
}

@Composable
fun QrCodeDialog(title: String, config: Config, onDismiss: () -> Unit) {
    val image = remember(config) { qrCode(config.toAwgQuickString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Image(image, contentDescription = Strings.SHOW_QR_CODE, modifier = Modifier.size(360.dp)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.CLOSE) } },
    )
}

private fun qrCode(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    val scale = 6
    val image = BufferedImage(matrix.width * scale, matrix.height * scale, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until image.height) for (x in 0 until image.width) {
        image.setRGB(x, y, if (matrix.get(x / scale, y / scale)) 0x000000 else 0xFFFFFF)
    }
    return image.toComposeImageBitmap()
}
