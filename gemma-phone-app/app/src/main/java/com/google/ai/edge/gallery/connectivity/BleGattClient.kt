package com.google.ai.edge.gallery.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.util.UUID

class BleGattClient(private val context: Context) {
    private val TAG = "BleGattClient"
    
    // Service UUID: 0xFFF0
    private val SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    
    // Characteristic UUIDs
    private val CHAR_DEVICE_INFO_UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB") // READ
    private val CHAR_WRITE_CREDENTIALS_UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB") // WRITE
    private val CHAR_NOTIFY_STATUS_UUID = UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB") // NOTIFY
    
    // CCCD Descriptor UUID
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var isScanning = false
    private var onCredentialsSent: ((Boolean, String) -> Unit)? = null
    private var onDeviceFound: ((BluetoothDevice) -> Unit)? = null
    private var pendingSsid: String? = null
    private var pendingPassword: String? = null
    
    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "Connection state changed: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server")
                    this@BleGattClient.gatt = null
                    connectedDevice = null
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "Services discovered: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    Log.i(TAG, "Found service 0xFFF0")
                    enableNotifications(gatt, service)
                    // If we have pending credentials, send them now
                    if (pendingSsid != null && pendingPassword != null) {
                        sendCredentialsWithData(gatt, pendingSsid!!, pendingPassword!!)
                        pendingSsid = null
                        pendingPassword = null
                    }
                } else {
                    Log.e(TAG, "Service 0xFFF0 not found")
                    onCredentialsSent?.invoke(false, "Service 0xFFF0 not found")
                }
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                onCredentialsSent?.invoke(false, "Service discovery failed: $status")
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.i(TAG, "Characteristic changed: uuid=${characteristic.uuid}")
            if (characteristic.uuid == CHAR_NOTIFY_STATUS_UUID) {
                val value = characteristic.getStringValue(0)
                Log.i(TAG, "Received notification: $value")
                if (value != null && value.trim().equals("OK", ignoreCase = true)) {
                    onCredentialsSent?.invoke(true, "Credentials sent successfully (ACK received)")
                } else {
                    onCredentialsSent?.invoke(false, "Received error: $value")
                }
            }
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.i(TAG, "Characteristic write completed: uuid=${characteristic.uuid}, status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed with status: $status")
                onCredentialsSent?.invoke(false, "Write failed: $status")
            }
            // Note: We wait for notification (onCharacteristicChanged) to confirm success
        }
        
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.i(TAG, "Descriptor write completed: uuid=${descriptor.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) {
                Log.i(TAG, "Notifications enabled, sending credentials")
                sendCredentials(gatt)
            }
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.i(TAG, "BLE device found: ${device.name} (${device.address})")
            
            // Check if device advertises our service UUID
            val serviceUuids = result.scanRecord?.serviceUuids
            if (serviceUuids != null) {
                val hasService = serviceUuids.any { it.uuid == SERVICE_UUID }
                if (hasService) {
                    Log.i(TAG, "Found device with Service 0xFFF0: ${device.name} (${device.address})")
                    onDeviceFound?.invoke(device)
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            isScanning = false
            // Don't call onCredentialsSent here - that's only for credential sending
        }
    }
    
    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }
    
    fun scanForDevices(onDeviceFound: (BluetoothDevice) -> Unit) {
        this.onDeviceFound = onDeviceFound
        
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not enabled")
            return
        }
        
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BLE scanner not available")
            return
        }
        
        // Create scan filter for Service UUID 0xFFF0
        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        Log.i(TAG, "Starting BLE scan for Service 0xFFF0...")
        isScanning = true
        bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
        
        // Stop scan after 30 seconds timeout
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isScanning) {
                Log.w(TAG, "Scan timeout, stopping scan")
                stopScan()
            }
        }, 30000)
    }
    
    fun scanAndConnect(onCredentialsSent: (Boolean, String) -> Unit) {
        this.onCredentialsSent = onCredentialsSent
        
        scanForDevices { device ->
            stopScan()
            connectToDevice(device)
            // Store credentials to send after connection
            // Note: This method expects credentials to be set via sendCredentialsToDevice
        }
    }
    
    private fun stopScan() {
        if (isScanning) {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Log.i(TAG, "BLE scan stopped")
        }
    }
    
    private fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "Connecting to device: ${device.address}")
        connectedDevice = device
        gatt = device.connectGatt(context, false, gattCallback)
    }
    
    private fun enableNotifications(gatt: BluetoothGatt, service: BluetoothGattService) {
        val characteristic = service.getCharacteristic(CHAR_NOTIFY_STATUS_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Characteristic 0xFFF3 not found")
            onCredentialsSent?.invoke(false, "Characteristic 0xFFF3 not found")
            return
        }
        
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "CCCD descriptor not found")
            onCredentialsSent?.invoke(false, "CCCD descriptor not found")
            return
        }
        
        // Enable notifications
        gatt.setCharacteristicNotification(characteristic, true)
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
        Log.i(TAG, "Enabling notifications...")
    }
    
    private fun sendCredentials(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(CHAR_WRITE_CREDENTIALS_UUID)
        
        if (characteristic == null) {
            Log.e(TAG, "Characteristic 0xFFF2 not found")
            onCredentialsSent?.invoke(false, "Characteristic 0xFFF2 not found")
            return
        }
        
        // Get WiFi credentials and phone URL
        val ssid = getSuggestedSsid() ?: ""
        val password = "" // This should come from user input or stored value
        val phoneUrl = getPhoneApiUrl()
        
        if (ssid.isEmpty() || password.isEmpty()) {
            Log.e(TAG, "SSID or password is empty")
            onCredentialsSent?.invoke(false, "SSID or password is empty")
            return
        }
        
        val message = "$ssid,$password,$phoneUrl"
        Log.i(TAG, "=== SENDING CREDENTIALS (BLE) ===")
        Log.i(TAG, "Message: '$message'")
        
        characteristic.value = message.toByteArray()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        
        val success = gatt.writeCharacteristic(characteristic)
        if (!success) {
            Log.e(TAG, "Failed to write characteristic")
            onCredentialsSent?.invoke(false, "Failed to write characteristic")
        } else {
            Log.i(TAG, "Credentials written, waiting for notification...")
            // Wait for notification callback (onCharacteristicChanged) for ACK
        }
    }
    
    fun sendCredentialsToDevice(
        device: BluetoothDevice,
        ssid: String,
        password: String,
        onCredentialsSent: (Boolean, String) -> Unit
    ) {
        this.onCredentialsSent = onCredentialsSent
        
        if (ssid.isEmpty() || password.isEmpty()) {
            onCredentialsSent(false, "SSID or password is empty")
            return
        }
        
        // If already connected to this device, send directly
        if (connectedDevice?.address == device.address && gatt != null) {
            val service = gatt?.getService(SERVICE_UUID)
            if (service != null) {
                enableNotifications(gatt!!, service)
                sendCredentialsWithData(gatt!!, ssid, password)
            } else {
                // Services not discovered yet, store credentials to send after discovery
                pendingSsid = ssid
                pendingPassword = password
            }
            return
        }
        
        // Otherwise connect first
        pendingSsid = ssid
        pendingPassword = password
        connectToDevice(device)
    }
    
    private fun sendCredentialsWithData(gatt: BluetoothGatt, ssid: String, password: String) {
        val service = gatt.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(CHAR_WRITE_CREDENTIALS_UUID)
        
        if (characteristic == null) {
            Log.e(TAG, "Characteristic 0xFFF2 not found")
            onCredentialsSent?.invoke(false, "Characteristic 0xFFF2 not found")
            return
        }
        
        val phoneUrl = getPhoneApiUrl()
        val message = "$ssid,$password,$phoneUrl"
        Log.i(TAG, "=== SENDING CREDENTIALS (BLE) ===")
        Log.i(TAG, "Message: '$message'")
        
        characteristic.value = message.toByteArray()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        
        val success = gatt.writeCharacteristic(characteristic)
        if (!success) {
            Log.e(TAG, "Failed to write characteristic")
            onCredentialsSent?.invoke(false, "Failed to write characteristic")
        } else {
            Log.i(TAG, "Credentials written, waiting for notification...")
        }
    }
    
    private fun getSuggestedSsid(): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")
    }
    
    private fun getPhoneApiUrl(): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ip = (ipInt and 0xff).toString() + "." + ((ipInt shr 8) and 0xff) + "." + 
                         ((ipInt shr 16) and 0xff) + "." + ((ipInt shr 24) and 0xff)
                return "http://$ip:5000"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not obtain Wi-Fi IP: ${e.message}")
        }
        return "http://192.168.43.1:5000"
    }
    
    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        connectedDevice = null
    }
}

