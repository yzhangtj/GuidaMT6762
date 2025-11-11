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

  private val bleGattClient = BleGattClient(application)
  private val wifiManager = GuidaWifiManager(application)
  private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

  private val _devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
  val devices: StateFlow<List<BluetoothDevice>> = _devices.asStateFlow()

  private val _status = MutableStateFlow<String?>(null)
  val status: StateFlow<String?> = _status.asStateFlow()

  // Bluetooth adapter state (enabled/disabled) exposed to UI
  private val _bluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
  val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

  // Expose whether a send operation is in progress so the UI can show a spinner / disable inputs.
  private val _isSending = MutableStateFlow(false)
  val isSending: StateFlow<Boolean> = _isSending.asStateFlow()
  
  private val _isScanning = MutableStateFlow(false)
  val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

  val wifiState: StateFlow<GuidaWifiManager.WifiState> = wifiManager.wifiState

  init {
    // Listen for adapter state changes so UI updates live when user enables/disables Bluetooth.
    try {
      val filter = IntentFilter().apply {
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
      }
      application.registerReceiver(object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
          try {
            when (intent?.action) {
              BluetoothAdapter.ACTION_STATE_CHANGED -> {
                _bluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
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

  fun scanForDevices() {
    if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
      _status.value = "Bluetooth not enabled"
      return
    }
    
    _isScanning.value = true
    _devices.value = emptyList()
    _status.value = "Scanning for BLE devices with Service 0xFFF0..."
    
    val foundDevices = mutableListOf<BluetoothDevice>()
    bleGattClient.scanForDevices { device ->
      if (!foundDevices.any { it.address == device.address }) {
        foundDevices.add(device)
        _devices.value = foundDevices.toList()
        _status.value = "Found: ${device.name ?: device.address}"
      }
    }
    
    // Stop scanning after 15 seconds
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      _isScanning.value = false
      if (foundDevices.isEmpty()) {
        _status.value = "No devices found. Make sure glasses are in pairing mode (F1 long press)."
      } else {
        _status.value = "Found ${foundDevices.size} device(s)"
      }
    }, 15000)
  }

  fun refreshDevices() {
    // For BLE, we scan instead of listing paired devices
    scanForDevices()
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
    bleGattClient.sendCredentialsToDevice(device, ssid, password) { success, message ->
      _status.value = message
      _isSending.value = false
    }
  }

  fun setStatus(message: String) {
    _status.value = message
  }
  
  override fun onCleared() {
    super.onCleared()
    bleGattClient.disconnect()
  }
}


