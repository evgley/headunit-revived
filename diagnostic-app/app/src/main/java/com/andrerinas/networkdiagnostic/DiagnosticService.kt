package com.andrerinas.networkdiagnostic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

class DiagnosticService : Service() {

    companion object {
        private const val CHANNEL_ID = "diagnostic_service"
        private const val NOTIFICATION_ID = 1
        private const val WIFI_POLL_INTERVAL_MS = 2000L
    }

    inner class LocalBinder : Binder() {
        val service: DiagnosticService get() = this@DiagnosticService
    }

    private val binder = LocalBinder()
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private lateinit var telephonyManager: TelephonyManager

    var logger: DiagnosticLogger? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var wifiPollRunnable: Runnable? = null
    private var wifiBroadcastReceiver: BroadcastReceiver? = null
    private var telephonyCallback: TelephonyCallback? = null

    // Track state for delta logging
    private var lastRssi = Int.MIN_VALUE
    private var lastFrequency = -1
    private var lastLinkSpeed = -1
    private var lastSupplicantState: SupplicantState? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        logger = DiagnosticLogger(this).also { it.start() }
        logger?.log("SYSTEM", "Monitoring started")

        logInitialState()
        registerWifiNetworkCallback()
        registerCellularNetworkCallback()
        registerDefaultNetworkCallback()
        registerWifiBroadcasts()
        startWifiPolling()
        registerTelephonyCallback()

        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun stopMonitoring() {
        wifiPollRunnable?.let { handler.removeCallbacks(it) }
        wifiPollRunnable = null

        try { connectivityManager.unregisterNetworkCallback(wifiNetworkCallback) } catch (_: Exception) {}
        try { connectivityManager.unregisterNetworkCallback(cellularNetworkCallback) } catch (_: Exception) {}
        try { connectivityManager.unregisterNetworkCallback(defaultNetworkCallback) } catch (_: Exception) {}

        wifiBroadcastReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        wifiBroadcastReceiver = null

        telephonyCallback?.let {
            telephonyManager.unregisterTelephonyCallback(it)
        }
        telephonyCallback = null

        logger?.stop()
        logger = null
    }

    // --- Initial State ---

    private fun logInitialState() {
        val log = logger ?: return

        // WiFi state
        val wifiInfo = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (wifiInfo != null && wifiInfo.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            log.log("INIT", "Active network is WiFi")
            logWifiCapabilities("INIT", wifiInfo)
        }

        // Cellular state
        val dataState = when (telephonyManager.dataState) {
            TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
            TelephonyManager.DATA_CONNECTING -> "CONNECTING"
            TelephonyManager.DATA_CONNECTED -> "CONNECTED"
            TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
            else -> "UNKNOWN"
        }
        log.log("INIT", "Mobile data state: $dataState")

        // Default network
        val activeNetwork = connectivityManager.activeNetwork
        val activeCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        if (activeCaps != null) {
            val transport = when {
                activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
            log.log("INIT", "Default network transport: $transport")
        } else {
            log.log("INIT", "No active network")
        }
    }

    // --- WiFi Network Callback ---

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            logger?.log("WIFI_CB", "onAvailable — network=$network")
        }

