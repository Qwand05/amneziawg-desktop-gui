/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.foundation.layout.Box
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
 * Port of `LogViewerActivity`. Instead of logcat, it shows the `awg-quick`
 * entries of the systemd journal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(app: AppState) {
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(reload) {
        lines = try {
            withContext(Dispatchers.IO) { app.backend.journal() }
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("-- No entries --") }
        } catch (e: Throwable) {
            listOf(ErrorMessages[e])
        }
        lines?.let { if (it.isNotEmpty()) listState.scrollToItem(it.lastIndex) }
    }

    fun export() {
        val content = lines ?: return
        val target = FileDialogs.saveFile(Strings.LOG_EXPORT_TITLE, "amneziawg-log.txt") ?: return
        try {
            target.writeText(content.joinToString("\n", postfix = "\n"))
            app.showSnackbar(Strings.logExportSuccess(target.absolutePath))
        } catch (e: Throwable) {
            app.showSnackbar(Strings.LOG_EXPORT_ERROR.format(ErrorMessages[e]))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.LOG_VIEWER_TITLE) },
                navigationIcon = { BackButton { app.popBackStack() } },
                actions = {
                    TooltipIconButton(Icons.Default.Refresh, Strings.REFRESH) { reload++ }
                    TooltipIconButton(Icons.Default.Save, Strings.LOG_EXPORT_TITLE, enabled = !lines.isNullOrEmpty(), onClick = ::export)
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val current = lines
            when {
                current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                current.isEmpty() -> Text(
                    Strings.LOG_EMPTY,
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState) {
                        items(current) {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
