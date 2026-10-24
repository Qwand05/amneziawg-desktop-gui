/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

/**
 * User visible strings of the desktop GUI.
 *
 * The English values are taken verbatim from
 * `ui/src/main/res/values/strings.xml` so that the port reads exactly like the
 * Android application.
 */
object Strings {
    const val APP_NAME = "AmneziaWG"


    // Tunnel list
    const val TUNNEL_LIST_PLACEHOLDER = "Add a tunnel using the button below"
    const val TUNNEL_STATUS_CONNECTED = "Connected"
    const val TUNNEL_STATUS_CONNECTING = "Connecting…"
    const val SETTINGS = "Settings"
    const val SELECT_ALL = "Select all"
    const val SELECT = "Select"
    const val DESELECT = "Deselect"
    const val SELECTED = "Selected"
    const val CLEAR_SELECTION = "Clear selection"
    const val CONNECT = "Connect"
    const val DISCONNECT = "Disconnect"
    const val SHOW_DETAILS = "Show details"
    fun deleteSelected(count: Int) = "Delete $count tunnels"
    const val DELETE = "Delete"
    const val EDIT = "Edit"
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val BACK = "Back"
    const val CLOSE = "Close"
    const val SELECT_TUNNEL_HINT = "Select a tunnel to see its details"
    const val TOOLS_MISSING = "awg-quick was not found. Install amneziawg-tools to bring tunnels up."
    const val REFRESH = "Refresh"

    fun deleteTitle(count: Int) = plural(count, "%d tunnel selected", "%d tunnels selected")
    fun deleteSuccess(count: Int) =
        plural(count, "Successfully deleted %d tunnel", "Successfully deleted %d tunnels")
    fun deleteError(count: Int, error: String) =
        plural(count, "Unable to delete %d tunnel: %s", "Unable to delete %d tunnels: %s")
            .replace("%d", count.toString()).replace("%s", error)

    fun deleteConfirmTitle(count: Int) = plural(count, "Delete this tunnel?", "Delete $count tunnels?")

    private fun plural(count: Int, one: String, other: String) = if (count == 1) one else other

    // Add-tunnels sheet / import
    const val CREATE_FROM_FILE = "Import from file or archive"
    const val CREATE_FROM_QR_CODE = "Scan from QR code"
    const val CREATE_FROM_QR_IMAGE = "Import from QR code image"
    const val CREATE_EMPTY = "Create from scratch"
    const val CREATE_FROM_CLIPBOARD = "Import from clipboard"
    const val IMPORT_DISCLAIMER =
        "Ensure that you obtained the configuration file from a trusted source.\n\n" +
            "Official Amnezia services are available only at amnezia.org"
    const val IMPORT_FROM_QR_CODE = "Import Tunnel from QR Code"
    const val TUNNEL_NAME = "Tunnel Name"
    const val CREATE_TUNNEL = "Create Tunnel"
    const val NO_CAMERA = "No camera detected"
    const val FFMPEG_MISSING = "Install ffmpeg to use the camera"
    const val QR_SCAN_HINT = "Point the camera at a configuration QR code"
    const val QR_NOT_A_CONFIG = "This QR code is not an AmneziaWG configuration"
    const val CAMERA_ERROR = "Unable to use the camera: %s"
    const val QR_CODE_HINT = "Tip: generate with `qrencode -t ansiutf8 < tunnel.conf`."
    const val IMPORT_ERROR = "Unable to import tunnel: %s"
    const val BAD_EXTENSION_ERROR = "File must be .conf or .zip"
    const val ILLEGAL_FILENAME_ERROR = "Illegal file name “%s”"
    const val NO_CONFIGS_ERROR = "No configurations found"
    const val NO_TUNNELS_ERROR = "No tunnels exist"
    const val IMPORT_SUCCESS = "Imported “%s”"
    fun importTotalSuccess(count: Int) =
        plural(count, "Imported %d tunnel", "Imported %d tunnels").replace("%d", count.toString())
    fun importPartialSuccess(done: Int, total: Int) = "Imported $done of $total tunnels"

