/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.Config

/**
 * The editor's working copy of a tunnel configuration.
 *
 * Port of the Android `ConfigProxy`: `Parcelable` and data binding were
 * replaced by Compose snapshot state.
 */
class ConfigProxy {
    val iface: InterfaceProxy
    val peers: SnapshotStateList<PeerProxy> = mutableStateListOf()

    constructor(other: Config) {
        iface = InterfaceProxy(other.getInterface())
        other.peers.forEach {
            val proxy = PeerProxy(it)
            peers.add(proxy)
            proxy.bind(this)
        }
    }

    constructor() {
        iface = InterfaceProxy()
    }

    fun addPeer(): PeerProxy {
        val proxy = PeerProxy()
        peers.add(proxy)
        proxy.bind(this)
        return proxy
    }

    @Throws(BadConfigException::class)
    fun resolve(): Config =
        Config.Builder()
            .setInterface(iface.resolve())
            .addPeers(peers.map { it.resolve() })
            .build()
}
