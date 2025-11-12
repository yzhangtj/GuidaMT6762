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
                    Log.i(TAG, "Connected to GATT server, status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Request MTU for better throughput (optional but recommended)
                        gatt.requestMtu(512)
                        gatt.discoverServices()
                    } else {
                        Log.e(TAG, "Connection failed with status: $status")
                        onCredentialsSent?.invoke(false, "Connection failed: $status")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server, status=$status")
                    this@BleGattClient.gatt = null
                    connectedDevice = null
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        onCredentialsSent?.invoke(false, "Disconnected unexpectedly: $status")
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "Services discovered: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    Log.i(TAG, "Found service 0xFFF0")
                    // Enable notifications first, credentials will be sent after CCCD is written
                    enableNotifications(gatt, service)
                    // Don't send credentials here - wait for notifications to be enabled
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
                // Send credentials after notifications are enabled
                if (pendingSsid != null && pendingPassword != null) {
                    sendCredentialsWithData(gatt, pendingSsid!!, pendingPassword!!)
                    pendingSsid = null
                    pendingPassword = null
                } else {
                    Log.w(TAG, "Notifications enabled but no pending credentials to send")
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed to write CCCD descriptor: $status")
                onCredentialsSent?.invoke(false, "Failed to enable notifications: $status")
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed: mtu=$mtu, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU negotiation successful: $mtu bytes")
            } else {
                Log.w(TAG, "MTU negotiation failed: $status, using default MTU")
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
    
    // Removed sendCredentials - credentials are now sent directly from onDescriptorWrite
    
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
                // Check if notifications are already enabled
                val notifyChar = service.getCharacteristic(CHAR_NOTIFY_STATUS_UUID)
                val descriptor = notifyChar?.getDescriptor(CCCD_UUID)
                val notificationsEnabled = descriptor != null && 
                    (descriptor.value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true)
                
                if (notificationsEnabled) {
                    // Notifications already enabled, send credentials directly
                    Log.i(TAG, "Notifications already enabled, sending credentials directly")
                    sendCredentialsWithData(gatt!!, ssid, password)
                } else {
                    // Enable notifications first, credentials will be sent after CCCD write
                    Log.i(TAG, "Enabling notifications, credentials will be sent after")
                    pendingSsid = ssid
                    pendingPassword = password
                    enableNotifications(gatt!!, service)
                }
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
        if (service == null) {
            Log.e(TAG, "Service 0xFFF0 not found")
            onCredentialsSent?.invoke(false, "Service 0xFFF0 not found")
            return
        }
        
        val characteristic = service.getCharacteristic(CHAR_WRITE_CREDENTIALS_UUID)
        if (characteristic == null) {
            Log.e(TAG, "Characteristic 0xFFF2 not found")
            onCredentialsSent?.invoke(false, "Characteristic 0xFFF2 not found")
            return
        }
        
        // Check characteristic properties
        val properties = characteristic.properties
        Log.i(TAG, "Characteristic 0xFFF2 properties: $properties")
        val supportsWrite = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val supportsWriteNoResponse = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        Log.i(TAG, "Supports WRITE: $supportsWrite, Supports WRITE_NO_RESPONSE: $supportsWriteNoResponse")
        
        val phoneUrl = getPhoneApiUrl()
        val message = "$ssid,$password,$phoneUrl"
        Log.i(TAG, "=== SENDING CREDENTIALS (BLE) ===")
        Log.i(TAG, "Message: '$message' (length: ${message.length} bytes)")
        
        characteristic.value = message.toByteArray()
        
        // Try WRITE_TYPE_NO_RESPONSE first if supported (more efficient for BLE)
        // Then fall back to WRITE_TYPE_DEFAULT
        var success = false
        val writeTypes = mutableListOf<Int>()
        if (supportsWriteNoResponse) {
            writeTypes.add(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }
        if (supportsWrite) {
            writeTypes.add(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        
        if (writeTypes.isEmpty()) {
            Log.e(TAG, "Characteristic does not support any write type")
            onCredentialsSent?.invoke(false, "Characteristic does not support write")
            return
        }
        
        for (writeType in writeTypes) {
            characteristic.writeType = writeType
            Log.i(TAG, "Attempting write with type: $writeType")
            success = gatt.writeCharacteristic(characteristic)
            if (success) {
                Log.i(TAG, "Write initiated successfully with writeType=$writeType, waiting for callback...")
                break
            } else {
                Log.w(TAG, "Write initiation failed with writeType=$writeType, trying next type...")
            }
        }
        
        if (!success) {
            Log.e(TAG, "Failed to initiate write with all write types")
            onCredentialsSent?.invoke(false, "Failed to write characteristic - check connection state")
        }
        // Note: Actual success/failure will be reported in onCharacteristicWrite callback
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

