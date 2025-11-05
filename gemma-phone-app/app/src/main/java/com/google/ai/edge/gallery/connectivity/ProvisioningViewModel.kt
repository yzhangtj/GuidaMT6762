package com.google.ai.edge.gallery.connectivity

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class ProvisioningViewModel
@Inject
constructor(application: Application) : AndroidViewModel(application) {

  private val bluetoothClient = BluetoothWifiClient()
  private val wifiManager = GuidaWifiManager(application)
  private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

  private val _devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
  val devices: StateFlow<List<BluetoothDevice>> = _devices.asStateFlow()

  private val _status = MutableStateFlow<String?>(null)
  val status: StateFlow<String?> = _status.asStateFlow()

  val wifiState: StateFlow<GuidaWifiManager.WifiState> = wifiManager.wifiState

  init {
    refreshDevices()
  }

  fun refreshDevices() {
    val bonded =
      try {
        bluetoothAdapter?.bondedDevices?.toList().orEmpty()
      } catch (securityException: SecurityException) {
        _status.value = "Bluetooth permission required"
        emptyList()
      }
    _devices.value = bonded.sortedBy { it.name ?: it.address }
  }

  fun getSuggestedSsid(): String? = wifiManager.getCurrentSsid()

  fun enableWifiIfNeeded() {
    if (!wifiManager.isWifiEnabled()) {
      wifiManager.enableWifi()
    }
  }

  fun sendCredentials(device: BluetoothDevice, ssid: String, password: String) {
    _status.value = "Sending credentials to ${device.name}..."
    bluetoothClient.sendCredentials(device, ssid, password) { success, message ->
      _status.value = message
      if (success) {
        refreshDevices()
      }
    }
  }

  fun setStatus(message: String) {
    _status.value = message
  }
}


