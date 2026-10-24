/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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
import org.amnezia.awg.desktop.util.FileDialogs
import org.amnezia.awg.desktop.util.QuantityFormatter
import org.amnezia.awg.desktop.util.Strings
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

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
                        exportConfig(app, tunnel, config!!)
                    }
                    TooltipIconButton(Icons.Default.Edit, Strings.EDIT) { app.navigate(Screen.TunnelEditor(tunnel)) }
                    TunnelToggle(tunnel) { app.toggleTunnel(tunnel, it) }
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
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            tunnel.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            InterfaceCard(tunnel, cfg.getInterface(), onCopied)
            if (tunnel.state == Tunnel.State.UP && !app.backend.hasPrivileges) {
                OutlinedButton(onClick = {
                    app.scope.launch {
                        try {
                            withContext(Dispatchers.IO) { app.backend.helper() }
                        } catch (e: Throwable) {
                            app.showSnackbar(ErrorMessages[e])
                        }
                    }
                }) { Text(Strings.SHOW_STATISTICS) }
            }
            cfg.peers.forEach { PeerCard(tunnel, it, onCopied) }
        }
    }

    if (showQr && config != null) {
        val image = remember(config) { qrCode(config!!.toAwgQuickString()) }
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text(tunnel.name) },
            text = { Image(image, contentDescription = Strings.SHOW_QR_CODE, modifier = Modifier.size(360.dp)) },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text(Strings.CLOSE) } },
        )
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
            LabeledValue(Strings.LATEST_HANDSHAKE, Strings.latestHandshakeAgo(QuantityFormatter.formatEpochAgo(stats.latestHandshakeEpochMillis)))
        }
    }
}

private fun exportConfig(app: AppState, tunnel: ObservableTunnel, config: Config) {
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
        app.showSnackbar(Strings.zipExportSuccess(target.absolutePath))
    } catch (e: Throwable) {
        app.showSnackbar(Strings.ZIP_EXPORT_ERROR.format(ErrorMessages[e]))
    }
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
