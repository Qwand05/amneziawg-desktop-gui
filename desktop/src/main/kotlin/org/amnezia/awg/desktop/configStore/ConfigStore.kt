/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.configStore

import org.amnezia.awg.config.Config

/**
 * Interface for persistent storage providers for AmneziaWG configurations.
 *
 * Ported unchanged from the Android application so that both frontends agree
 * on the storage semantics.
 */
interface ConfigStore {
    /** Create a persistent tunnel, which must have a unique name. */
    @Throws(Exception::class)
    fun create(name: String, config: Config): Config

    /** Delete a persistent tunnel. */
    @Throws(Exception::class)
    fun delete(name: String)

    /** Enumerate the names of tunnels present in persistent storage. */
    fun enumerate(): Set<String>

    /** Load the configuration for the tunnel given by [name]. */
    @Throws(Exception::class)
    fun load(name: String): Config

    /** Rename the configuration for the tunnel given by [name]. */
    @Throws(Exception::class)
    fun rename(name: String, replacement: String)

    /** Save an updated configuration for an existing tunnel. */
    @Throws(Exception::class)
    fun save(name: String, config: Config): Config
}
