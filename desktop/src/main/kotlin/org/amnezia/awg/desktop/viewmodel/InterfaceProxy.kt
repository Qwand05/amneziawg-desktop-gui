/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.amnezia.awg.config.Attribute
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.Interface
import org.amnezia.awg.crypto.Key
import org.amnezia.awg.crypto.KeyFormatException
import org.amnezia.awg.crypto.KeyPair

/**
 * Editable representation of the `[Interface]` section of a configuration.
 *
 * Port of the Android `InterfaceProxy`: `Parcelable` and data binding were
 * dropped in favour of Compose snapshot state. Every field is kept as the raw
 * string the user typed, exactly like the Android editor does, so that values
 * such as `10.0.0.1, 10.0.0.2` round-trip unchanged.
 */
class InterfaceProxy {
    /** Application packages excluded/included on Android; carried over untouched. */
    val excludedApplications = mutableStateListOf<String>()
    val includedApplications = mutableStateListOf<String>()

    var addresses: String by mutableStateOf("")
    private var dnsServersState: String by mutableStateOf("")

    /** DNS servers field; notifies [dnsListeners] so peers can track their routes. */
    var dnsServers: String
        get() = dnsServersState
        set(value) {
            dnsServersState = value
            dnsListeners.forEach { it(value) }
        }

    private val dnsListeners = mutableListOf<(String) -> Unit>()

    fun addDnsListener(listener: (String) -> Unit) {
        dnsListeners.add(listener)
    }

    fun removeDnsListener(listener: (String) -> Unit) {
        dnsListeners.remove(listener)
    }
    var listenPort: String by mutableStateOf("")
    var mtu: String by mutableStateOf("")
    var junkPacketCount: String by mutableStateOf("")
    var junkPacketMinSize: String by mutableStateOf("")
    var junkPacketMaxSize: String by mutableStateOf("")
    var initPacketJunkSize: String by mutableStateOf("")
    var responsePacketJunkSize: String by mutableStateOf("")
    var cookieReplyPacketJunkSize: String by mutableStateOf("")
    var transportPacketJunkSize: String by mutableStateOf("")
    var initPacketMagicHeader: String by mutableStateOf("")
    var responsePacketMagicHeader: String by mutableStateOf("")
    var underloadPacketMagicHeader: String by mutableStateOf("")
    var transportPacketMagicHeader: String by mutableStateOf("")
    var specialJunkI1: String by mutableStateOf("")
    var specialJunkI2: String by mutableStateOf("")
    var specialJunkI3: String by mutableStateOf("")
    var specialJunkI4: String by mutableStateOf("")
    var specialJunkI5: String by mutableStateOf("")
    var headerProtectionKey: String by mutableStateOf("")
    var contentPaddingAddition: String by mutableStateOf("")
    var rekeyAfterTime: String by mutableStateOf("")
    var rekeyTimeout: String by mutableStateOf("")
    var rejectAfterTime: String by mutableStateOf("")
    var keepaliveTimeout: String by mutableStateOf("")
    var maxHandshakeAttempts: String by mutableStateOf("")
    var randomTrailers: String by mutableStateOf("")
    var disableCookies: String by mutableStateOf("")
    var privateKey: String by mutableStateOf("")

    /** Public key derived from [privateKey], empty when it cannot be derived. */
    val publicKey: String
        get() = try {
            KeyPair(Key.fromBase64(privateKey)).publicKey.toBase64()
        } catch (_: KeyFormatException) {
            ""
        }

    constructor()

    constructor(other: Interface) {
        addresses = Attribute.join(other.addresses)
        val dnsServerStrings = other.dnsServers.map { it.hostAddress }.plus(other.dnsSearchDomains)
        dnsServers = Attribute.join(dnsServerStrings)
        excludedApplications.addAll(other.excludedApplications)
        includedApplications.addAll(other.includedApplications)
        listenPort = other.listenPort.map { it.toString() }.orElse("")
        mtu = other.mtu.map { it.toString() }.orElse("")
        junkPacketCount = other.junkPacketCount.map { it.toString() }.orElse("")
        junkPacketMinSize = other.junkPacketMinSize.map { it.toString() }.orElse("")
        junkPacketMaxSize = other.junkPacketMaxSize.map { it.toString() }.orElse("")
        initPacketJunkSize = other.initPacketJunkSize.map { it.toString() }.orElse("")
        responsePacketJunkSize = other.responsePacketJunkSize.map { it.toString() }.orElse("")
        cookieReplyPacketJunkSize = other.cookieReplyPacketJunkSize.map { it.toString() }.orElse("")
        transportPacketJunkSize = other.transportPacketJunkSize.map { it.toString() }.orElse("")
        initPacketMagicHeader = other.initPacketMagicHeader.orElse("")
        responsePacketMagicHeader = other.responsePacketMagicHeader.orElse("")
        underloadPacketMagicHeader = other.underloadPacketMagicHeader.orElse("")
        transportPacketMagicHeader = other.transportPacketMagicHeader.orElse("")
        specialJunkI1 = other.specialJunkI1.orElse("")
        specialJunkI2 = other.specialJunkI2.orElse("")
        specialJunkI3 = other.specialJunkI3.orElse("")
        specialJunkI4 = other.specialJunkI4.orElse("")
        specialJunkI5 = other.specialJunkI5.orElse("")
        headerProtectionKey = other.headerProtectionKey.map { it.toBase64() }.orElse("")
        contentPaddingAddition = other.contentPaddingAddition.orElse("")
        rekeyAfterTime = other.rekeyAfterTime.orElse("")
        rekeyTimeout = other.rekeyTimeout.orElse("")
        rejectAfterTime = other.rejectAfterTime.orElse("")
        keepaliveTimeout = other.keepaliveTimeout.orElse("")
        maxHandshakeAttempts = other.maxHandshakeAttempts.orElse("")
        randomTrailers = other.randomTrailers.orElse("")
        disableCookies = other.disableCookies.orElse("")
        privateKey = other.keyPair.privateKey.toBase64()
    }

