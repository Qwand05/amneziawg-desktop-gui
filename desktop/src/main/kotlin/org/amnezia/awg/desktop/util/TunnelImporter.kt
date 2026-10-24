/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.model.ObservableTunnel
import org.amnezia.awg.desktop.model.TunnelManager
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

/**
 * Port of the Android `TunnelImporter` and `QrCodeFromFileScanner`, reading
 * from local files instead of content URIs.
 */
object TunnelImporter {

    /**
     * Import every `.conf` or `.zip` in [files], returning the message to show,
     * exactly like the Android importer reports its results.
     */
    suspend fun importFiles(manager: TunnelManager, files: List<File>): String {
        val tunnels = ArrayList<ObservableTunnel>()
        val throwables = ArrayList<Throwable>()
        for (file in files) {
            try {
                importFile(manager, file, tunnels, throwables)
            } catch (e: Throwable) {
                throwables.add(e)
            }
        }
        return importResultMessage(tunnels, throwables)
    }

    private suspend fun importFile(
        manager: TunnelManager,
        file: File,
        tunnels: MutableList<ObservableTunnel>,
        throwables: MutableList<Throwable>,
    ) {
        val lower = file.name.lowercase()
        when {
            lower.endsWith(".conf") -> {
                val config = withContext(Dispatchers.IO) { file.inputStream().use { Config.parse(it) } }
                tunnels.add(manager.create(file.name.dropLast(".conf".length), config))
            }

            lower.endsWith(".zip") -> {
                val parsed = withContext(Dispatchers.IO) { readZip(file, throwables) }
                if (parsed.isEmpty() && throwables.isEmpty())
                    throw IllegalArgumentException(Strings.NO_CONFIGS_ERROR)
                for ((name, config) in parsed) {
                    try {
                        tunnels.add(manager.create(name, config))
                    } catch (e: Throwable) {
                        throwables.add(e)
                    }
                }
            }

            else -> throw IllegalArgumentException(Strings.BAD_EXTENSION_ERROR)
        }
    }

    private fun readZip(file: File, throwables: MutableList<Throwable>): List<Pair<String, Config>> {
        val result = ArrayList<Pair<String, Config>>()
        ZipInputStream(file.inputStream()).use { zip ->
            val reader = BufferedReader(InputStreamReader(zip, StandardCharsets.UTF_8))
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (!name.lowercase().endsWith(".conf")) continue
                try {
                    result.add(name.dropLast(".conf".length) to Config.parse(reader))
                } catch (e: Throwable) {
                    throwables.add(e)
                }
            }
        }
        return result
    }

    /** Parse [text] to make sure it is a valid configuration (QR code / clipboard import). */
    fun parseConfigText(text: String): Config =
        Config.parse(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))

    /** Decode the QR code contained in an image file. */
    fun decodeQrImage(file: File): String {
        val image = ImageIO.read(file) ?: throw IllegalArgumentException(Strings.UNSUPPORTED_IMAGE)
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        val reader = QRCodeReader()
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            // Inverted (light on dark) codes, like the Android scanner retries.
            reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text
        }
    }

    fun importResultMessage(tunnels: List<ObservableTunnel>, throwables: Collection<Throwable>): String {
        for (throwable in throwables) AppLog.w("Import", "Import failed: ${ErrorMessages[throwable]}", throwable)
        if (tunnels.isNotEmpty()) AppLog.i("Import", "Imported ${tunnels.joinToString { it.name }}")
        var message = ""
        for (throwable in throwables) message = Strings.importError(ErrorMessages[throwable])
        if (tunnels.size == 1 && throwables.isEmpty())
            message = Strings.importSuccess(tunnels[0].name)
        else if (tunnels.isEmpty() && throwables.size == 1)
        else if (throwables.isEmpty())
            message = Strings.importTotalSuccess(tunnels.size)
        else
            message = Strings.importPartialSuccess(tunnels.size, tunnels.size + throwables.size)
        return message
    }
}
