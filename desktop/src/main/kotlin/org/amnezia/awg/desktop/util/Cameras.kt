/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.charset.StandardCharsets

/** A V4L2 video capture device, e.g. `/dev/video0`. */
data class CameraDevice(val device: File, val name: String)

/**
 * Camera discovery and QR code scanning, the desktop replacement for the
 * Android `zxing-android-embedded` scanner. Frames are grabbed with `ffmpeg`
 * so that no native camera library has to be bundled.
 */
object Cameras {
    private val SYSFS = File("/sys/class/video4linux")

    /**
     * Usable cameras. Each physical camera exposes several nodes (capture and
     * metadata); only the capture node has `index` 0. Nodes the user cannot
     * open are skipped, since they could not be used for scanning anyway.
     */
    fun detect(sysfs: File = SYSFS, devDir: File = File("/dev")): List<CameraDevice> =
        (sysfs.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("video") }
            .filter { (readAttribute(it, "index")?.toIntOrNull() ?: 0) == 0 }
            .map { CameraDevice(File(devDir, it.name), readAttribute(it, "name") ?: it.name) }
            .filter { it.device.exists() && it.device.canRead() }
            .sortedBy { it.device.name.removePrefix("video").toIntOrNull() ?: Int.MAX_VALUE }

    val hasFfmpeg: Boolean
        get() = System.getenv("PATH").orEmpty().split(':').any { File(it, "ffmpeg").canExecute() }

    private fun readAttribute(dir: File, name: String): String? =
        try {
            File(dir, name).readText().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
}

/**
 * Streams frames from [camera] and reports every frame (for the preview) and
 * the first decoded QR code. Runs on its own thread; [close] stops ffmpeg,
 * which also unblocks the reader.
 */
class QrCameraScanner(
    private val camera: CameraDevice,
    private val onFrame: (BufferedImage) -> Unit,
    private val onQrCode: (String) -> Boolean,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    @Volatile
    private var closed = false

    @Volatile
    private var process: Process? = null

    init {
        Thread(::readLoop, "qr-camera-scanner").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        // Started here rather than in the constructor so that a missing or
        // failing ffmpeg is reported through onError instead of throwing.
        val process = try {
            ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-f", "v4l2", "-i", camera.device.absolutePath,
                "-vf", "fps=$FPS,scale=$WIDTH:$HEIGHT:force_original_aspect_ratio=decrease," +
                    "pad=$WIDTH:$HEIGHT:(ow-iw)/2:(oh-ih)/2",
                "-pix_fmt", "rgb24", "-f", "rawvideo", "-",
            ).start()
        } catch (e: Exception) {
            onError(e.message ?: e.toString())
            return
        }
        this.process = process
        if (closed) {
            process.destroy()
            return
        }
        val reader = QRCodeReader()
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        val frame = ByteArray(WIDTH * HEIGHT * 3)
        val pixels = IntArray(WIDTH * HEIGHT)
        try {
            DataInputStream(process.inputStream.buffered()).use { input ->
                while (!closed) {
                    input.readFully(frame)
                    for (i in pixels.indices) {
                        val r = frame[i * 3].toInt() and 0xFF
                        val g = frame[i * 3 + 1].toInt() and 0xFF
                        val b = frame[i * 3 + 2].toInt() and 0xFF
                        pixels[i] = (r shl 16) or (g shl 8) or b
                    }
                    val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
                    image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH)
                    onFrame(image)
                    val text = try {
                        reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(WIDTH, HEIGHT, pixels))), hints).text
                    } catch (_: ReaderException) {
                        null
                    } finally {
                        reader.reset()
                    }
                    if (text != null && onQrCode(text)) break
                }
            }
        } catch (e: Exception) {
            if (!closed) {
                val stderr = process.errorStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
                onError(stderr.ifEmpty { if (e is EOFException) "${camera.name} stopped" else e.message ?: e.toString() })
            }
        } finally {
            close()
        }
    }

    override fun close() {
        closed = true
        process?.destroy()
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        private const val FPS = 10
    }
}
