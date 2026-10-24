/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.model.AppState
import org.amnezia.awg.desktop.model.ObservableTunnel
import org.amnezia.awg.desktop.model.Screen
import org.amnezia.awg.desktop.util.ErrorMessages
import org.amnezia.awg.desktop.util.Strings
import org.amnezia.awg.desktop.viewmodel.ConfigProxy
import org.amnezia.awg.desktop.viewmodel.InterfaceProxy
import org.amnezia.awg.desktop.viewmodel.PeerProxy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelEditorScreen(app: AppState, tunnel: ObservableTunnel?) {
    var proxy by remember(tunnel) { mutableStateOf<ConfigProxy?>(if (tunnel == null) ConfigProxy() else null) }
    var name by remember(tunnel) { mutableStateOf(tunnel?.name ?: "") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(tunnel) {
        if (tunnel == null) {
            // New tunnels start with a fresh key pair, like the Android editor.
            proxy!!.iface.generateKeyPair()
            return@LaunchedEffect
        }
        try {
            proxy = ConfigProxy(tunnel.getConfigAsync())
        } catch (e: Throwable) {
            app.showSnackbar(ErrorMessages[e])
            proxy = ConfigProxy()
        }
    }

    fun save() {
        val current = proxy ?: return
        val newConfig: Config = try {
            current.resolve()
        } catch (e: Throwable) {
            app.showSnackbar(ErrorMessages[e])
            return
        }
        saving = true
        app.scope.launch {
            try {
                when {
                    tunnel == null -> {
                        val created = app.tunnelManager.create(name, newConfig)
                        app.showSnackbar(Strings.tunnelCreateSuccess(created.name))
                        app.replace(Screen.TunnelDetail(created))
                    }

                    tunnel.name != name -> {
                        try {
                            tunnel.setNameAsync(name)
                        } catch (e: Throwable) {
                            app.showSnackbar(Strings.tunnelRenameError(ErrorMessages[e]))
                            return@launch
                        }
                        tunnel.setConfigAsync(newConfig)
                        app.showSnackbar(Strings.tunnelRenameSuccess(tunnel.name))
                        app.popBackStack()
                    }

                    else -> {
                        tunnel.setConfigAsync(newConfig)
                        app.showSnackbar(Strings.configSaveSuccess(tunnel.name))
                        app.popBackStack()
                    }
                }
            } catch (e: Throwable) {
                val error = ErrorMessages[e]
                app.showSnackbar(
                    if (tunnel == null) Strings.tunnelCreateError(error)
                    else Strings.configSaveError(tunnel.name, error)
                )
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tunnel?.name ?: Strings.CREATE_ACTIVITY_TITLE) },
                navigationIcon = { TooltipIconButton(Icons.Default.Close, Strings.CANCEL) { app.popBackStack() } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.padding(12.dp))
                    else TooltipIconButton(
                        Icons.Default.Save,
                        Strings.SAVE,
                        enabled = proxy != null && !Tunnel.isNameInvalid(name),
                        onClick = ::save,
                    )
                },
            )
        },
    ) { padding ->
        val current = proxy
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InterfaceEditor(current.iface, name, onNameChange = { name = it })
                current.peers.forEachIndexed { index, peer ->
                    PeerEditor(peer, index) { peer.unbind() }
                }
                OutlinedButton(onClick = { current.addPeer() }) {
                    Icon(Icons.Default.Add, null)
                    Text(Strings.ADD_PEER, Modifier.padding(start = 8.dp))
                }
            }
            VerticalScrollbarFor(scroll)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    monospace: Boolean = false,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        readOnly = readOnly,
        textStyle = if (monospace) TextStyle(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        trailingIcon = trailing,
    )
}

