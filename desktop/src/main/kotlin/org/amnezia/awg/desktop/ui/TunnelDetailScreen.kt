/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Attribute
import org.amnezia.awg.config.Config
import org.amnezia.awg.config.Interface
import org.amnezia.awg.config.Peer
import org.amnezia.awg.desktop.model.AppState
import org.amnezia.awg.desktop.model.ObservableTunnel
import org.amnezia.awg.desktop.model.Screen
import org.amnezia.awg.desktop.util.ErrorMessages
import org.amnezia.awg.desktop.util.QuantityFormatter
import org.amnezia.awg.desktop.util.Strings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelDetailScreen(app: AppState, tunnel: ObservableTunnel, showBack: Boolean) {
    var config by remember(tunnel) { mutableStateOf<Config?>(null) }
    var loadError by remember(tunnel) { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    val onCopied: (String) -> Unit = { app.showSnackbar(Strings.copiedToClipboard(it)) }

    // Reload when the tunnel's config changes (e.g. after editing).
    LaunchedEffect(tunnel, tunnel.config) {
        try {
            config = tunnel.getConfigAsync()
        } catch (e: Throwable) {
            loadError = ErrorMessages[e]
        }
    }
    // Statistics are refreshed every second while the screen is visible.
    LaunchedEffect(tunnel) {
        while (true) {
            if (tunnel.state == Tunnel.State.UP) {
                try {
                    app.tunnelManager.getTunnelStatistics(tunnel)
                } catch (_: Throwable) {
                }
            }
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tunnel.name) },
                navigationIcon = { if (showBack) BackButton { app.popBackStack() } },
                actions = {
                    TooltipIconButton(Icons.Default.QrCode2, Strings.SHOW_QR_CODE, enabled = config != null) { showQr = true }
                    TooltipIconButton(Icons.Default.Save, Strings.EXPORT_CONFIG, enabled = config != null) {
                        app.exportConfig(tunnel, config!!)
                    }
                    TooltipIconButton(Icons.Default.AddToHomeScreen, Strings.CREATE_SHORTCUT) { app.createDesktopShortcut(tunnel) }
                    TooltipIconButton(Icons.Default.Edit, Strings.EDIT) { app.navigate(Screen.TunnelEditor(tunnel)) }
                    TunnelToggle(tunnel, enabled = app.toolsAvailable) { app.toggleTunnel(tunnel, it) }
                },
            )
        },
    ) { padding ->
        val cfg = config
        if (cfg == null) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (loadError != null) Text(loadError!!, Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
                else CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            return@Scaffold
        }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                tunnel.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                InterfaceCard(tunnel, cfg.getInterface(), onCopied)
                if (tunnel.state == Tunnel.State.UP && !app.backend.hasPrivileges) {
                    if (app.backend.canRequestPrivileges) {
                        OutlinedButton(onClick = {
                            app.scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { app.backend.requestPrivileges() }
                                } catch (e: Throwable) {
                                    app.showSnackbar(ErrorMessages[e])
                                }
                            }
                        }) { Text(Strings.SHOW_STATISTICS) }
                    } else {
                        Text(
                            Strings.STATISTICS_NEED_ADMIN_WINDOWS,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                cfg.peers.forEach { PeerCard(tunnel, it, onCopied) }
            }
            VerticalScrollbarFor(scroll)
        }
    }

    if (showQr && config != null) {
        QrCodeDialog(tunnel.name, config!!) { showQr = false }
    }
}