    fun generateKeyPair() {
        privateKey = KeyPair().privateKey.toBase64()
    }

    @Throws(BadConfigException::class)
    fun resolve(): Interface {
        val builder = Interface.Builder()
        if (addresses.isNotEmpty()) builder.parseAddresses(addresses)
        if (dnsServers.isNotEmpty()) builder.parseDnsServers(dnsServers)
        if (excludedApplications.isNotEmpty()) builder.excludeApplications(excludedApplications)
        if (includedApplications.isNotEmpty()) builder.includeApplications(includedApplications)
        if (listenPort.isNotEmpty()) builder.parseListenPort(listenPort)
        if (mtu.isNotEmpty()) builder.parseMtu(mtu)
        if (junkPacketCount.isNotEmpty()) builder.parseJunkPacketCount(junkPacketCount)
        if (junkPacketMinSize.isNotEmpty()) builder.parseJunkPacketMinSize(junkPacketMinSize)
        if (junkPacketMaxSize.isNotEmpty()) builder.parseJunkPacketMaxSize(junkPacketMaxSize)
        if (initPacketJunkSize.isNotEmpty()) builder.parseInitPacketJunkSize(initPacketJunkSize)
        if (responsePacketJunkSize.isNotEmpty()) builder.parseResponsePacketJunkSize(responsePacketJunkSize)
        if (cookieReplyPacketJunkSize.isNotEmpty()) builder.parseCookieReplyPacketJunkSize(cookieReplyPacketJunkSize)
        if (transportPacketJunkSize.isNotEmpty()) builder.parseTransportPacketJunkSize(transportPacketJunkSize)
        if (initPacketMagicHeader.isNotEmpty()) builder.parseInitPacketMagicHeader(initPacketMagicHeader)
        if (responsePacketMagicHeader.isNotEmpty()) builder.parseResponsePacketMagicHeader(responsePacketMagicHeader)
        if (underloadPacketMagicHeader.isNotEmpty()) builder.parseUnderloadPacketMagicHeader(underloadPacketMagicHeader)
        if (transportPacketMagicHeader.isNotEmpty()) builder.parseTransportPacketMagicHeader(transportPacketMagicHeader)
        if (specialJunkI1.isNotEmpty()) builder.parseSpecialJunkI1(specialJunkI1)
        if (specialJunkI2.isNotEmpty()) builder.parseSpecialJunkI2(specialJunkI2)
        if (specialJunkI3.isNotEmpty()) builder.parseSpecialJunkI3(specialJunkI3)
        if (specialJunkI4.isNotEmpty()) builder.parseSpecialJunkI4(specialJunkI4)
        if (specialJunkI5.isNotEmpty()) builder.parseSpecialJunkI5(specialJunkI5)
        if (headerProtectionKey.isNotEmpty()) builder.parseHeaderProtectionKey(headerProtectionKey)
        if (contentPaddingAddition.isNotEmpty()) builder.parseContentPaddingAddition(contentPaddingAddition)
        if (rekeyAfterTime.isNotEmpty()) builder.parseRekeyAfterTime(rekeyAfterTime)
        if (rekeyTimeout.isNotEmpty()) builder.parseRekeyTimeout(rekeyTimeout)
        if (rejectAfterTime.isNotEmpty()) builder.parseRejectAfterTime(rejectAfterTime)
        if (keepaliveTimeout.isNotEmpty()) builder.parseKeepaliveTimeout(keepaliveTimeout)
        if (maxHandshakeAttempts.isNotEmpty()) builder.parseMaxHandshakeAttempts(maxHandshakeAttempts)
        if (randomTrailers.isNotEmpty()) builder.parseRandomTrailers(randomTrailers)
        if (disableCookies.isNotEmpty()) builder.parseDisableCookies(disableCookies)
        if (privateKey.isNotEmpty()) builder.parsePrivateKey(privateKey)
        return builder.build()
    }
}