        override fun onLost(network: Network) {
            logger?.log("WIFI_CB", "onLost — network=$network")
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            logger?.log("WIFI_CB", "onLosing — network=$network, maxMsToLive=$maxMsToLive")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            logWifiCapabilities("WIFI_CB", caps)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val addresses = linkProperties.linkAddresses.joinToString { it.address.hostAddress ?: "?" }
            val dns = linkProperties.dnsServers.joinToString { it.hostAddress ?: "?" }
            val routes = linkProperties.routes.joinToString { "${it.destination} via ${it.gateway?.hostAddress ?: "direct"}" }
            val mtu = linkProperties.mtu
            logger?.log("WIFI_CB", "onLinkPropertiesChanged — IPs=[$addresses] DNS=[$dns] MTU=$mtu routes=[$routes]")
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            logger?.log("WIFI_CB", "onBlockedStatusChanged — network=$network, blocked=$blocked")
        }
    }

    private fun registerWifiNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, wifiNetworkCallback)
    }

    // --- Cellular Network Callback ---

    private val cellularNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            logger?.log("CELL_CB", "onAvailable — network=$network")
        }

        override fun onLost(network: Network) {
            logger?.log("CELL_CB", "onLost — network=$network")
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            logger?.log("CELL_CB", "onLosing — network=$network, maxMsToLive=$maxMsToLive")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val downstream = caps.linkDownstreamBandwidthKbps
            val upstream = caps.linkUpstreamBandwidthKbps
            logger?.log("CELL_CB", "onCapabilitiesChanged — downstream=${downstream}kbps, upstream=${upstream}kbps")
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            logger?.log("CELL_CB", "onBlockedStatusChanged — network=$network, blocked=$blocked")
        }
    }

    private fun registerCellularNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        connectivityManager.registerNetworkCallback(request, cellularNetworkCallback)
    }

    // --- Default Network Callback ---

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            val transport = caps?.let { describeTransport(it) } ?: "UNKNOWN"
            logger?.log("DEFAULT", "Default network changed — network=$network, transport=$transport")
        }

        override fun onLost(network: Network) {
            logger?.log("DEFAULT", "Default network LOST — network=$network")
        }
    }

    private fun registerDefaultNetworkCallback() {
        connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback)
    }

    // --- WiFi Broadcasts ---

    private fun registerWifiBroadcasts() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                        val stateName = when (state) {
                            WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
                            WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
                            WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
                            WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
                            else -> "UNKNOWN($state)"
                        }
                        logger?.log("WIFI_BC", "WIFI_STATE_CHANGED: $stateName")
                    }

                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                        logger?.log("WIFI_BC", "NETWORK_STATE_CHANGED: state=${networkInfo?.state}, detailedState=${networkInfo?.detailedState}")
                    }

                    WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                        val supState = intent.getParcelableExtra<SupplicantState>(WifiManager.EXTRA_NEW_STATE)
                        val error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)
                        val errorStr = if (error != -1) ", error=$error" else ""
                        logger?.log("WIFI_BC", "SUPPLICANT_STATE_CHANGED: $supState$errorStr")
                    }

                    WifiManager.RSSI_CHANGED_ACTION -> {
                        val rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, 0)
                        logger?.log("WIFI_BC", "RSSI_CHANGED: $rssi dBm")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
        }

        @Suppress("DEPRECATION")
        registerReceiver(receiver, filter)
        wifiBroadcastReceiver = receiver
    }

    // --- WiFi Polling ---

    private fun startWifiPolling() {
        val runnable = object : Runnable {
            override fun run() {
                pollWifiInfo()
                handler.postDelayed(this, WIFI_POLL_INTERVAL_MS)
            }
        }
        wifiPollRunnable = runnable
        handler.postDelayed(runnable, WIFI_POLL_INTERVAL_MS)
    }

    private fun pollWifiInfo() {
        val network = connectivityManager.activeNetwork ?: return
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return

        val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps.transportInfo as? WifiInfo
        } else {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo
        } ?: return

        val rssi = wifiInfo.rssi
        val freq = wifiInfo.frequency
        val linkSpeed = wifiInfo.linkSpeed
        val supState = wifiInfo.supplicantState

        // Only log if something changed
        if (rssi != lastRssi || freq != lastFrequency || linkSpeed != lastLinkSpeed || supState != lastSupplicantState) {
            val txSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) wifiInfo.txLinkSpeedMbps else -1
            val rxSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) wifiInfo.rxLinkSpeedMbps else -1

            logger?.log("WIFI_POLL", "RSSI=${rssi}dBm freq=${freq}MHz link=${linkSpeed}Mbps tx=${txSpeed}Mbps rx=${rxSpeed}Mbps supplicant=$supState")

            lastRssi = rssi
            lastFrequency = freq
            lastLinkSpeed = linkSpeed
            lastSupplicantState = supState
        }
    }

    // --- Telephony Callback ---

    private fun registerTelephonyCallback() {
        val callback = object : TelephonyCallback(), TelephonyCallback.DataConnectionStateListener {
            override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                val stateName = when (state) {
                    TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
                    TelephonyManager.DATA_CONNECTING -> "CONNECTING"
                    TelephonyManager.DATA_CONNECTED -> "CONNECTED"
                    TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
                    TelephonyManager.DATA_HANDOVER_IN_PROGRESS -> "HANDOVER_IN_PROGRESS"
                    else -> "UNKNOWN($state)"
                }
                val typeName = when (networkType) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G-NR"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                    TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                    else -> "TYPE_$networkType"
                }
                logger?.log("TELEPHONY", "Data connection: $stateName ($typeName)")
            }
        }

        try {
            telephonyManager.registerTelephonyCallback(mainExecutor, callback)
            telephonyCallback = callback
        } catch (e: SecurityException) {
            logger?.log("TELEPHONY", "Cannot register: ${e.message}")
        }
    }

    // --- Helpers ---

    private fun logWifiCapabilities(tag: String, caps: NetworkCapabilities) {
        val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps.transportInfo as? WifiInfo
        } else null

        val parts = mutableListOf<String>()

        wifiInfo?.let { wi ->
            parts.add("SSID=${wi.ssid}")
            parts.add("BSSID=${wi.bssid}")
            parts.add("RSSI=${wi.rssi}dBm")
            parts.add("freq=${wi.frequency}MHz")
            parts.add("link=${wi.linkSpeed}Mbps")
            parts.add("supplicant=${wi.supplicantState}")
        }

        parts.add("downstream=${caps.linkDownstreamBandwidthKbps}kbps")
        parts.add("upstream=${caps.linkUpstreamBandwidthKbps}kbps")

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) parts.add("INTERNET")
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) parts.add("VALIDATED")
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) parts.add("NOT_METERED")

        logger?.log(tag, "Capabilities — ${parts.joinToString(", ")}")
    }

    private fun describeTransport(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "OTHER"
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
