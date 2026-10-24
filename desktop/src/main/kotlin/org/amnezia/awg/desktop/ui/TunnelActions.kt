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
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.model.AppState
import org.amnezia.awg.desktop.model.ObservableTunnel
import org.amnezia.awg.desktop.util.ErrorMessages
import org.amnezia.awg.desktop.util.FileDialogs
import org.amnezia.awg.desktop.util.Strings
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/*
 * Tunnel actions shared by the detail screen and the list's context menu.
 */

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