    // Tunnel detail
    const val INTERFACE_TITLE = "Interface"
    const val PEER = "Peer"
    const val NAME = "Name"
    const val PUBLIC_KEY = "Public key"
    const val PRIVATE_KEY = "Private key"
    const val ADDRESSES = "Addresses"
    const val DNS_SERVERS = "DNS servers"
    const val DNS_SEARCH_DOMAINS = "Search domains"
    const val LISTEN_PORT = "Listen port"
    const val MTU = "MTU"
    const val ALLOWED_IPS = "Allowed IPs"
    const val ENDPOINT = "Endpoint"
    const val PERSISTENT_KEEPALIVE = "Persistent keepalive"
    const val PRE_SHARED_KEY = "Pre-shared key"
    const val PRE_SHARED_KEY_ENABLED = "enabled"
    const val TRANSFER = "Transfer"
    const val LATEST_HANDSHAKE = "Latest handshake"
    const val APPLICATIONS = "Applications"
    const val SHOW_QR_CODE = "Show QR code"
    const val EXPORT_CONFIG = "Export configuration file"
    const val SHOW_STATISTICS = "Show live statistics (requires authentication)"
    fun excludedApplications(count: Int) = "$count excluded"
    fun includedApplications(count: Int) = "$count included"
    fun transferRxTx(rx: String, tx: String) = "rx: $rx, tx: $tx"
    fun latestHandshakeAgo(ago: String) = "$ago ago"

    // Editor
    const val JUNK_PACKET_COUNT = "Junk packet count"
    const val JUNK_PACKET_MIN_SIZE = "Junk packet minimum size"
    const val JUNK_PACKET_MAX_SIZE = "Junk packet maximum size"
    const val INIT_PACKET_JUNK_SIZE = "Init packet junk size"
    const val RESPONSE_PACKET_JUNK_SIZE = "Response packet junk size"
    const val COOKIE_REPLY_PACKET_JUNK_SIZE = "Cookie reply packet junk size"
    const val TRANSPORT_PACKET_JUNK_SIZE = "Transport packet junk size"
    const val INIT_PACKET_MAGIC_HEADER = "Init packet magic header"
    const val RESPONSE_PACKET_MAGIC_HEADER = "Response packet magic header"
    const val UNDERLOAD_PACKET_MAGIC_HEADER = "Underload packet magic header"
    const val TRANSPORT_PACKET_MAGIC_HEADER = "Transport packet magic header"
    const val SPECIAL_JUNK_I1 = "Special junk I1"
    const val SPECIAL_JUNK_I2 = "Special junk I2"
    const val SPECIAL_JUNK_I3 = "Special junk I3"
    const val SPECIAL_JUNK_I4 = "Special junk I4"
    const val SPECIAL_JUNK_I5 = "Special junk I5"
    const val HEADER_PROTECTION_KEY = "Header protection key"
    const val CONTENT_PADDING_ADDITION = "Content padding addition"
    const val REKEY_AFTER_TIME = "Rekey after time"
    const val REKEY_TIMEOUT = "Rekey timeout"
    const val REJECT_AFTER_TIME = "Reject after time"
    const val KEEPALIVE_TIMEOUT = "Keepalive timeout"
    const val MAX_HANDSHAKE_ATTEMPTS = "Max handshake attempts"
    const val RANDOM_TRAILERS = "Random trailers"
    const val DISABLE_COOKIES = "Disable cookies"
    const val ADD_PEER = "Add peer"
    const val AWG_PARAMETERS = "AmneziaWG obfuscation parameters"
    const val EXCLUDE_PRIVATE_IPS = "Exclude private IPs"
    const val GENERATE_NEW_PRIVATE_KEY = "Generate new private key"
    const val HINT_AUTOMATIC = "(auto)"
    const val HINT_GENERATED = "(generated)"
    const val HINT_OPTIONAL = "(optional)"
    const val HINT_OPTIONAL_DISCOURAGED = "(optional, not recommended)"
    const val HINT_RANDOM = "(random)"
    const val ALL_APPLICATIONS = "All Applications"
    fun persistentKeepaliveSuffix(count: Int) = if (count == 1) "second" else "seconds"

