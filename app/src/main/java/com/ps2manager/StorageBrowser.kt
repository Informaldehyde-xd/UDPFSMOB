/* A plain-filesystem folder/file browser, so the person can navigate and
 * pick a path instead of typing one. Deliberately NOT the Storage Access
 * Framework (ACTION_OPEN_DOCUMENT_TREE): SAF hands back a content:// URI
 * that needs DocumentFile/ContentResolver plumbing, which doesn't match
 * how the rest of this app works — SettingsManager, the UDPFS share-folder
 * scanning, and the UDPBD RandomAccessFile backend all want a plain
 * absolute java.io.File path. This browser returns exactly that, relying
 * on the MANAGE_EXTERNAL_STORAGE permission the app already requests. */
package com.ps2manager.udpfsserver

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import java.io.File

enum class PickerMode { FOLDER, FILE }

/** Each storage volume's actual root (e.g. "/storage/emulated/0" for
 *  internal, "/storage/1234-5678" for an SD card), derived from the
 *  per-app external files dirs Android already exposes without any
 *  extra permission beyond the broad storage access this app has. */
fun getStorageRoots(context: Context): List<File> {
    return ContextCompat.getExternalFilesDirs(context, null)
        .filterNotNull()
        .mapNotNull { appDir ->
            // appDir is <root>/Android/data/<package>/files — walk up 4 levels to <root>.
            var root: File? = appDir
            repeat(4) { root = root?.parentFile }
            root
        }
        .filter { it.exists() && it.isDirectory }
        .distinct()
}

private fun rootLabel(root: File): String =
    if (root.path.contains("/emulated/")) "Internal storage" else "SD card (${root.name})"

@Composable
fun StorageBrowserDialog(
    mode: PickerMode,
    initialPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    val roots = remember { getStorageRoots(context) }
    val startDir = remember {
        val initial = initialPath.takeIf { it.isNotBlank() }?.let { File(it) }
        val startPoint = if (mode == PickerMode.FILE) initial?.parentFile else initial
        (startPoint?.takeIf { it.exists() && it.isDirectory }) ?: roots.firstOrNull() ?: File("/storage/emulated/0")
    }
    var currentDir by remember { mutableStateOf(startDir) }
    var listError by remember { mutableStateOf<String?>(null) }

    val entries = remember(currentDir) {
        listError = null
        try {
            val all = currentDir.listFiles()?.toList() ?: emptyList()
            val filtered = if (mode == PickerMode.FOLDER) all.filter { it.isDirectory } else all
            filtered.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (e: Exception) {
            listError = "Can't read this folder: ${e.message}"
            emptyList()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(16.dp)) {
                Text(
                    if (mode == PickerMode.FOLDER) "Choose a folder" else "Choose a file",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (roots.size > 1) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        roots.forEach { root ->
                            FilterChip(
                                selected = currentDir.path.startsWith(root.path),
                                onClick = { currentDir = root },
                                label = { Text(rootLabel(root)) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    currentDir.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                val parent = currentDir.parentFile
                val canGoUp = parent != null && roots.any { currentDir.path != it.path && currentDir.path.startsWith(it.path) }
                if (canGoUp && parent != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { currentDir = parent },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⬆  ..", style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider()
                }

                if (listError != null) {
                    Text(listError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(entries) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        currentDir = entry
                                    } else if (mode == PickerMode.FILE) {
                                        onSelect(entry.absolutePath)
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (entry.isDirectory) "📁" else "📄", modifier = Modifier.padding(end = 8.dp))
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                    if (entries.isEmpty() && listError == null) {
                        item {
                            Text(
                                "(empty)",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (mode == PickerMode.FOLDER) {
                        Button(onClick = { onSelect(currentDir.absolutePath) }) {
                            Text("Select this folder")
                        }
                    }
                }
            }
        }
    }
}
