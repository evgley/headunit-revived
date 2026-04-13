package com.andrerinas.headunitrevived.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.utils.AppLog
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import java.net.Socket

/**
 * Manages Google Nearby Connections on the Headunit (Tablet).
 * The Tablet acts as a DISCOVERER only.
 */
class NearbyManager(
    private val context: Context, 
    private val scope: CoroutineScope,
    private val onSocketReady: (Socket) -> Unit
) {

    data class DiscoveredEndpoint(val id: String, val name: String)

    companion object {
        private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
        val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.borconi.emil.hur"
    private var isRunning = false
    private var activeNearbySocket: NearbySocket? = null
    private var activePipes: Array<android.os.ParcelFileDescriptor>? = null

    fun start() {
        if (!hasRequiredPermissions()) {
            AppLog.w("NearbyManager: Missing required location/bluetooth permissions. Skipping start.")
            return
        }
        if (isRunning) {
            AppLog.i("NearbyManager: Already running discovery.")
            return
        }
        AppLog.i("NearbyManager: Starting Nearby (Discoverer only)...")
        isRunning = true
        _discoveredEndpoints.value = emptyList()
        startDiscovery()
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasCoarse && !hasFine) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasAdvertise = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasAdvertise || !hasScan || !hasConnect) return false
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            if (!hasNearby) return false
        }

        return true
    }

    fun stop() {
        if (!isRunning) return
        AppLog.i("NearbyManager: Stopping discovery...")
        isRunning = false
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _discoveredEndpoints.value = emptyList()
    }

    /**
     * Manually initiate a connection to a specific discovered endpoint.
     * Called from HomeFragment when user taps a device in the list.
     */
    fun connectToEndpoint(endpointId: String) {
        AppLog.i("NearbyManager: Requesting connection to $endpointId...")
        
        connectionsClient.requestConnection(android.os.Build.MODEL, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e -> 
                AppLog.e("NearbyManager: Failed to request connection: ${e.message}") 
            }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        AppLog.i("NearbyManager: Requesting Discovery with SERVICE_ID: $SERVICE_ID (Strategy: P2P_CLUSTER)")
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { AppLog.d("NearbyManager: [OK] Discovery started.") }
            .addOnFailureListener { e -> 
                AppLog.e("NearbyManager: [ERROR] Discovery failed: ${e.message}") 
                isRunning = false
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            AppLog.i("NearbyManager: Endpoint FOUND: ${info.endpointName} ($endpointId)")
            val current = _discoveredEndpoints.value.toMutableList()
            if (current.none { it.id == endpointId }) {
                current.add(DiscoveredEndpoint(endpointId, info.endpointName))
                _discoveredEndpoints.value = current
            }
        }

        override fun onEndpointLost(endpointId: String) {
            AppLog.i("NearbyManager: Endpoint LOST: $endpointId")
            val current = _discoveredEndpoints.value.toMutableList()
            current.removeAll { it.id == endpointId }
            _discoveredEndpoints.value = current
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            AppLog.i("NearbyManager: Connection INITIATED with $endpointId (${info.endpointName}). Token: ${info.authenticationToken}")
            AppLog.i("NearbyManager: Automatically ACCEPTING connection...")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e -> AppLog.e("NearbyManager: Failed to accept connection: ${e.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = result.status
            AppLog.i("NearbyManager: Connection RESULT for $endpointId: StatusCode=${status.statusCode} (${status.statusMessage})")
            
            when (status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    AppLog.i("NearbyManager: Successfully CONNECTED to $endpointId. Waiting 500ms before initiating tunnel...")
                    
                    scope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(500)
                        
                        val socket = NearbySocket()
                        activeNearbySocket = socket
                        
                        // 1. Create outgoing pipe (Tablet -> Phone)
                        val pipes = android.os.ParcelFileDescriptor.createPipe()
                        activePipes = pipes
                        socket.outputStreamWrapper = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])

                        // Initiating stream tunnel to Phone
                        AppLog.i("NearbyManager: Initiating symmetric stream tunnel to $endpointId...")
                        
                        // We pass the PFD directly to Nearby - it's more robust than passing an InputStream
                        val tabletToPhonePayload = Payload.fromStream(pipes[0])
                        
                        connectionsClient.sendPayload(endpointId, tabletToPhonePayload)
                            .addOnSuccessListener { 
                                AppLog.i("NearbyManager: [OK] Tablet->Phone payload registered successfully.") 
                                // Test basic BYTES communication
                                connectionsClient.sendPayload(endpointId, Payload.fromBytes("PING_FROM_TABLET".toByteArray()))
                            }
                            .addOnFailureListener { e -> 
                                AppLog.e("NearbyManager: [ERROR] Failed to send payload: ${e.message}") 
                            }
                        
                        // CRITICAL: Start handshake immediately so data is waiting in the pipe
                        onSocketReady(socket)
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> AppLog.w("NearbyManager: Connection REJECTED by $endpointId")
                ConnectionsStatusCodes.STATUS_ERROR -> AppLog.e("NearbyManager: Connection ERROR with $endpointId")
                else -> AppLog.w("NearbyManager: Unknown connection result code: ${status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            AppLog.i("NearbyManager: DISCONNECTED from $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            AppLog.i("NearbyManager: Payload RECEIVED from $endpointId. Type: ${payload.type}")
            if (payload.type == Payload.Type.STREAM) {
                AppLog.i("NearbyManager: Received incoming STREAM payload. Completing bidirectional tunnel.")
                activeNearbySocket?.inputStreamWrapper = payload.asStream()?.asInputStream()
            } else if (payload.type == Payload.Type.BYTES) {
                val msg = String(payload.asBytes() ?: byteArrayOf())
                AppLog.i("NearbyManager: Received BYTES payload: $msg")
            }
        }


        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                AppLog.d("NearbyManager: Payload transfer SUCCESS for endpoint $endpointId")
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                AppLog.e("NearbyManager: Payload transfer FAILURE for endpoint $endpointId")
            }
        }
    }
}