@Composable
private fun InterfaceEditor(iface: InterfaceProxy, name: String, onNameChange: (String) -> Unit) {
    var showAdvanced by remember { mutableStateOf(iface.hasAdvancedValues()) }
    SectionCard(Strings.INTERFACE_TITLE) {
        Field(Strings.NAME, name, { onNameChange(it.filter(::isNameChar).take(Tunnel.NAME_MAX_LENGTH)) })
        Field(
            Strings.PRIVATE_KEY, iface.privateKey, { iface.privateKey = it }, monospace = true,
            trailing = { TooltipIconButton(Icons.Default.Autorenew, Strings.GENERATE_NEW_PRIVATE_KEY) { iface.generateKeyPair() } },
        )
        Field(Strings.PUBLIC_KEY, iface.publicKey, {}, hint = Strings.HINT_GENERATED, monospace = true, readOnly = true)
        Field(Strings.ADDRESSES, iface.addresses, { iface.addresses = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(Strings.LISTEN_PORT, iface.listenPort, { iface.listenPort = it }, Modifier.weight(1f), hint = Strings.HINT_RANDOM)
            Field(Strings.MTU, iface.mtu, { iface.mtu = it }, Modifier.weight(1f), hint = Strings.HINT_AUTOMATIC)
        }
        Field(Strings.DNS_SERVERS, iface.dnsServers, { iface.dnsServers = it })

        Row(
            Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Strings.AWG_PARAMETERS, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (showAdvanced) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(Strings.JUNK_PACKET_COUNT, iface.junkPacketCount, { iface.junkPacketCount = it }, Modifier.weight(1f))
                Field(Strings.JUNK_PACKET_MIN_SIZE, iface.junkPacketMinSize, { iface.junkPacketMinSize = it }, Modifier.weight(1f))
                Field(Strings.JUNK_PACKET_MAX_SIZE, iface.junkPacketMaxSize, { iface.junkPacketMaxSize = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(Strings.INIT_PACKET_JUNK_SIZE, iface.initPacketJunkSize, { iface.initPacketJunkSize = it }, Modifier.weight(1f))
                Field(Strings.RESPONSE_PACKET_JUNK_SIZE, iface.responsePacketJunkSize, { iface.responsePacketJunkSize = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(Strings.COOKIE_REPLY_PACKET_JUNK_SIZE, iface.cookieReplyPacketJunkSize, { iface.cookieReplyPacketJunkSize = it }, Modifier.weight(1f))
                Field(Strings.TRANSPORT_PACKET_JUNK_SIZE, iface.transportPacketJunkSize, { iface.transportPacketJunkSize = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(Strings.INIT_PACKET_MAGIC_HEADER, iface.initPacketMagicHeader, { iface.initPacketMagicHeader = it }, Modifier.weight(1f))
                Field(Strings.RESPONSE_PACKET_MAGIC_HEADER, iface.responsePacketMagicHeader, { iface.responsePacketMagicHeader = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(Strings.UNDERLOAD_PACKET_MAGIC_HEADER, iface.underloadPacketMagicHeader, { iface.underloadPacketMagicHeader = it }, Modifier.weight(1f))
                Field(Strings.TRANSPORT_PACKET_MAGIC_HEADER, iface.transportPacketMagicHeader, { iface.transportPacketMagicHeader = it }, Modifier.weight(1f))
            }
            Field(Strings.SPECIAL_JUNK_I1, iface.specialJunkI1, { iface.specialJunkI1 = it }, monospace = true)
            Field(Strings.SPECIAL_JUNK_I2, iface.specialJunkI2, { iface.specialJunkI2 = it }, monospace = true)
            Field(Strings.SPECIAL_JUNK_I3, iface.specialJunkI3, { iface.specialJunkI3 = it }, monospace = true)
            Field(Strings.SPECIAL_JUNK_I4, iface.specialJunkI4, { iface.specialJunkI4 = it }, monospace = true)
            Field(Strings.SPECIAL_JUNK_I5, iface.specialJunkI5, { iface.specialJunkI5 = it }, monospace = true)
            Field(Strings.HEADER_PROTECTION_KEY, iface.headerProtectionKey, { iface.headerProtectionKey = it }, monospace = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(Strings.CONTENT_PADDING_ADDITION, iface.contentPaddingAddition, { iface.contentPaddingAddition = it }, Modifier.weight(1f))
                Field(Strings.RANDOM_TRAILERS, iface.randomTrailers, { iface.randomTrailers = it }, Modifier.weight(1f))
                Field(Strings.DISABLE_COOKIES, iface.disableCookies, { iface.disableCookies = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(Strings.REKEY_AFTER_TIME, iface.rekeyAfterTime, { iface.rekeyAfterTime = it }, Modifier.weight(1f))
                Field(Strings.REKEY_TIMEOUT, iface.rekeyTimeout, { iface.rekeyTimeout = it }, Modifier.weight(1f))
                Field(Strings.REJECT_AFTER_TIME, iface.rejectAfterTime, { iface.rejectAfterTime = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(Strings.KEEPALIVE_TIMEOUT, iface.keepaliveTimeout, { iface.keepaliveTimeout = it }, Modifier.weight(1f))
                Field(Strings.MAX_HANDSHAKE_ATTEMPTS, iface.maxHandshakeAttempts, { iface.maxHandshakeAttempts = it }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PeerEditor(peer: PeerProxy, index: Int, onDelete: () -> Unit) {
    SectionCard(
        "${Strings.PEER} ${index + 1}",
        actions = { TooltipIconButton(Icons.Default.Delete, Strings.DELETE, onClick = onDelete) },
    ) {
        Field(Strings.PUBLIC_KEY, peer.publicKey, { peer.publicKey = it }, monospace = true)
        Field(Strings.PRE_SHARED_KEY, peer.preSharedKey, { peer.preSharedKey = it }, hint = Strings.HINT_OPTIONAL, monospace = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(Strings.ENDPOINT, peer.endpoint, { peer.endpoint = it }, Modifier.weight(2f))
            Field(
                Strings.PERSISTENT_KEEPALIVE, peer.persistentKeepalive, { peer.persistentKeepalive = it },
                Modifier.weight(1f), hint = Strings.HINT_OPTIONAL_DISCOURAGED,
            )
        }
        Field(Strings.ALLOWED_IPS, peer.allowedIps, { peer.allowedIps = it }, singleLine = false)
        if (peer.isAbleToExcludePrivateIps) {
            Row(
                Modifier.clickable { peer.setExcludingPrivateIps(!peer.isExcludingPrivateIps) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(peer.isExcludingPrivateIps, onCheckedChange = { peer.setExcludingPrivateIps(it) })
                Text(Strings.EXCLUDE_PRIVATE_IPS)
            }
        }
    }
}

/** Expand the obfuscation section by default when the config already uses it. */
private fun InterfaceProxy.hasAdvancedValues(): Boolean = listOf(
    junkPacketCount, junkPacketMinSize, junkPacketMaxSize, initPacketJunkSize, responsePacketJunkSize,
    cookieReplyPacketJunkSize, transportPacketJunkSize, initPacketMagicHeader, responsePacketMagicHeader,
    underloadPacketMagicHeader, transportPacketMagicHeader, specialJunkI1, specialJunkI2, specialJunkI3,
    specialJunkI4, specialJunkI5, headerProtectionKey, contentPaddingAddition, rekeyAfterTime, rekeyTimeout,
    rejectAfterTime, keepaliveTimeout, maxHandshakeAttempts, randomTrailers, disableCookies,
).any { it.isNotEmpty() }
