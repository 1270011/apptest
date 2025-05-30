package com.example.vpnapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
// import com.wireguard.config.InetNetwork // Not directly used in this version
import java.io.ByteArrayInputStream

class MyVpnService : VpnService(), Tunnel.StateListener {

    private lateinit var builder: VpnService.Builder
    private var pfd: ParcelFileDescriptor? = null
    private var backend: GoBackend? = null
    private var tunnel: Tunnel? = null
    private lateinit var localBroadcastManager: LocalBroadcastManager

    companion object {
        const val ACTION_CONNECT = "com.example.vpnapp.CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpnapp.DISCONNECT"
        private const val NOTIFICATION_CHANNEL_ID = "VpnServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "MyVpnService"

        // Intent actions and extras for status updates
        const val ACTION_VPN_STATUS_UPDATE = "com.example.vpnapp.VPN_STATUS_UPDATE"
        const val EXTRA_VPN_STATE = "com.example.vpnapp.EXTRA_VPN_STATE"
        const val EXTRA_VPN_MESSAGE = "com.example.vpnapp.EXTRA_VPN_MESSAGE"

        // Intent extra for config
        const val EXTRA_CONFIG_STRING = "com.example.vpnapp.EXTRA_CONFIG_STRING"

        // No longer using internal fallback config. Config must be provided by MainActivity.
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        backend = GoBackend(this)
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
    }

    private fun sendTunnelStatusUpdate(state: String, message: String?) {
        val intent = Intent(ACTION_VPN_STATUS_UPDATE)
        intent.putExtra(EXTRA_VPN_STATE, state)
        intent.putExtra(EXTRA_VPN_MESSAGE, message ?: state) // Use state as message if null
        localBroadcastManager.sendBroadcast(intent)
        Log.d(TAG, "Sent status update: State=$state, Message=$message")
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VPN Service")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a proper icon
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val configString = intent.getStringExtra(EXTRA_CONFIG_STRING)
                if (configString.isNullOrEmpty()) {
                    Log.e(TAG, "Configuration not provided by MainActivity or is empty!")
                    sendTunnelStatusUpdate(Tunnel.State.DOWN.name, "Error: No configuration provided")
                    stopSelf() // Stop the service as it cannot proceed
                    return START_NOT_STICKY // Or START_STICKY if you want it to retry if killed, though without config it's pointless
                }
                startVpn(configString)
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn(configString: String) {
        Log.d(TAG, "Starting VPN with provided config")
        sendTunnelStatusUpdate(Tunnel.State.TOGGLING.name, "Connecting...")
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        try {
            val config = Config.parse(ByteArrayInputStream(configString.toByteArray()))
            // Ensure tunnel is uniquely named or managed if multiple configs are ever used
            tunnel = object : Tunnel {
                override fun getName() = config.interfaze.name.orElse("wg0")
                override fun onStateChange(newState: Tunnel.State) {
                    this@MyVpnService.onStateChange(newState)
                }
            }
            // This call will trigger onStateChange -> Tunnel.State.UP
            backend?.setState(tunnel!!, Tunnel.State.UP, config)


            builder = Builder()
            builder.setSession(tunnel!!.name) // Use tunnel name for session

            config.interfaze.addresses.forEach { addr ->
                builder.addAddress(addr.address, addr.mask)
            }

            config.interfaze.dnsServers.forEach { dns ->
                builder.addDnsServer(dns.hostAddress)
            }

            var hasDefaultRouteV4 = false
            var hasDefaultRouteV6 = false

            config.peers.forEach { peer ->
                peer.allowedIps.forEach { allowedIp ->
                    try {
                        builder.addRoute(allowedIp.address, allowedIp.mask)
                        if (allowedIp.toString() == "0.0.0.0/0") hasDefaultRouteV4 = true
                        if (allowedIp.toString() == "::/0") hasDefaultRouteV6 = true
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Invalid route: $allowedIp", e)
                    }
                }
            }

            if (!hasDefaultRouteV4) {
                Log.i(TAG, "Adding default IPv4 route 0.0.0.0/0")
                builder.addRoute("0.0.0.0", 0)
            }
            if (!hasDefaultRouteV6) {
                 Log.i(TAG, "Adding default IPv6 route ::/0")
                builder.addRoute("::", 0)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setBlocking(true)
            }

            pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "Failed to establish VPN interface, pfd is null.")
                sendTunnelStatusUpdate(Tunnel.State.DOWN.name, "Error: Could not establish VPN interface")
                stopVpn()
                return
            }
            Log.d(TAG, "VPN interface established. FD: ${pfd!!.fileDescriptor.valid()}")
            // onStateChange should be called by backend to confirm UP state

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            sendTunnelStatusUpdate(Tunnel.State.DOWN.name, "Error: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN")
        sendTunnelStatusUpdate(Tunnel.State.TOGGLING.name, "Disconnecting...")
        try {
            if (tunnel != null && backend != null) {
                 // This call will trigger onStateChange -> Tunnel.State.DOWN
                 backend?.setState(tunnel!!, Tunnel.State.DOWN, null)
            } else {
                // If tunnel was never up, directly send DOWN status
                sendTunnelStatusUpdate(Tunnel.State.DOWN.name, "Disconnected")
            }
            pfd?.close()
            pfd = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
            sendTunnelStatusUpdate(Tunnel.State.DOWN.name, "Error: ${e.message}")
        } finally {
            stopForeground(true)
            // stopSelf() will be called if tunnel state becomes DOWN and no new connect command is issued.
            // If tunnel was never properly started, ensure we stop.
            if (tunnel == null || backend?.getState(tunnel!!) == Tunnel.State.DOWN) {
                 Log.d(TAG, "Service stopping itself as tunnel is down.")
                 stopSelf()
            }
        }
    }

