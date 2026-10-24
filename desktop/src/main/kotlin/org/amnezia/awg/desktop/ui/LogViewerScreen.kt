/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.layout.Box
import org.amnezia.awg.desktop.util.AppLog
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.desktop.model.AppState
import org.amnezia.awg.desktop.util.ErrorMessages
import org.amnezia.awg.desktop.util.FileDialogs
import org.amnezia.awg.desktop.util.Strings

/**
 * Port of `LogViewerActivity`. The "Application" tab shows [AppLog] (what
 * logcat shows on Android) and updates live; "System journal" shows the
 * AmneziaWG kernel module and `awg-quick` service entries of the journal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(app: AppState) {
    var tab by remember { mutableIntStateOf(0) }
    val revision by AppLog.revision.collectAsState()
    val appEntries = remember(revision) { AppLog.entries() }
    var journal by remember { mutableStateOf<List<String>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(tab, reload) {
        if (tab != 1) return@LaunchedEffect
        journal = null
        journal = try {
            withContext(Dispatchers.IO) { app.backend.systemJournal() }.lines().filter { it.isNotBlank() }
        } catch (e: Throwable) {
            listOf(ErrorMessages[e])
        }
    }

    // Lines of the visible tab: app entries keep their level for coloring.
    val lines: List<Pair<AppLog.Level?, String>>? = when (tab) {
        0 -> appEntries.map { it.level to it.format() }
        else -> journal?.map { null to it }
    }

    // Follow the end of the log, like `tail -f`, unless the user scrolled up.
    var followEnd by remember(tab) { mutableStateOf(true) }
    LaunchedEffect(listState.canScrollForward) {
        if (listState.canScrollForward && listState.isScrollInProgress) followEnd = false
        if (!listState.canScrollForward) followEnd = true
    }
    LaunchedEffect(tab, lines?.size) {
        val size = lines?.size ?: 0
        if (size > 0 && followEnd) listState.scrollToItem(size - 1)
    }

    fun export() {
        val content = lines ?: return
        val name = if (tab == 0) "amneziawg-app-log.txt" else "amneziawg-system-journal.txt"
        val target = FileDialogs.saveFile(Strings.LOG_EXPORT_TITLE, name) ?: return
        try {
            target.writeText(content.joinToString("\n", postfix = "\n") { it.second })
            app.showSnackbar(Strings.logExportSuccess(target.absolutePath))
        } catch (e: Throwable) {
            app.showSnackbar(Strings.logExportError(ErrorMessages[e]))
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(Strings.LOG_VIEWER_TITLE) },
                    navigationIcon = { BackButton { app.popBackStack() } },
                    actions = {
                        if (tab == 1) TooltipIconButton(Icons.Default.Refresh, Strings.REFRESH) { reload++ }
                        TooltipIconButton(Icons.Default.Save, Strings.LOG_EXPORT_TITLE, enabled = !lines.isNullOrEmpty(), onClick = ::export)
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(Strings.LOG_TAB_APP) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(Strings.LOG_TAB_SYSTEM) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                if (tab == 0) Strings.logFileHint(AppLog.file.absolutePath) else Strings.SYSTEM_JOURNAL_HINT,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.fillMaxSize()) {
                when {
                    lines == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    lines.isEmpty() -> Text(
                        if (tab == 0) Strings.LOG_EMPTY else Strings.SYSTEM_JOURNAL_EMPTY,
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        SelectionContainer {
                            LazyColumn(Modifier.fillMaxSize().padding(start = 12.dp, end = 16.dp), state = listState) {
                                items(lines) { (level, text) ->
                                    Text(
                                        text,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (level) {
                                            AppLog.Level.ERROR -> MaterialTheme.colorScheme.error
                                            AppLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                                            AppLog.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.padding(vertical = 1.dp),
                                    )
                                }
                            }
                        }
                        VerticalScrollbarFor(listState)
                    }
                }
            }
        }
    }
}
