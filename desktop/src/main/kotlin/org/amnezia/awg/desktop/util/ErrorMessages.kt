/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import com.google.zxing.ChecksumException
import com.google.zxing.NotFoundException
import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.InetEndpoint
import org.amnezia.awg.config.InetNetwork
import org.amnezia.awg.config.ParseException
import org.amnezia.awg.crypto.Key
import org.amnezia.awg.crypto.KeyFormatException
import org.amnezia.awg.desktop.backend.RootHelperException
import java.net.InetAddress

/**
 * Turns throwables into the user facing messages of the Android application
 * (`ui/.../util/ErrorMessages.kt`), minus the Android specific backends.
 * Texts come from [Strings], so they follow the selected language.
 */
object ErrorMessages {

    operator fun get(throwable: Throwable?): String {
        if (throwable == null) return Strings.UNKNOWN_ERROR
        val rootCause = rootCause(throwable)
        return when {
            rootCause is BadConfigException -> {
                val reason = getBadConfigExceptionReason(rootCause)
                val context = if (rootCause.location == BadConfigException.Location.TOP_LEVEL) {
                    Strings.badConfigContextTopLevel(rootCause.section.getName())
                } else {
                    Strings.badConfigContext(rootCause.section.getName(), rootCause.location.getName())
                }
                Strings.badConfigError(reason, context) + getBadConfigExceptionExplanation(rootCause)
            }

            rootCause is BackendException ->
                when (rootCause.reason) {
                    BackendException.Reason.UNKNOWN_KERNEL_MODULE_NAME -> Strings.MODULE_VERSION_ERROR
                    BackendException.Reason.TUNNEL_MISSING_CONFIG -> Strings.NO_CONFIG_ERROR
                    else -> Strings.tunnelConfigError(rootCause.format.firstOrNull()?.toString() ?: Strings.UNKNOWN_ERROR)
                }

            rootCause is RootHelperException ->
                if (rootCause.code == "auth") rootCause.message ?: Strings.AUTH_FAILED
                else Strings.privilegedOperationFailed(rootCause.code, rootCause.message.orEmpty())

            rootCause is NotFoundException -> Strings.ERROR_NO_QR_FOUND
            rootCause is ChecksumException -> Strings.ERROR_QR_CHECKSUM
            rootCause.localizedMessage != null -> rootCause.localizedMessage!!
            else -> Strings.unknownErrorType(rootCause.javaClass.simpleName)
        }
    }

    private fun getBadConfigExceptionExplanation(bce: BadConfigException): String {
        val cause = bce.cause
        return when {
            cause is KeyFormatException && cause.type == KeyFormatException.Type.LENGTH -> when (cause.format) {
                Key.Format.BASE64 -> Strings.KEY_LENGTH_EXPLANATION_BASE64
                Key.Format.BINARY -> Strings.KEY_LENGTH_EXPLANATION_BINARY
                Key.Format.HEX -> Strings.KEY_LENGTH_EXPLANATION_HEX
                else -> ""
            }

            cause is ParseException -> cause.localizedMessage?.let { ": $it" } ?: ""
            bce.location == BadConfigException.Location.LISTEN_PORT -> Strings.BAD_CONFIG_EXPLANATION_UDP_PORT
            bce.location == BadConfigException.Location.MTU -> Strings.BAD_CONFIG_EXPLANATION_POSITIVE_NUMBER
            bce.location == BadConfigException.Location.PERSISTENT_KEEPALIVE -> Strings.BAD_CONFIG_EXPLANATION_PKA
            else -> ""
        }
    }

    private fun getBadConfigExceptionReason(bce: BadConfigException): String {
        val cause = bce.cause
        return when {
            cause is KeyFormatException -> when (cause.type) {
                KeyFormatException.Type.CONTENTS -> Strings.KEY_CONTENTS_ERROR
                KeyFormatException.Type.LENGTH -> Strings.KEY_LENGTH_ERROR
                else -> Strings.badConfigReason("invalid_key")
            }

            cause is ParseException -> {
                val type = when (cause.parsingClass) {
                    InetAddress::class.java -> Strings.PARSE_ERROR_INET_ADDRESS
                    InetEndpoint::class.java -> Strings.PARSE_ERROR_INET_ENDPOINT
                    InetNetwork::class.java -> Strings.PARSE_ERROR_INET_NETWORK
                    Int::class.java, Integer::class.java -> Strings.PARSE_ERROR_INTEGER
                    else -> Strings.PARSE_ERROR_GENERIC
                }
                Strings.parseErrorReason(type, cause.text.toString())
            }

            else -> Strings.badConfigReason(bce.reason.name.lowercase())
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
