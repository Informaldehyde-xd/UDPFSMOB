package com.ps2manager.udpfsserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ps2manager.udpfsserver.server.UdpfsSocketServer
import com.ps2manager.udpfsserver.udpfs.AndroidFsBackend
import com.ps2manager.udpfsserver.udprdma.UdpRdmaConst
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

enum class UdpfsServerState { STOPPED, STARTING, RUNNING, ERROR }

data class UdpfsServerStatus(
    val state: UdpfsServerState = UdpfsServerState.STOPPED,
    val ipAddress: String? = null,
    val discoveryPort: Int = 0,
    val dataPort: Int = 0,
    val sharePath: String = "",
    val startTimeMillis: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val errorMessage: String? = null
)

class UdpfsServerService : Service() {

    companion object {
        private const val TAG = "UdpfsServerService"
        private val _status = MutableStateFlow(UdpfsServerStatus())
        val status = _status.asStateFlow()
        private const val NOTIF_ID = 2
        private const val CHANNEL_ID = "UDPFS_CHANNEL"
        private const val TICK_INTERVAL_MS = 5000L
    }

    private var server: UdpfsSocketServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("UDPFS_MULTICAST_LOCK").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createNotificationChannel()

            val sharePath = SettingsManager.getSharePath(this)
            val bindIp = SettingsManager.getBindIp(this)
            val verbose = SettingsManager.getVerboseLogging(this)
            val moduloMode = SettingsManager.getModuloMode(this)

            FileLogger.startSession("UDPFS Server")
            FileLogger.header("moduloMode=$moduloMode verbose=$verbose sharePath=$sharePath bindIp=${bindIp.ifBlank { "(any)" }}")
            FileLogger.header("network interfaces: ${NetworkUtils.dumpInterfaces()}")

            _status.value = UdpfsServerStatus(
                state = UdpfsServerState.STARTING,
                sharePath = sharePath
            )

            startForeground(NOTIF_ID, buildNotification(_status.value))
            acquireLocks()

            val shareDir = File(sharePath)
            shareDir.mkdirs()
            val backend = AndroidFsBackend(shareDir)
            val bindAddr = if (bindIp.isNotBlank()) InetAddress.getByName(bindIp) else null

            val udpfsServer = UdpfsSocketServer(
                backend = backend,
                bindAddress = bindAddr,
                verbose = verbose,
                moduloMode = moduloMode
            )
            udpfsServer.start()
            server = udpfsServer

            _status.value = _status.value.copy(
                state = UdpfsServerState.RUNNING,
                ipAddress = bindIp.ifBlank { NetworkUtils.getLocalIpAddress() },
                discoveryPort = UdpRdmaConst.UDPFS_PORT,
                dataPort = udpfsServer.dataPort,
                startTimeMillis = System.currentTimeMillis()
            )
            startTicker()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDPFS server", e)
            FileLogger.e(TAG, "Failed to start UDPFS server", e)
            val errorStatus = _status.value.copy(
                state = UdpfsServerState.ERROR,
                errorMessage = "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}"
            )
            _status.value = errorStatus
            showErrorNotification(errorStatus)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startTicker() {
        tickerRunnable = object : Runnable {
            override fun run() {
                val current = _status.value
                if (current.state == UdpfsServerState.RUNNING) {
                    val uptime = (System.currentTimeMillis() - current.startTimeMillis) / 1000
                    val updated = current.copy(uptimeSeconds = uptime)
                    _status.value = updated
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIF_ID, buildNotification(updated))
                }
                mainHandler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
        mainHandler.post(tickerRunnable!!)
    }

    private fun stopTicker() {
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        tickerRunnable = null
    }

    private fun showErrorNotification(errorStatus: UdpfsServerStatus) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(errorStatus))
    }

    private fun buildNotification(status: UdpfsServerStatus): android.app.Notification {
        val ipText = status.ipAddress ?: "unknown IP"
        val uptimeText = formatUptime(status.uptimeSeconds)
        val contentText = when (status.state) {
            UdpfsServerState.RUNNING -> "$ipText • disc:${status.discoveryPort} data:${status.dataPort} • Up $uptimeText"
            UdpfsServerState.STARTING -> "Starting server..."
            UdpfsServerState.ERROR -> "Error: ${status.errorMessage ?: "server stopped"}"
            UdpfsServerState.STOPPED -> "Server stopped"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UDPFS Server")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOnlyAlertOnce(true)
            .setOngoing(status.state == UdpfsServerState.RUNNING || status.state == UdpfsServerState.STARTING)
            .build()
    }

    private fun formatUptime(totalSeconds: Long): String {
        val h = TimeUnit.SECONDS.toHours(totalSeconds)
        val m = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            true
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UdpfsServer::WakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)

        if (isOnWifi()) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(lockMode, "UdpfsServer::WifiLock")
            wifiLock?.acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "UDPFS Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
            multicastLock = null
            
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null

            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null

            stopTicker()
            server?.stop()
            server = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks on destroy", e)
        }
        _status.value = _status.value.copy(state = UdpfsServerState.STOPPED)
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
