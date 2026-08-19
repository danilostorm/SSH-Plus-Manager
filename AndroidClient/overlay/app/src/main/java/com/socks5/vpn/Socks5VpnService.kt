package com.socks5.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.socks5.R
import com.socks5.Socks5Application
import com.socks5.data.preferences.AppPreferences
import com.socks5.ssh.SshConnectionManager
import com.socks5.ui.MainActivity
import com.socks5.util.NotificationHelper
import com.socks5.util.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Persistent SSH VPN service used by Storm SSH.
 *
 * The foreground notification is started immediately and the SSH manager is
 * application-scoped, so minimizing/recreating MainActivity does not tear down
 * the tunnel. START_STICKY + encrypted credentials allow recovery if Android
 * recreates the service process while the user still expects the VPN online.
 */
class Socks5VpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var packetForwarder: PacketForwarder? = null
    private val notificationHelper by lazy { NotificationHelper(this) }
    private val trafficStats = TrafficStats()
    private var wakeLock: PowerManager.WakeLock? = null
    private var starting = false

    private val app: Socks5Application
        get() = application as Socks5Application

    private val sshManager: SshConnectionManager
        get() = app.sharedSshManager

    companion object {
        const val ACTION_CONNECT = "com.socks5.action.CONNECT"
        const val ACTION_DISCONNECT = "com.socks5.action.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_DNS_SERVER = "dns_server"
        const val EXTRA_CUSTOM_HOSTS = "custom_hosts"

        const val VPN_ADDRESS = "10.0.0.1"
        const val VPN_PREFIX_LENGTH = 24
        const val VPN_MTU = 1500

        private const val STORM_HOST = "ssh.hoststorm.cloud"
        private const val STORM_PORT = 2222
        private const val UI_PREFS = "storm_ssh_ui"
        private const val PREF_USERNAME = "last_username"
        private const val SERVICE_PREFS = "storm_ssh_service"
        private const val PREF_KEEP_CONNECTED = "keep_connected"
    }

    private var dnsServer: String = "1.1.1.1"
    private var customHosts: Map<String, String> = emptyMap()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_DNS_SERVER)?.let { dns ->
            if (dns.isNotBlank()) dnsServer = dns
        }

        intent?.getStringExtra(EXTRA_CUSTOM_HOSTS)?.let { json ->
            customHosts = parseHosts(json)
        }

        when (intent?.action) {
            ACTION_CONNECT -> {
                setKeepConnected(true)
                ensureForegroundConnecting()
                startVpnWhenReady()
            }
            ACTION_DISCONNECT -> {
                setKeepConnected(false)
                scope.launch {
                    app.stopSocksServer()
                    sshManager.disconnect()
                    app.activeSshManager = null
                    stopVpn()
                }
            }
            else -> {
                // Android recreated a START_STICKY service.
                if (shouldKeepConnected()) {
                    ensureForegroundConnecting()
                    restoreConnectionAndVpn()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun ensureForegroundConnecting() {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            NotificationHelper.NOTIFICATION_VPN_ID,
            notificationHelper.buildConnectingNotification(STORM_HOST, openIntent)
        )
        acquireWakeLock()
    }

    private fun startVpnWhenReady() {
        if (starting || tunInterface != null) return
        starting = true
        scope.launch {
            try {
                if (!sshManager.isConnected()) {
                    restoreSshSession()
                }
                if (!sshManager.isConnected()) {
                    throw IllegalStateException("SSH session not connected")
                }
                app.activeSshManager = sshManager
                app.startSocksServer(AppPreferences(this@Socks5VpnService).localSocksPort)
                establishVpn()
            } catch (_: Exception) {
                setKeepConnected(false)
                stopVpn()
            } finally {
                starting = false
            }
        }
    }

    private fun restoreConnectionAndVpn() {
        if (starting || tunInterface != null) return
        starting = true
        scope.launch {
            try {
                restoreSshSession()
                if (!sshManager.isConnected()) {
                    throw IllegalStateException("Unable to restore SSH session")
                }
                app.activeSshManager = sshManager
                app.startSocksServer(AppPreferences(this@Socks5VpnService).localSocksPort)
                establishVpn()
            } catch (_: Exception) {
                setKeepConnected(false)
                stopVpn()
            } finally {
                starting = false
            }
        }
    }

    private suspend fun restoreSshSession() {
        if (sshManager.isConnected()) return

        val username = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .getString(PREF_USERNAME, null)
            ?.trim()
            .orEmpty()
        val password = AppPreferences(this).getPassword(0L).orEmpty()

        if (username.isBlank() || password.isBlank()) return

        val config = SshConnectionManager.SshConfig(
            host = STORM_HOST,
            port = STORM_PORT,
            username = username,
            authMethod = SshConnectionManager.AuthMethod.Password(password),
            keepAliveInterval = 30_000L,
            reconnectEnabled = true
        )
        sshManager.connect(config)
    }

    private suspend fun establishVpn() {
        if (tunInterface != null) return

        // Protect the already-authenticated SSH socket BEFORE establishing TUN.
        // This prevents the VPN route from swallowing its own transport.
        sshManager.getSessionSocket()?.let { socket -> protect(socket) }

        val builder = Builder()
            .setSession("Storm SSH")
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(dnsServer)
            .setBlocking(false)

        tunInterface = builder.establish() ?: throw IllegalStateException("VPN interface unavailable")

        packetForwarder = PacketForwarder(
            tunFd = tunInterface!!,
            sshManager = sshManager,
            trafficStats = trafficStats,
            scope = scope
        ).also {
            it.dnsServer = dnsServer
            it.customHosts = customHosts
            it.start()
        }
        trafficStats.reset()

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, Socks5VpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        startForeground(
            NotificationHelper.NOTIFICATION_VPN_ID,
            notificationHelper.buildVpnNotification(
                status = getString(R.string.status_connected),
                host = STORM_HOST,
                traffic = "Online",
                openIntent = openIntent,
                disconnectIntent = disconnectIntent
            )
        )

        monitorTraffic(openIntent, disconnectIntent)
    }

    private fun monitorTraffic(openIntent: PendingIntent, disconnectIntent: PendingIntent) {
        scope.launch {
            trafficStats.stats.collect { stats ->
                val traffic = "↓ ${TrafficStats.formatBytes(stats.bytesDown)} ↑ ${TrafficStats.formatBytes(stats.bytesUp)}"
                val notification = notificationHelper.buildVpnNotification(
                    status = getString(R.string.status_connected),
                    host = STORM_HOST,
                    traffic = traffic,
                    openIntent = openIntent,
                    disconnectIntent = disconnectIntent
                )
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(NotificationHelper.NOTIFICATION_VPN_ID, notification)
            }
        }
    }

    private fun parseHosts(json: String): Map<String, String> {
        return try {
            val obj = org.json.JSONObject(json)
            buildMap {
                for (key in obj.keys()) put(key, obj.getString(key))
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StormSSH:PersistentTunnel"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun shouldKeepConnected(): Boolean =
        getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
            .getBoolean(PREF_KEEP_CONNECTED, false)

    private fun setKeepConnected(value: Boolean) {
        getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEEP_CONNECTED, value)
            .apply()
    }

    private fun stopVpn() {
        packetForwarder?.stop()
        packetForwarder = null
        tunInterface?.close()
        tunInterface = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not disconnect just because the UI task was removed from recents.
        // START_STICKY will keep/recreate the service if the OS needs to reclaim it.
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        setKeepConnected(false)
        app.stopSocksServer()
        scope.launch { sshManager.disconnect() }
        stopVpn()
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (!shouldKeepConnected()) {
            app.stopSocksServer()
        }
        scope.cancel()
        super.onDestroy()
    }
}
