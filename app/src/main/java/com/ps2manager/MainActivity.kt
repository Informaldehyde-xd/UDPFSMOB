package com.ps2manager.udpfsserver

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var launchError by remember { mutableStateOf<String?>(null) }
                    UdpfsControllerScreen(
                        launchError = launchError,
                        onRequestStorage = { requestStoragePermission() },
                        onStartServer = {
                            try {
                                launchError = null
                                val intent = Intent(this, UdpfsServerService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(intent)
                                } else {
                                    startService(intent)
                                }
                            } catch (e: Exception) {
                                launchError = "UDPFS Launch Error: ${e.javaClass.simpleName}: ${e.message}"
                            }
                        },
                        onStopServer = {
                            try {
                                launchError = null
                                stopService(Intent(this, UdpfsServerService::class.java))
                            } catch (e: Exception) {
                                launchError = "UDPFS Stop Error: ${e.javaClass.simpleName}: ${e.message}"
                            }
                        },
                        onStartUdpBdServer = {
                            try {
                                launchError = null
                                val intent = Intent(this, UdpBdServerService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(intent)
                                } else {
                                    startService(intent)
                                }
                            } catch (e: Exception) {
                                launchError = "UDPBD Launch Error: ${e.javaClass.simpleName}: ${e.message}"
                            }
                        },
                        onStopUdpBdServer = {
                            try {
                                launchError = null
                                stopService(Intent(this, UdpBdServerService::class.java))
                            } catch (e: Exception) {
                                launchError = "UDPBD Stop Error: ${e.javaClass.simpleName}: ${e.message}"
                            }
                        }
                    )
                }
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
        }
    }
}

@Composable
fun UdpfsControllerScreen(
    launchError: String?,
    onRequestStorage: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onStartUdpBdServer: () -> Unit,
    onStopUdpBdServer: () -> Unit
) {
    val context = LocalContext.current
    val status by UdpfsServerService.status.collectAsState()

    var sharePath by remember { mutableStateOf(SettingsManager.getSharePath(context)) }
    var bindIp by remember { mutableStateOf(SettingsManager.getBindIp(context)) }
    var verbose by remember { mutableStateOf(SettingsManager.getVerboseLogging(context)) }
    var moduloMode by remember { mutableStateOf(SettingsManager.getModuloMode(context)) }
    var showShareBrowser by remember { mutableStateOf(false) }

    // Explicitly allow empty/blank IP inputs (binds to all interfaces)
    val ipValid = bindIp.isBlank() || SettingsManager.isValidIp(bindIp)
    val settingsValid = ipValid && sharePath.isNotBlank()
    val isRunning = status.state == UdpfsServerState.RUNNING || status.state == UdpfsServerState.STARTING
    val canStop = status.state != UdpfsServerState.STOPPED

    var udpBdImagePath by remember { mutableStateOf(SettingsManager.getUdpBdImagePath(context)) }
    var udpBdBindIp by remember { mutableStateOf(SettingsManager.getUdpBdBindIp(context)) }
    var udpBdVerbose by remember { mutableStateOf(SettingsManager.getUdpBdVerboseLogging(context)) }
    var showImageBrowser by remember { mutableStateOf(false) }
    val udpBdStatus by UdpBdServerService.status.collectAsState()

    // Explicitly allow empty/blank IP inputs (binds to all interfaces)
    val udpBdIpValid = udpBdBindIp.isBlank() || SettingsManager.isValidIp(udpBdBindIp)
    val udpBdSettingsValid = udpBdIpValid && udpBdImagePath.isNotBlank()
    val udpBdIsRunning = udpBdStatus.state == UdpBdServerState.RUNNING || udpBdStatus.state == UdpBdServerState.STARTING
    val udpBdCanStop = udpBdStatus.state != UdpBdServerState.STOPPED

    val saveUdpBdSettings: () -> Unit = {
        SettingsManager.setUdpBdImagePath(context, udpBdImagePath)
        SettingsManager.setUdpBdBindIp(context, udpBdBindIp)
        SettingsManager.setUdpBdVerboseLogging(context, udpBdVerbose)
    }

    val saveSettings: () -> Unit = {
        SettingsManager.setSharePath(context, sharePath)
        SettingsManager.setBindIp(context, bindIp)
        SettingsManager.setVerboseLogging(context, verbose)
        SettingsManager.setModuloMode(context, moduloMode)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("UDPFS Server (Neutrino / Modulo)", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        StatusCard(status)
        if (launchError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Error: $launchError",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRequestStorage, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
            Text("1. Grant All Files Access (Required)")
        }
        Spacer(modifier = Modifier.height(24.dp))

        Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = sharePath,
                onValueChange = { sharePath = it },
                label = { Text("Share Folder Path") },
                singleLine = true,
                enabled = !isRunning,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { showShareBrowser = true }, enabled = !isRunning) {
                Text("Browse…")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = bindIp,
            onValueChange = { bindIp = it },
            label = { Text("Bind IP (optional, blank = all interfaces)") },
            isError = !ipValid,
            supportingText = {
                if (!ipValid) Text("Enter a valid IPv4 address or leave blank")
                else if (status.ipAddress != null) Text("Device IP: ${status.ipAddress}")
            },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = verbose, onCheckedChange = { verbose = it }, enabled = !isRunning)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Verbose logging (per-request log lines)")
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = moduloMode, onCheckedChange = { moduloMode = it }, enabled = !isRunning)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Modulo mode (single-port; enable only while using Modulo)")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = saveSettings,
            enabled = settingsValid && !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Settings")
        }
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { saveSettings(); onStartServer() },
            enabled = settingsValid && !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("2. Start UDPFS Server")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onStopServer,
            enabled = canStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Stop Server")
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("UDPBD Server", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        UdpBdStatusCard(udpBdStatus)
        Spacer(modifier = Modifier.height(24.dp))

        Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = udpBdImagePath,
                onValueChange = { udpBdImagePath = it },
                label = { Text(".img File Path") },
                supportingText = { Text("Raw disk image created via the Termux .img guide") },
                singleLine = true,
                enabled = !udpBdIsRunning,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { showImageBrowser = true }, enabled = !udpBdIsRunning) {
                Text("Browse…")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = udpBdBindIp,
            onValueChange = { udpBdBindIp = it },
            label = { Text("Bind IP (optional, blank = all interfaces)") },
            isError = !udpBdIpValid,
            supportingText = {
                if (!udpBdIpValid) Text("Enter a valid IPv4 address or leave blank")
                else if (udpBdStatus.ipAddress != null) Text("Device IP: ${udpBdStatus.ipAddress}")
            },
            singleLine = true,
            enabled = !udpBdIsRunning,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = udpBdVerbose, onCheckedChange = { udpBdVerbose = it }, enabled = !udpBdIsRunning)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Verbose logging (per-request log lines)")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = saveUdpBdSettings,
            enabled = udpBdSettingsValid && !udpBdIsRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Settings")
        }
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { saveUdpBdSettings(); onStartUdpBdServer() },
            enabled = udpBdSettingsValid && !udpBdIsRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start UDPBD Server")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onStopUdpBdServer,
            enabled = udpBdCanStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Stop Server")
        }
    }

    if (showShareBrowser) {
        StorageBrowserDialog(
            mode = PickerMode.FOLDER,
            initialPath = sharePath,
            onDismiss = { showShareBrowser = false },
            onSelect = { path -> sharePath = path; showShareBrowser = false }
        )
    }

    if (showImageBrowser) {
        StorageBrowserDialog(
            mode = PickerMode.FILE,
            initialPath = udpBdImagePath,
            onDismiss = { showImageBrowser = false },
            onSelect = { path -> udpBdImagePath = path; showImageBrowser = false }
        )
    }
}

