/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.configStore

import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Configuration store that keeps one `awg-quick` style file per tunnel,
 * mirroring the Android `FileConfigStore`.
 */
class FileConfigStore(private val directory: File) : ConfigStore {

    @Throws(IOException::class)
    override fun create(name: String, config: Config): Config {
        val file = fileFor(name)
        if (!file.createNewFile())
            throw IOException("Configuration file ${file.name} already exists")
        write(file, config)
        return config
    }

    @Throws(IOException::class)
    override fun delete(name: String) {
        val file = fileFor(name)
        if (!file.delete())
            throw IOException("Could not delete configuration ${file.name}")
    }

    override fun enumerate(): Set<String> {
        val files = directory.listFiles() ?: return emptySet()
        return files
            .filter { it.isFile && it.name.endsWith(".conf") && !it.name.startsWith(".") }
            .map { it.name.removeSuffix(".conf") }
            .toSet()
    }

    private fun fileFor(name: String) = File(directory, "$name.conf")

    @Throws(BadConfigException::class, IOException::class)
    override fun load(name: String): Config {
        FileInputStream(fileFor(name)).use { stream -> return Config.parse(stream) }
    }

    @Throws(IOException::class)
    override fun rename(name: String, replacement: String) {
        val file = fileFor(name)
        val replacementFile = fileFor(replacement)
        if (!replacementFile.createNewFile()) throw IOException("Configuration $replacement already exists")
        if (!file.renameTo(replacementFile)) {
            replacementFile.delete()
            throw IOException("Could not rename ${file.name}")
        }
    }

    @Throws(IOException::class)
    override fun save(name: String, config: Config): Config {
        val file = fileFor(name)
        if (!file.isFile) throw FileNotFoundException("Configuration ${file.name} not found")
        write(file, config)
        return config
    }

    private fun write(file: File, config: Config) {
        file.parentFile?.mkdirs()
        // Configurations contain private keys: keep them owner-only.
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        FileOutputStream(file, false).use { stream ->
            stream.write(config.toAwgQuickString().toByteArray(StandardCharsets.UTF_8))
        }
    }
}
