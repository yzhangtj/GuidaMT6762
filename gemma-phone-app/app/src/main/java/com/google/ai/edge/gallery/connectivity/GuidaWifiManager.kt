package com.google.ai.edge.gallery.connectivity

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
  private val connectivityManager =
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
    Log.i(TAG, "[WiFi] Starting connection to SSID: $ssid")
    _wifiState.value = WifiState.Status("Starting WiFi connection to $ssid...")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      connectWithWifiNetworkSpecifier(ssid, password)
    } else {
      connectWithLegacyMethod(ssid, password)
    }
  }

  private fun connectWithWifiNetworkSpecifier(ssid: String, password: String) {
    try {
      val specifier =
        WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()

      val networkRequest =
        NetworkRequest.Builder()
          .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
          .setNetworkSpecifier(specifier)
          .build()

      _wifiState.value = WifiState.Status("Requesting network for $ssid...")
      networkCallback =
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            _wifiState.value = WifiState.Connected(ssid)
          }

          override fun onUnavailable() {
            _wifiState.value = WifiState.Error("Network unavailable for $ssid")
          }

          override fun onLost(network: Network) {
            _wifiState.value = WifiState.Error("WiFi network lost: $ssid")
          }
        }

      connectivityManager.requestNetwork(networkRequest, networkCallback!!)
    } catch (e: Exception) {
      _wifiState.value = WifiState.Error("Exception: ${e.message}")
    }
  }

  private fun connectWithLegacyMethod(ssid: String, password: String) {
    try {
      val conf = WifiConfiguration().apply {
        SSID = "\"$ssid\""
        preSharedKey = "\"$password\""
      }
      val networkId = wifiManager.addNetwork(conf)
      if (networkId != -1) {
        wifiManager.enableNetwork(networkId, true)
        wifiManager.reconnect()
        _wifiState.value = WifiState.Connected(ssid)
      } else {
        _wifiState.value = WifiState.Error("Failed to add network for $ssid")
      }
    } catch (e: Exception) {
      _wifiState.value = WifiState.Error("Legacy connection exception: ${e.message}")
    }
  }

  fun disconnect() {
    networkCallback?.let { callback ->
      connectivityManager.unregisterNetworkCallback(callback)
      networkCallback = null
    }
    _wifiState.value = WifiState.Disconnected
  }

  fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

  fun enableWifi() {
    wifiManager.isWifiEnabled = true
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
      wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")
    }
  }

  companion object {
    private const val TAG = "GuidaWifiManager"
  }
}


