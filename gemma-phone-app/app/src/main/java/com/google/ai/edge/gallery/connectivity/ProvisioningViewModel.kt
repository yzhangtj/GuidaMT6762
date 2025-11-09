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
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver

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

  // Bluetooth adapter state (enabled/disabled) exposed to UI
  private val _bluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
  val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

  // Convenience: whether a device is currently bonded (paired)
  fun isDeviceBonded(device: BluetoothDevice): Boolean {
    return try {
      bluetoothAdapter?.bondedDevices?.any { it.address == device.address } == true
    } catch (e: Exception) {
      false
    }
  }

  // Expose whether a send operation is in progress so the UI can show a spinner / disable inputs.
  private val _isSending = MutableStateFlow(false)
  val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

  val wifiState: StateFlow<GuidaWifiManager.WifiState> = wifiManager.wifiState

  init {
    refreshDevices()
    // Listen for adapter state changes so UI updates live when user enables/disables Bluetooth.
    try {
      val filter = IntentFilter().apply {
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
      }
      application.registerReceiver(object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
          try {
            when (intent?.action) {
              BluetoothAdapter.ACTION_STATE_CHANGED -> {
                _bluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
                refreshDevices()
              }
              BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                refreshDevices()
              }
            }
          } catch (e: Exception) {
            _status.value = "Error observing Bluetooth state: ${e.message}"
          }
        }
      }, filter)
    } catch (e: Exception) {
      _status.value = "Could not register Bluetooth state receiver: ${e.message}"
    }
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
    _bluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
  }

  fun getSuggestedSsid(): String? = wifiManager.getCurrentSsid()

  fun enableWifiIfNeeded() {
    if (!wifiManager.isWifiEnabled()) {
      wifiManager.enableWifi()
    }
  }

  fun sendCredentials(device: BluetoothDevice, ssid: String, password: String) {
    _status.value = "Sending credentials to ${device.name}..."
    _isSending.value = true
    // Pass application context so the client can compute and append the phone-local URL token
    bluetoothClient.sendCredentials(device, ssid, password, getApplication()) { success, message ->
      _status.value = message
      _isSending.value = false
      if (success) {
        refreshDevices()
      }
    }
  }

  fun setStatus(message: String) {
    _status.value = message
  }
}