@Composable
fun StatusCard(status: UdpfsServerStatus) {
    val (label, color) = when (status.state) {
        UdpfsServerState.RUNNING -> "RUNNING" to MaterialTheme.colorScheme.primary
        UdpfsServerState.STARTING -> "STARTING…" to MaterialTheme.colorScheme.tertiary
        UdpfsServerState.ERROR -> "ERROR" to MaterialTheme.colorScheme.error
        UdpfsServerState.STOPPED -> "STOPPED" to MaterialTheme.colorScheme.outline
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status: ", fontWeight = FontWeight.Bold)
                Text(label, color = color, fontWeight = FontWeight.Bold)
            }
            if (status.state == UdpfsServerState.RUNNING) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Address: ${status.ipAddress ?: "unknown"}")
                Text("Discovery port: ${status.discoveryPort}")
                Text("Data port: ${status.dataPort}")
                Text("Folder: ${status.sharePath}")
                Text("Uptime: ${formatUptime(status.uptimeSeconds)}")
            } else if (status.state == UdpfsServerState.ERROR) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(status.errorMessage ?: "Unknown error", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun UdpBdStatusCard(status: UdpBdServerStatus) {
    val (label, color) = when (status.state) {
        UdpBdServerState.RUNNING -> "RUNNING" to MaterialTheme.colorScheme.primary
        UdpBdServerState.STARTING -> "STARTING…" to MaterialTheme.colorScheme.tertiary
        UdpBdServerState.ERROR -> "ERROR" to MaterialTheme.colorScheme.error
        UdpBdServerState.STOPPED -> "STOPPED" to MaterialTheme.colorScheme.outline
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status: ", fontWeight = FontWeight.Bold)
                Text(label, color = color, fontWeight = FontWeight.Bold)
            }
            if (status.state == UdpBdServerState.RUNNING) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Address: ${status.ipAddress ?: "unknown"}")
                Text("Port: ${status.port} (0x${status.port.toString(16).uppercase()})")
                Text("Image: ${status.imagePath}")
                Text("Sectors: ${status.sectorCount}")
                Text("Uptime: ${formatUptime(status.uptimeSeconds)}")
            } else if (status.state == UdpBdServerState.ERROR) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(status.errorMessage ?: "Unknown error", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatUptime(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}