    override fun onStateChange(newState: Tunnel.State) {
        Log.d(TAG, "Tunnel state changed: $newState")
        val notificationMessage: String
        val broadcastMessage: String?

        when (newState) {
            Tunnel.State.UP -> {
                notificationMessage = "Connected"
                broadcastMessage = "Status: Connected"
            }
            Tunnel.State.DOWN -> {
                notificationMessage = "Disconnected"
                broadcastMessage = "Status: Disconnected"
                // Service should stop itself if it's truly down and not attempting to reconnect.
                // This is handled in stopVpn now.
            }
            Tunnel.State.TOGGLING -> {
                // This state is usually brief. Notification might show "Connecting..." or "Disconnecting..."
                // The broadcast sent from startVpn/stopVpn is more descriptive.
                notificationMessage = if (backend?.getState(tunnel!!) == Tunnel.State.UP) "Disconnecting..." else "Connecting..."
                broadcastMessage = if (backend?.getState(tunnel!!) == Tunnel.State.UP) "Status: Disconnecting..." else "Status: Connecting..."
            }
            else -> { // Should not happen with current GoBackend states
                notificationMessage = "Unknown state: $newState"
                broadcastMessage = "Status: Unknown ($newState)"
            }
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(notificationMessage))
        sendTunnelStatusUpdate(newState.name, broadcastMessage)

        if (newState == Tunnel.State.DOWN) {
            Log.d(TAG, "Tunnel is DOWN, ensuring service stops if not restarted.")
            stopForeground(true) // Remove notification
            stopSelf() // Stop the service itself
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "VpnService destroyed")
        // Ensure cleanup, though stopVpn() should have handled most of it.
        // If backend is not null and tunnel exists, try to bring it down one last time.
        if (tunnel != null && backend != null && backend?.getState(tunnel!!) != Tunnel.State.DOWN) {
            Log.w(TAG, "Service destroyed while tunnel was not DOWN. Forcing DOWN.")
            backend?.setState(tunnel!!, Tunnel.State.DOWN, null)
        }
        pfd?.close()
        backend?.shutdown() // If backend has a shutdown method
        super.onDestroy()
    }
}
