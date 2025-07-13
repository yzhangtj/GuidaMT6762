package com.guidaco.guidaglassesapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
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
        Log.i("guida", "[WiFi] Using root method for silent WiFi connection")
        connectToWifiRoot(ssid, password) { success, message ->
            if (success) {
                _wifiState.value = WifiState.Connected(ssid)
            } else {
                _wifiState.value = WifiState.Error(message)
            }
        }
    }
    
    fun connectToWifiRoot(ssid: String, password: String, onResult: (Boolean, String) -> Unit) {
        try {
            Log.i("guida", "[WiFi] Attempting root WiFi connection for $ssid")
            val command = "cmd wifi connect-network \"$ssid\" wpa2 \"$password\""
            Log.i("guida", "[WiFi] Executing root command with input stream: $command")

            val process = Runtime.getRuntime().exec("su")
            val outputStream = process.outputStream
            val inputStream = process.inputStream.bufferedReader()
            val errorStream = process.errorStream.bufferedReader()

            // Write the command to the root shell
            outputStream.write("$command\n".toByteArray())
            outputStream.flush()
            outputStream.write("exit\n".toByteArray())
            outputStream.flush()
            outputStream.close()

            // Read output and error streams
            val output = inputStream.readText()
            val error = errorStream.readText()
            
            val exitCode = process.waitFor()

            val fullMessage = "Exit Code: $exitCode\nOutput: $output\nError: $error"
            Log.d("guida", "[WiFi] Root command result:\n$fullMessage")

            if (exitCode == 0) {
                Log.i("guida", "[WiFi] Root WiFi connection command succeeded")
                onResult(true, "Root WiFi connection command succeeded.")
            } else {
                Log.e("guida", "[WiFi] Root WiFi connection command failed.")
                onResult(false, "Root command failed: $fullMessage")
            }
        } catch (e: Exception) {
            Log.e("guida", "[WiFi] Root WiFi connection error: ${e.message}")
            onResult(false, "Root connection error: ${e.message}")
        }
    }
    
    fun disconnect() {
        Log.i("guida", "[WiFi] Disconnecting from WiFi")
        networkCallback?.let { callback ->
            try {
            connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w("guida", "[WiFi] Error unregistering network callback: ${e.message}")
            }
            networkCallback = null
        }
        _wifiState.value = WifiState.Disconnected
    }
    
    fun removeNetworkSuggestions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val suggestions = wifiManager.networkSuggestions
                if (suggestions.isNotEmpty()) {
                    val status = wifiManager.removeNetworkSuggestions(suggestions)
                    Log.i("guida", "[WiFi] Removed network suggestions, status: $status")
                }
            } catch (e: Exception) {
                Log.w("guida", "[WiFi] Error removing network suggestions: ${e.message}")
            }
        }
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
    
    fun removeNetwork(ssid: String): Boolean {
        return try {
            val configuredNetworks = wifiManager.configuredNetworks
            configuredNetworks?.forEach { config ->
                if (config.SSID == "\"$ssid\"") {
                    Log.i("guida", "[WiFi] Removing existing network: $ssid (ID: ${config.networkId})")
                    wifiManager.removeNetwork(config.networkId)
                    wifiManager.saveConfiguration()
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e("guida", "[WiFi] Error removing network: ${e.message}")
            false
        }
    }
}