/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions

/**
 * POSIX file permissions, skipped where the filesystem has none (Windows):
 * asking for them there throws, which must not keep a config from being
 * written.
 */
object FilePermissions {
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    /** For files holding private keys: readable by the owner only. Spread into `Files.create*`. */
    val ownerOnly: Array<FileAttribute<*>> =
        if (posix) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()

    /** `rwxr-xr-x`, as desktop entries need to be trusted. */
    fun setExecutable(path: Path) {
        if (posix) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        else path.toFile().setExecutable(true)
    }
}