    fun tunnelCreateSuccess(name: String) = "Successfully created tunnel “$name”"
    const val TUNNEL_CREATE_ERROR = "Unable to create tunnel: %s"
    fun configSaveSuccess(name: String) = "Successfully saved configuration for “$name”"
    fun configSaveError(name: String, error: String) = "Cannot save configuration for “$name”: $error"
    fun tunnelRenameSuccess(name: String) = "Successfully renamed tunnel to “$name”"
    const val TUNNEL_RENAME_ERROR = "Unable to rename tunnel: %s"
    const val TUNNEL_ERROR_INVALID_NAME = "Invalid name"
    fun tunnelErrorAlreadyExists(name: String) = "Tunnel “$name” already exists"
    const val CREATE_ACTIVITY_TITLE = "Create AmneziaWG Tunnel"
    fun copiedToClipboard(label: String) = "$label copied to clipboard"

    // Settings
    fun versionTitle(version: String) = "AmneziaWG for Linux v$version"
    const val VERSION_SUMMARY = "%1\$s backend %2\$s"
    fun versionSummaryChecking(name: String) = "Checking $name backend version"
    fun versionSummaryUnknown(name: String) = "Unknown $name version"
    const val TYPE_NAME_KERNEL_MODULE = "Kernel module"
    const val TOOLS_TITLE = "AmneziaWG tools"
    const val PRIVILEGES_TITLE = "Administrator access"
    const val CONFIG_DIR_TITLE = "Configuration directory"
    const val RESTORE_ON_BOOT_TITLE = "Restore tunnels on application start"
    const val RESTORE_ON_BOOT_SUMMARY_ON = "Will bring up enabled tunnels at start"
    const val RESTORE_ON_BOOT_SUMMARY_OFF = "Will not bring up enabled tunnels at start"
    const val DARK_THEME_TITLE = "Use dark theme"
    const val DARK_THEME_SUMMARY_ON = "Currently using dark (night) theme"
    const val DARK_THEME_SUMMARY_OFF = "Currently using light (day) theme"
    const val CLOSE_TO_TRAY_TITLE = "Keep running in the system tray"
    const val CLOSE_TO_TRAY_SUMMARY_ON = "Closing the window hides it to the tray"
    const val CLOSE_TO_TRAY_SUMMARY_OFF = "Closing the window quits the application"
    const val CLOSE_TO_TRAY_UNAVAILABLE = "No system tray is available on this desktop"
    const val TRAY_SHOW = "Show AmneziaWG"
    const val TRAY_HIDE = "Hide AmneziaWG"
    const val TRAY_NO_TUNNELS = "No tunnels"
    const val TRAY_QUIT = "Quit"
    const val TRAY_DISCONNECTED = "No tunnels connected"
    fun trayConnected(names: List<String>) = "Connected: ${names.joinToString(", ")}"
    const val ACCENT_COLOR_TITLE = "Accent color"
    const val STATUS_COLOR_TITLE = "Status color"
    const val RESET_COLORS_TITLE = "Reset colors"
    const val RESET_COLORS_SUMMARY = "Restore the default AmneziaWG colors"
    const val COLOR_HEX = "Hex"
    const val APPLY = "Apply"
    const val MULTIPLE_TUNNELS_TITLE = "Allow multiple simultaneous tunnels"
    const val MULTIPLE_TUNNELS_SUMMARY_ON = "Multiple tunnels may be turned on simultaneously"
    const val MULTIPLE_TUNNELS_SUMMARY_OFF = "Turning on one tunnel will turn off others"
    const val ZIP_EXPORT_TITLE = "Export tunnels to zip file"
    const val ZIP_EXPORT_SUMMARY = "Save all tunnel configurations to a zip file"
    fun zipExportSuccess(path: String) = "Saved to “$path”"
    const val ZIP_EXPORT_ERROR = "Unable to export tunnels: %s"
    const val LOG_VIEWER_PREF_TITLE = "View application log"
    const val LOG_VIEWER_PREF_SUMMARY = "Logs may assist with debugging"
    const val IMPORT_TUNNEL_TITLE = "Import tunnels"

    // Log viewer
    const val LOG_VIEWER_TITLE = "Log"
    const val LOG_EXPORT_TITLE = "Export log file"
    fun logExportSuccess(path: String) = "Saved to “$path”"
    const val LOG_EXPORT_ERROR = "Unable to export log: %s"
    const val LOG_EMPTY = "No log entries yet"

    // Toggle errors
    const val ERROR_UP = "Error bringing up tunnel: %s"
    const val ERROR_DOWN = "Error bringing down tunnel: %s"
    const val UNKNOWN_ERROR = "Unknown error"
}
