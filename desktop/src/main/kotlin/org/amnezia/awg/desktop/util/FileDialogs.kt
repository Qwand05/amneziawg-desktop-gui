/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.model.ObservableTunnel
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Native (GTK on Ubuntu) open/save dialogs, replacing Android's storage
 * access framework, plus the zip exporter from `ZipExporterPreference`.
 */
object FileDialogs {
    /** The window the dialogs are modal to; set once by `Main.kt`. */
    var parent: Frame? = null

    fun openFiles(title: String, vararg extensions: String): List<File> {
        val dialog = FileDialog(parent, title, FileDialog.LOAD).apply {
            isMultipleMode = true
            if (extensions.isNotEmpty())
                setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(it) } }
        }
        dialog.isVisible = true
        return dialog.files.toList()
    }

    fun saveFile(title: String, suggestedName: String, directory: File = AppPaths.defaultExportDir): File? {
        val dialog = FileDialog(parent, title, FileDialog.SAVE).apply {
            this.directory = directory.absolutePath
            file = suggestedName
        }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }

    /** Export [configs] as `<name>.conf` entries into an owner-only zip at [target]. */
    fun exportZip(configs: List<Pair<ObservableTunnel, Config>>, target: File) {
        if (configs.isEmpty()) throw IllegalArgumentException(Strings.NO_TUNNELS_ERROR)
        target.delete()
        Files.createFile(target.toPath(), *FilePermissions.ownerOnly)
        ZipOutputStream(target.outputStream()).use { zip ->
            for ((tunnel, config) in configs) {
                zip.putNextEntry(ZipEntry("${tunnel.name}.conf"))
                zip.write(config.toAwgQuickString().toByteArray(StandardCharsets.UTF_8))
            }
            zip.closeEntry()
        }
    }
}
