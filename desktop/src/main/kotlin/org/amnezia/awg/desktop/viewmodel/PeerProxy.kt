/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.amnezia.awg.config.Attribute
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.Peer

/**
 * Editable representation of a `[Peer]` section.
 *
 * Port of the Android `PeerProxy`, including the "exclude private IPs" helper
 * that rewrites `AllowedIPs` between the IPv4 wildcard and the list of public
 * networks.
 */
class PeerProxy {
    private var dnsRoutes: List<String> = emptyList()
    private var owner: ConfigProxy? by mutableStateOf(null)
    private val dnsListener: (String) -> Unit = { setInterfaceDns(it) }

    private var allowedIpsValue: String by mutableStateOf("")
    var allowedIps: String
        get() = allowedIpsValue
        set(value) {
            allowedIpsValue = value
        }

    var endpoint: String by mutableStateOf("")
    var persistentKeepalive: String by mutableStateOf("")
    var preSharedKey: String by mutableStateOf("")
    var publicKey: String by mutableStateOf("")

    private val totalPeers: Int get() = owner?.peers?.size ?: 0

    /**
     * Derived from [allowedIps] and the number of peers. The Android version
     * caches this and recalculates it from list callbacks; reading snapshot
     * state directly keeps Compose in sync without any listeners.
     */
    private val allowedIpsState: AllowedIpsState
        get() = if (totalPeers == 1) {
            // String comparison works because we only care if allowedIps is a
            // superset of one of the above sets of valid networks.
            val networkStrings = getAllowedIpsSet()
            when {
                networkStrings.containsAll(IPV4_WILDCARD) -> AllowedIpsState.CONTAINS_IPV4_WILDCARD
                networkStrings.containsAll(IPV4_PUBLIC_NETWORKS) -> AllowedIpsState.CONTAINS_IPV4_PUBLIC_NETWORKS
                else -> AllowedIpsState.OTHER
            }
        } else {
            AllowedIpsState.INVALID
        }

    val isAbleToExcludePrivateIps: Boolean
        get() = allowedIpsState == AllowedIpsState.CONTAINS_IPV4_PUBLIC_NETWORKS ||
            allowedIpsState == AllowedIpsState.CONTAINS_IPV4_WILDCARD

    val isExcludingPrivateIps: Boolean
        get() = allowedIpsState == AllowedIpsState.CONTAINS_IPV4_PUBLIC_NETWORKS

    constructor()

    constructor(other: Peer) {
        allowedIps = Attribute.join(other.allowedIps)
        endpoint = other.endpoint.map { it.toString() }.orElse("")
        persistentKeepalive = other.persistentKeepalive.map { it.toString() }.orElse("")
        preSharedKey = other.preSharedKey.map { it.toBase64() }.orElse("")
        publicKey = other.publicKey.toBase64()
    }

    fun bind(owner: ConfigProxy) {
        this.owner = owner
        owner.iface.addDnsListener(dnsListener)
        setInterfaceDns(owner.iface.dnsServers)
    }

    fun unbind() {
        val owner = owner ?: return
        owner.iface.removeDnsListener(dnsListener)
        owner.peers.remove(this)
        setInterfaceDns("")
        this.owner = null
    }

    private fun getAllowedIpsSet(): Set<String> = Attribute.split(allowedIps).toSet()

    /**
     * Replace the first instance of the wildcard with the public network list,
     * or vice versa. DNS servers only need special handling when private IPs
     * are being excluded.
     */
    fun setExcludingPrivateIps(excludingPrivateIps: Boolean) {
        if (!isAbleToExcludePrivateIps || isExcludingPrivateIps == excludingPrivateIps) return
        val oldNetworks = if (excludingPrivateIps) IPV4_WILDCARD else IPV4_PUBLIC_NETWORKS
        val newNetworks = if (excludingPrivateIps) IPV4_PUBLIC_NETWORKS else IPV4_WILDCARD
        val input = getAllowedIpsSet()
        val output = LinkedHashSet<String>(input.size - oldNetworks.size + newNetworks.size)
        var replaced = false
        for (network in input) {
            if (oldNetworks.contains(network)) {
                if (!replaced) {
                    for (replacement in newNetworks) if (!output.contains(replacement)) output.add(replacement)
                    replaced = true
                }
            } else if (!output.contains(network)) {
                output.add(network)
            }
        }
        if (excludingPrivateIps) output.addAll(dnsRoutes) else output.removeAll(dnsRoutes.toSet())
        allowedIps = Attribute.join(output)
    }

    @Throws(BadConfigException::class)
    fun resolve(): Peer {
        val builder = Peer.Builder()
        if (allowedIps.isNotEmpty()) builder.parseAllowedIPs(allowedIps)
        if (endpoint.isNotEmpty()) builder.parseEndpoint(endpoint)
        if (persistentKeepalive.isNotEmpty()) builder.parsePersistentKeepalive(persistentKeepalive)
        if (preSharedKey.isNotEmpty()) builder.parsePreSharedKey(preSharedKey)
        if (publicKey.isNotEmpty()) builder.parsePublicKey(publicKey)
        return builder.build()
    }

    private fun setInterfaceDns(dnsServers: CharSequence) {
        val newDnsRoutes = Attribute.split(dnsServers)
            .filter { !it.contains(":") }
            .map { "$it/32" }
        if (allowedIpsState == AllowedIpsState.CONTAINS_IPV4_PUBLIC_NETWORKS) {
            val input = getAllowedIpsSet()
            val output = input.filter { !dnsRoutes.contains(it) || newDnsRoutes.contains(it) }
                .plus(newDnsRoutes)
                .distinct()
            // None of the public networks are /32s, so this cannot change the state.
            allowedIps = Attribute.join(output)
        }
        dnsRoutes = newDnsRoutes
    }

    private enum class AllowedIpsState {
        CONTAINS_IPV4_PUBLIC_NETWORKS, CONTAINS_IPV4_WILDCARD, INVALID, OTHER
    }

    companion object {
        private val IPV4_PUBLIC_NETWORKS = setOf(
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3",
            "64.0.0.0/2", "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6", "172.0.0.0/12",
            "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9", "173.0.0.0/8", "174.0.0.0/7",
            "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11", "192.160.0.0/13", "192.169.0.0/16",
            "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12", "192.192.0.0/10",
            "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4"
        )
        private val IPV4_WILDCARD = setOf("0.0.0.0/0")
    }
}
