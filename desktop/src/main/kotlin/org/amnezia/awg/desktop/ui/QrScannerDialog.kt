/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.util.CameraDevice
import org.amnezia.awg.desktop.util.QrCameraScanner
import org.amnezia.awg.desktop.util.Strings
import org.amnezia.awg.desktop.util.TunnelImporter
import javax.swing.SwingUtilities

/**
 * Live camera preview that scans for a configuration QR code, the desktop
 * counterpart of the Android "Scan from QR code" action.
 */
@Composable
fun QrScannerDialog(cameras: List<CameraDevice>, onDismiss: () -> Unit, onConfig: (Config) -> Unit) {
    var camera by remember { mutableStateOf(cameras.first()) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(camera) {
        frame = null
        failed = false
        message = null
        val scanner = QrCameraScanner(
            camera,
            onFrame = { image ->
                val bitmap = image.toComposeImageBitmap()
                SwingUtilities.invokeLater { frame = bitmap }
            },
            onQrCode = { text ->
                val config = try {
                    TunnelImporter.parseConfigText(text)
                } catch (_: Throwable) {
                    null
                }
                SwingUtilities.invokeLater {
                    if (config != null) onConfig(config) else message = Strings.QR_NOT_A_CONFIG
                }
                // Keep scanning until a usable configuration shows up.
                config != null
            },
            onError = { error ->
                SwingUtilities.invokeLater {
                    failed = true
                    message = Strings.cameraError(error)
                }
            },
        )
        onDispose { scanner.close() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.IMPORT_FROM_QR_CODE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (cameras.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cameras.forEach {
                            FilterChip(selected = it == camera, onClick = { camera = it }, label = { Text(it.name) })
                        }
                    }
                }
                Box(
                    Modifier
                        .width(480.dp)
                        .aspectRatio(QrCameraScanner.WIDTH.toFloat() / QrCameraScanner.HEIGHT)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val current = frame
                    if (current != null) Image(current, contentDescription = camera.name)
                    else if (!failed) CircularProgressIndicator()
                }
                Text(
                    message ?: Strings.QR_SCAN_HINT,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}
