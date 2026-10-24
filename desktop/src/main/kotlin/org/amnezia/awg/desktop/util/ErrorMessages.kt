/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import com.google.zxing.ChecksumException
import com.google.zxing.NotFoundException
import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.ParseException
import org.amnezia.awg.crypto.Key
import org.amnezia.awg.crypto.KeyFormatException
import org.amnezia.awg.desktop.backend.RootHelperException
import java.net.InetAddress

/**
 * Turns throwables into the user facing messages of the Android application
 * (`ui/.../util/ErrorMessages.kt`), minus the Android specific backends.
 */
object ErrorMessages {

    private val BCE_REASON_MAP = mapOf(
        BadConfigException.Reason.INVALID_KEY to "Invalid key",
        BadConfigException.Reason.INVALID_NUMBER to "Invalid number",
        BadConfigException.Reason.INVALID_VALUE to "Invalid value",
        BadConfigException.Reason.MISSING_ATTRIBUTE to "Missing attribute",
        BadConfigException.Reason.MISSING_SECTION to "Missing section",
        BadConfigException.Reason.SYNTAX_ERROR to "Syntax error",
        BadConfigException.Reason.UNKNOWN_ATTRIBUTE to "Unknown attribute",
        BadConfigException.Reason.UNKNOWN_SECTION to "Unknown section",
    )

    private val KFE_FORMAT_MAP = mapOf(
        Key.Format.BASE64 to ": AmneziaWG base64 keys must be 44 characters (32 bytes)",
        Key.Format.BINARY to ": AmneziaWG keys must be 32 bytes",
        Key.Format.HEX to ": AmneziaWG hex keys must be 64 characters (32 bytes)",
    )

    private val KFE_TYPE_MAP = mapOf(
        KeyFormatException.Type.CONTENTS to "Bad characters in key",
        KeyFormatException.Type.LENGTH to "Incorrect key length",
    )

    private val PE_CLASS_MAP = mapOf(
        InetAddress::class.java to "IP address",
        org.amnezia.awg.config.InetEndpoint::class.java to "endpoint",
        org.amnezia.awg.config.InetNetwork::class.java to "IP network",
        Int::class.java to "number",
    )

    operator fun get(throwable: Throwable?): String {
        if (throwable == null) return Strings.UNKNOWN_ERROR
        val rootCause = rootCause(throwable)
        return when {
            rootCause is BadConfigException -> {
                val reason = getBadConfigExceptionReason(rootCause)
                val context = if (rootCause.location == BadConfigException.Location.TOP_LEVEL) {
                    "${rootCause.section.name}"
                } else {
                    "${rootCause.section.name}'s ${rootCause.location.name}"
                }
                val explanation = getBadConfigExceptionExplanation(rootCause)
                "$reason in $context$explanation"
            }

            rootCause is BackendException ->
                when (rootCause.reason) {
                    BackendException.Reason.UNKNOWN_KERNEL_MODULE_NAME ->
                        "Unable to determine kernel module version"
                    BackendException.Reason.TUNNEL_MISSING_CONFIG ->
                        "Trying to bring up a tunnel with no config"
                    else -> "Unable to configure tunnel (${rootCause.format.firstOrNull() ?: "unknown error"})"
                }

            rootCause is RootHelperException ->
                "Privileged operation failed (${rootCause.code}): ${rootCause.message}"

            rootCause is NotFoundException -> "QR code not found in image"
            rootCause is ChecksumException -> "QR code checksum verification failed"
            rootCause.localizedMessage != null -> rootCause.localizedMessage!!
            else -> "Unknown “${rootCause.javaClass.simpleName}” error"
        }
    }

    private fun getBadConfigExceptionExplanation(bce: BadConfigException): String {
        val cause = bce.cause
        return when {
            cause is KeyFormatException && cause.type == KeyFormatException.Type.LENGTH ->
                KFE_FORMAT_MAP[cause.format] ?: ""

            cause is ParseException -> cause.localizedMessage?.let { ": $it" } ?: ""

            bce.location == BadConfigException.Location.LISTEN_PORT ->
                ": Must be a valid UDP port number"

            bce.location == BadConfigException.Location.MTU -> ": Must be positive"

            bce.location == BadConfigException.Location.PERSISTENT_KEEPALIVE ->
                ": Must be positive and no more than 65535"

            else -> ""
        }
    }

    private fun getBadConfigExceptionReason(bce: BadConfigException): String {
        val cause = bce.cause
        return when {
            cause is KeyFormatException -> KFE_TYPE_MAP[cause.type] ?: "Invalid key"
            cause is ParseException -> {
                val type = PE_CLASS_MAP[cause.parsingClass] ?: "string"
                "Cannot parse $type “${cause.text}”"
            }
            else -> BCE_REASON_MAP[bce.reason] ?: "Invalid value"
        }
    }

    private fun rootCause(throwable: Throwable): Throwable {
        var cause = throwable
        while (cause.cause != null) {
            if (cause is BadConfigException || cause is BackendException ||
                cause is RootHelperException
            ) break
            cause = cause.cause!!
        }
        return cause
    }
}
