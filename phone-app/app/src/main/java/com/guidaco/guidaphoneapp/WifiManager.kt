package com.guidaco.guidaphoneapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GuidaWifiManager(private val context: Context) {
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val _wifiState = MutableStateFlow<WifiState>(WifiState.Disconnected)
    val wifiState: StateFlow<WifiState> = _wifiState.asStateFlow()
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    sealed class WifiState {
        object Disconnected : WifiState()
        object Connecting : WifiState()
        data class Connected(val ssid: String) : WifiState()
        data class Error(val message: String) : WifiState()
        data class Status(val message: String) : WifiState()
    }
    
    fun connectToWifi(ssid: String, password: String) {
        Log.i("guida", "[WiFi] Starting connection to SSID: $ssid")
        _wifiState.value = WifiState.Status("Starting WiFi connection to $ssid...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.i("guida", "[WiFi] Using WifiNetworkSpecifier (Android 10+)")
            _wifiState.value = WifiState.Status("Using WifiNetworkSpecifier API...")
            connectWithWifiNetworkSpecifier(ssid, password)
        } else {
            Log.i("guida", "[WiFi] Using legacy WiFi method (pre-Android 10)")
            _wifiState.value = WifiState.Status("Using legacy WiFi method...")
            connectWithLegacyMethod(ssid, password)
        }
    }
    
    private fun connectWithWifiNetworkSpecifier(ssid: String, password: String) {
        try {
            Log.i("guida", "[WiFi] Building WifiNetworkSpecifier for $ssid")
            _wifiState.value = WifiState.Status("Building WifiNetworkSpecifier for $ssid...")
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()
            
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
            
            Log.i("guida", "[WiFi] Requesting network for $ssid")
            _wifiState.value = WifiState.Status("Requesting network for $ssid...")
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i("guida", "[WiFi] Network available: $ssid")
                    _wifiState.value = WifiState.Connected(ssid)
                }
                
                override fun onUnavailable() {
                    Log.e("guida", "[WiFi] Network unavailable: $ssid")
                    _wifiState.value = WifiState.Error("Network unavailable for $ssid")
                }
                
                override fun onLost(network: Network) {
                    Log.w("guida", "[WiFi] Network lost: $ssid")
                    _wifiState.value = WifiState.Error("WiFi network lost: $ssid")
                }
            }
            
            connectivityManager.requestNetwork(networkRequest, networkCallback!!)
            Log.i("guida", "[WiFi] Network request sent for $ssid")
            _wifiState.value = WifiState.Status("Network request sent for $ssid. If nothing happens, your device may not support user-driven WiFi requests.")
            
        } catch (e: Exception) {
            Log.e("guida", "[WiFi] Exception: ${e.message}")
            _wifiState.value = WifiState.Error("Exception: ${e.message}")
        }
    }
    
    private fun connectWithLegacyMethod(ssid: String, password: String) {
        try {
            Log.i("guida", "[WiFi] Building legacy WifiConfiguration for $ssid")
            _wifiState.value = WifiState.Status("Building legacy WifiConfiguration for $ssid...")
            val conf = WifiConfiguration()
            conf.SSID = "\"$ssid\""
            conf.preSharedKey = "\"$password\""
            
            val networkId = wifiManager.addNetwork(conf)
            if (networkId != -1) {
                Log.i("guida", "[WiFi] Enabling network $networkId for $ssid")
                wifiManager.enableNetwork(networkId, true)
                wifiManager.reconnect()
                _wifiState.value = WifiState.Connected(ssid)
            } else {
                Log.e("guida", "[WiFi] Failed to add network for $ssid")
                _wifiState.value = WifiState.Error("Failed to add network for $ssid")
            }
        } catch (e: Exception) {
            Log.e("guida", "[WiFi] Exception (legacy): ${e.message}")
            _wifiState.value = WifiState.Error("Legacy connection exception: ${e.message}")
        }
    }
    
    fun disconnect() {
        Log.i("guida", "[WiFi] Disconnecting from WiFi")
        networkCallback?.let { callback ->
            connectivityManager.unregisterNetworkCallback(callback)
            networkCallback = null
        }
        _wifiState.value = WifiState.Disconnected
    }
    
    fun isWifiEnabled(): Boolean {
        return wifiManager.isWifiEnabled
    }
    
    fun enableWifi() {
        wifiManager.isWifiEnabled = true
        Log.i("guida", "[WiFi] WiFi enabled")
    }
    
    fun getCurrentSsid(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                "Connected (SSID not available)"
            } else {
                null
            }
        } else {
            val wifiInfo = wifiManager.connectionInfo
            wifiInfo?.ssid?.removeSurrounding("\"")
        }
    }
}