@Composable
private fun InterfaceCard(tunnel: ObservableTunnel, iface: Interface, onCopied: (String) -> Unit) {
    SectionCard(Strings.INTERFACE_TITLE) {
        LabeledValue(Strings.NAME, tunnel.name, onCopied)
        LabeledValue(Strings.PUBLIC_KEY, iface.keyPair.publicKey.toBase64(), onCopied, monospace = true)
        if (iface.addresses.isNotEmpty())
            LabeledValue(Strings.ADDRESSES, Attribute.join(iface.addresses), onCopied)
        if (iface.dnsServers.isNotEmpty())
            LabeledValue(Strings.DNS_SERVERS, Attribute.join(iface.dnsServers.map { it.hostAddress }), onCopied)
        if (iface.dnsSearchDomains.isNotEmpty())
            LabeledValue(Strings.DNS_SEARCH_DOMAINS, Attribute.join(iface.dnsSearchDomains), onCopied)
        iface.listenPort.orElse(null)?.let { LabeledValue(Strings.LISTEN_PORT, it.toString(), onCopied) }
        iface.mtu.orElse(null)?.let { LabeledValue(Strings.MTU, it.toString(), onCopied) }
        val awg = listOf(
            Strings.JUNK_PACKET_COUNT to iface.junkPacketCount.map { it.toString() },
            Strings.JUNK_PACKET_MIN_SIZE to iface.junkPacketMinSize.map { it.toString() },
            Strings.JUNK_PACKET_MAX_SIZE to iface.junkPacketMaxSize.map { it.toString() },
            Strings.INIT_PACKET_JUNK_SIZE to iface.initPacketJunkSize.map { it.toString() },
            Strings.RESPONSE_PACKET_JUNK_SIZE to iface.responsePacketJunkSize.map { it.toString() },
            Strings.COOKIE_REPLY_PACKET_JUNK_SIZE to iface.cookieReplyPacketJunkSize.map { it.toString() },
            Strings.TRANSPORT_PACKET_JUNK_SIZE to iface.transportPacketJunkSize.map { it.toString() },
            Strings.INIT_PACKET_MAGIC_HEADER to iface.initPacketMagicHeader,
            Strings.RESPONSE_PACKET_MAGIC_HEADER to iface.responsePacketMagicHeader,
            Strings.UNDERLOAD_PACKET_MAGIC_HEADER to iface.underloadPacketMagicHeader,
            Strings.TRANSPORT_PACKET_MAGIC_HEADER to iface.transportPacketMagicHeader,
            Strings.SPECIAL_JUNK_I1 to iface.specialJunkI1,
            Strings.SPECIAL_JUNK_I2 to iface.specialJunkI2,
            Strings.SPECIAL_JUNK_I3 to iface.specialJunkI3,
            Strings.SPECIAL_JUNK_I4 to iface.specialJunkI4,
            Strings.SPECIAL_JUNK_I5 to iface.specialJunkI5,
            Strings.HEADER_PROTECTION_KEY to iface.headerProtectionKey.map { it.toBase64() },
            Strings.CONTENT_PADDING_ADDITION to iface.contentPaddingAddition,
            Strings.REKEY_AFTER_TIME to iface.rekeyAfterTime,
            Strings.REKEY_TIMEOUT to iface.rekeyTimeout,
            Strings.REJECT_AFTER_TIME to iface.rejectAfterTime,
            Strings.KEEPALIVE_TIMEOUT to iface.keepaliveTimeout,
            Strings.MAX_HANDSHAKE_ATTEMPTS to iface.maxHandshakeAttempts,
            Strings.RANDOM_TRAILERS to iface.randomTrailers,
            Strings.DISABLE_COOKIES to iface.disableCookies,
        )
        awg.forEach { (label, value) -> value.orElse(null)?.let { LabeledValue(label, it, onCopied) } }
    }
}

@Composable
private fun PeerCard(tunnel: ObservableTunnel, peer: Peer, onCopied: (String) -> Unit) {
    val stats = tunnel.statistics?.peer(peer.publicKey)
    SectionCard(Strings.PEER) {
        LabeledValue(Strings.PUBLIC_KEY, peer.publicKey.toBase64(), onCopied, monospace = true)
        if (peer.preSharedKey.isPresent) LabeledValue(Strings.PRE_SHARED_KEY, Strings.PRE_SHARED_KEY_ENABLED)
        if (peer.allowedIps.isNotEmpty()) LabeledValue(Strings.ALLOWED_IPS, Attribute.join(peer.allowedIps), onCopied)
        peer.endpoint.orElse(null)?.let { LabeledValue(Strings.ENDPOINT, it.toString(), onCopied) }
        peer.persistentKeepalive.orElse(null)?.let {
            LabeledValue(Strings.PERSISTENT_KEEPALIVE, "$it ${Strings.persistentKeepaliveSuffix(it.toIntOrNull() ?: 0)}")
        }
        if (stats != null && (stats.rxBytes != 0L || stats.txBytes != 0L)) {
            LabeledValue(
                Strings.TRANSFER,
                Strings.transferRxTx(QuantityFormatter.formatBytes(stats.rxBytes), QuantityFormatter.formatBytes(stats.txBytes)),
            )
        }
        if (stats != null && stats.latestHandshakeEpochMillis != 0L) {
            val ago = QuantityFormatter.formatEpochAgo(stats.latestHandshakeEpochMillis)
            LabeledValue(Strings.LATEST_HANDSHAKE, if (ago == Strings.NOW) ago else Strings.latestHandshakeAgo(ago))
        }
    }
}
