/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.amnezia.awg.desktop.util.Contributor
import org.amnezia.awg.desktop.util.Contributors
import org.amnezia.awg.desktop.util.Credits
import javax.imageio.ImageIO
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.desktop.model.AppState
import org.amnezia.awg.desktop.model.Screen
import org.amnezia.awg.desktop.theme.ThemeColors
import org.amnezia.awg.desktop.util.AppPaths
import org.amnezia.awg.desktop.util.BuildInfo
import org.amnezia.awg.desktop.util.ErrorMessages
import org.amnezia.awg.desktop.util.FileDialogs
import org.amnezia.awg.desktop.util.I18n
import org.amnezia.awg.desktop.util.Prefs
import org.amnezia.awg.desktop.util.Strings

/** Port of `SettingsActivity`'s preference screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppState) {
    val prefs = app.prefs
    // Raw values; the texts are formatted while composing so a language change applies.
    var moduleVersion by remember { mutableStateOf<Result<String>?>(null) }
    var toolsVersion by remember { mutableStateOf("") }
    var restoreOnStart by remember { mutableStateOf(prefs.getBoolean(Prefs.KEY_RESTORE_ON_BOOT, false)) }
    var multipleTunnels by remember { mutableStateOf(app.backend.multipleTunnels) }
    var exporting by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(Credits.NONE) }

    LaunchedEffect(Unit) {
        moduleVersion = runCatching { withContext(Dispatchers.IO) { app.backend.getVersion() } }
        toolsVersion = try {
            withContext(Dispatchers.IO) { app.backend.getToolsVersion() }
        } catch (e: Throwable) {
            ErrorMessages[e]
        }
    }
    // The cached credits at once, then fresh ones from GitHub at most once a day.
    LaunchedEffect(Unit) {
        credits = withContext(Dispatchers.IO) { Contributors.cached() }
        withContext(Dispatchers.IO) { Contributors.refreshIfStale() }?.let { credits = it }
    }

    fun openInBrowser(url: String) {
        try {
            BuildInfo.openInBrowser(url)
        } catch (e: Exception) {
            app.showSnackbar(ErrorMessages[e])
        }
    }

    fun exportZip() {
        val target = FileDialogs.saveFile(Strings.ZIP_EXPORT_TITLE, "amneziawg-export.zip") ?: return
        exporting = true
        app.scope.launch {
            try {
                val configs = app.tunnelManager.tunnels.toList().map { it to it.getConfigAsync() }
                withContext(Dispatchers.IO) { FileDialogs.exportZip(configs, target) }
                app.showSnackbar(Strings.zipExportSuccess(target.absolutePath))
            } catch (e: Throwable) {
                app.showSnackbar(Strings.zipExportError(ErrorMessages[e]))
            } finally {
                exporting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(Strings.SETTINGS) }, navigationIcon = { BackButton { app.popBackStack() } })
        },
    ) { padding ->
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                val module = app.backend.typeName
                ActionRow(
                    Strings.versionTitle(BuildInfo.platformName, BuildInfo.version),
                    moduleVersion?.fold({ Strings.versionSummary(module, it) }, { Strings.versionSummaryUnknown(module) })
                        ?: Strings.versionSummaryChecking(module),
                ) {}
                RepositoryRow(Strings.SOURCE_CODE_TITLE, BuildInfo.REPOSITORY_URL, credits.fork, onOpen = ::openInBrowser)
                RepositoryRow(Strings.BASED_ON_TITLE, BuildInfo.UPSTREAM_REPOSITORY_URL, credits.upstream, onOpen = ::openInBrowser)
                ActionRow(Strings.TOOLS_TITLE, toolsVersion) {}
                ActionRow(Strings.PRIVILEGES_TITLE, app.backend.privilegeDescription) {}
                ActionRow(Strings.CONFIG_DIR_TITLE, AppPaths.configDir.absolutePath) {}
                HorizontalDivider()
                SwitchRow(
                    Strings.RESTORE_ON_BOOT_TITLE,
                    if (restoreOnStart) Strings.RESTORE_ON_BOOT_SUMMARY_ON else Strings.RESTORE_ON_BOOT_SUMMARY_OFF,
                    restoreOnStart,
                ) {
                    restoreOnStart = it
                    prefs.putBoolean(Prefs.KEY_RESTORE_ON_BOOT, it)
                }
                SwitchRow(
                    Strings.MULTIPLE_TUNNELS_TITLE,
                    if (multipleTunnels) Strings.MULTIPLE_TUNNELS_SUMMARY_ON else Strings.MULTIPLE_TUNNELS_SUMMARY_OFF,
                    multipleTunnels,
                ) {
                    multipleTunnels = it
                    app.backend.multipleTunnels = it
                    prefs.putBoolean(Prefs.KEY_MULTIPLE_TUNNELS, it)
                }
                SwitchRow(
                    Strings.CLOSE_TO_TRAY_TITLE,
                    when {
                        !app.trayAvailable -> Strings.CLOSE_TO_TRAY_UNAVAILABLE
                        app.closeToTray -> Strings.CLOSE_TO_TRAY_SUMMARY_ON
                        else -> Strings.CLOSE_TO_TRAY_SUMMARY_OFF
                    },
                    app.closeToTray && app.trayAvailable,
                    enabled = app.trayAvailable,
                ) { app.updateCloseToTray(it) }
                LanguageRow(app)
                SwitchRow(
                    Strings.DARK_THEME_TITLE,
                    if (app.darkTheme) Strings.DARK_THEME_SUMMARY_ON else Strings.DARK_THEME_SUMMARY_OFF,
                    app.darkTheme,
                ) { app.updateDarkTheme(it) }
                ColorRow(Strings.ACCENT_COLOR_TITLE, app.themeColors.accent) {
                    app.updateThemeColors(app.themeColors.copy(accent = it))
                }
                ColorRow(Strings.STATUS_COLOR_TITLE, app.themeColors.status) {
                    app.updateThemeColors(app.themeColors.copy(status = it))
                }
                ActionRow(Strings.RESET_COLORS_TITLE, Strings.RESET_COLORS_SUMMARY, enabled = !app.themeColors.isDefault) {
                    app.updateThemeColors(ThemeColors())
                }
                HorizontalDivider()
                ActionRow(Strings.ZIP_EXPORT_TITLE, Strings.ZIP_EXPORT_SUMMARY, enabled = !exporting, onClick = ::exportZip)
                ActionRow(Strings.LOG_VIEWER_PREF_TITLE, Strings.LOG_VIEWER_PREF_SUMMARY) { app.navigate(Screen.LogViewer) }
            }
            VerticalScrollbarFor(scroll)
        }
    }
}

/** Language picker: "System default" plus every translated language, in its own name. */
@Composable
private fun LanguageRow(app: AppState) {
    var open by remember { mutableStateOf(false) }
    val system = I18n.resolve(Strings.systemLocale)
    Box {
        ActionRow(
            Strings.LANGUAGE_TITLE,
            app.language?.nativeName ?: Strings.languageSystem(system.nativeName),
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(Strings.languageSystem(system.nativeName)) },
                leadingIcon = { if (app.language == null) Icon(Icons.Default.Check, null) },
                onClick = { open = false; app.updateLanguage(null) },
            )
            HorizontalDivider()
            I18n.languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.nativeName) },
                    leadingIcon = { if (app.language == language) Icon(Icons.Default.Check, null) },
                    onClick = { open = false; app.updateLanguage(language) },
                )
            }
        }
    }
}

/**
 * A repository link (this project's, or the one it is based on), with its
 * contributors' avatars at the end of the row.
 */
@Composable
private fun RepositoryRow(title: String, url: String, contributors: List<Contributor>, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(url) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (contributors.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                contributors.forEach { contributor -> ContributorAvatar(contributor) { onOpen(contributor.profileUrl) } }
            }
        }
    }
}

/** A small round avatar; hovering shows the name, clicking opens the GitHub profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContributorAvatar(contributor: Contributor, onClick: () -> Unit) {
    val image = remember(contributor.avatar) {
        contributor.avatar?.let { file -> runCatching { ImageIO.read(file)?.toComposeImageBitmap() }.getOrNull() }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(contributor.label) } },
        state = rememberTooltipState(),
    ) {
        val modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick)
        if (image != null) {
            Image(image, contentDescription = contributor.label, modifier = modifier, contentScale = ContentScale.Crop)
        } else {
            // No avatar downloaded: the first letter of the login.
            Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Text(
                    contributor.login.take(1).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
