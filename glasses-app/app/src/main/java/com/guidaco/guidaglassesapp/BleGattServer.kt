package com.guidaco.guidaglassesapp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID

class BleGattServer(
    private val context: Context,
    private val onCredentialsReceived: (ssid: String, password: String, phoneApiUrl: String?) -> Unit
) {
    private val TAG = "BleGattServer"
    
    // Service UUID: 0xFFF0
    private val SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    
    // Characteristic UUIDs
    private val CHAR_DEVICE_INFO_UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB") // READ
    private val CHAR_WRITE_CREDENTIALS_UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB") // WRITE
    private val CHAR_NOTIFY_STATUS_UUID = UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB") // NOTIFY
    
    // CCCD Descriptor UUID
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private var notifyEnabled = false
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "Connection state changed: device=${device.address}, status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    notifyEnabled = false
                    Log.i(TAG, "Device connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    notifyEnabled = false
                    Log.i(TAG, "Device disconnected: ${device.address}")
                }
            }
        }
        
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.i(TAG, "Service added: status=$status, uuid=${service.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "GATT service added successfully")
            } else {
                Log.e(TAG, "Failed to add GATT service: $status")
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.i(TAG, "Characteristic read request: uuid=${characteristic.uuid}, offset=$offset")
            when (characteristic.uuid) {
                CHAR_DEVICE_INFO_UUID -> {
                    val deviceInfo = "GuidaGlasses-0001"
                    characteristic.value = deviceInfo.toByteArray()
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, deviceInfo.toByteArray())
                    Log.i(TAG, "Sent device info: $deviceInfo")
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.i(TAG, "Characteristic write request: uuid=${characteristic.uuid}, value=${value?.let { String(it) }}, offset=$offset")
            
            when (characteristic.uuid) {
                CHAR_WRITE_CREDENTIALS_UUID -> {
                    if (value != null && value.isNotEmpty()) {
                        val receivedData = String(value)
                        Log.i(TAG, "=== GLASSES APP RECEIVING CREDENTIALS (BLE) ===")
                        Log.i(TAG, "Raw received data: '$receivedData'")
                        Log.i(TAG, "Data length: ${receivedData.length}")
                        
                        try {
                            val parts = receivedData.trim().split(",")
                            Log.i(TAG, "Split parts count: ${parts.size}")
                            for (i in parts.indices) {
                                Log.i(TAG, "Part[$i]: '${parts[i]}'")
                            }
                            
                            if (parts.size >= 2) {
                                val ssid = parts[0].trim()
                                val password = parts[1].trim()
                                val phoneUrl = if (parts.size >= 3) parts[2].trim() else null
                                
                                Log.i(TAG, "=== FINAL PARSED CREDENTIALS ===")
                                Log.i(TAG, "SSID: '$ssid' (length: ${ssid.length})")
                                Log.i(TAG, "Password: '$password' (length: ${password.length})")
                                if (!phoneUrl.isNullOrEmpty()) {
                                    Log.i(TAG, "Phone API URL: '$phoneUrl'")
                                }
                                
                                if (ssid.isEmpty() || password.isEmpty()) {
                                    Log.e(TAG, "ERROR: SSID or password is empty!")
                                    sendNotification("ERROR: Empty credentials")
                                } else {
                                    // Send OK notification
                                    sendNotification("OK")
                                    // Call callback on main thread
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        onCredentialsReceived(ssid, password, phoneUrl)
                                    }
                                }
                            } else {
                                Log.e(TAG, "Invalid credentials format - expected at least 2 parts, got ${parts.size}")
                                sendNotification("ERROR: Invalid format")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing credentials: ${e.message}", e)
                            sendNotification("ERROR: Parse failed")
                        }
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                }
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.i(TAG, "Descriptor write request: uuid=${descriptor.uuid}, value=${value?.contentToString()}")
            
            if (descriptor.uuid == CCCD_UUID && value != null) {
                val notificationEnabled = value[0].toInt() and 0x01 != 0
                notifyEnabled = notificationEnabled
                Log.i(TAG, "CCCD write: notifications ${if (notificationEnabled) "enabled" else "disabled"}")
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }
        
        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            Log.i(TAG, "Execute write: requestId=$requestId, execute=$execute")
            gattServer?.sendResponse(device, requestId, if (execute) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
        }
    }
    
    fun start(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        
        if (adapter == null) {
            Log.e(TAG, "Bluetooth adapter not available")
            return false
        }
        
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not enabled")
            return false
        }
        
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }
        
        // Create service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        // Characteristic 0xFFF1: READ (Device Info)
        val charDeviceInfo = BluetoothGattCharacteristic(
            CHAR_DEVICE_INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(charDeviceInfo)
        
        // Characteristic 0xFFF2: WRITE (Credentials)
        val charWriteCredentials = BluetoothGattCharacteristic(
            CHAR_WRITE_CREDENTIALS_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(charWriteCredentials)
        
        // Characteristic 0xFFF3: NOTIFY (Status)
        val charNotifyStatus = BluetoothGattCharacteristic(
            CHAR_NOTIFY_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccdDescriptor = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE)
        charNotifyStatus.addDescriptor(cccdDescriptor)
        service.addCharacteristic(charNotifyStatus)
        
        // Add service to GATT server
        val success = gattServer?.addService(service) ?: false
        if (success) {
            Log.i(TAG, "GATT server started successfully with service 0xFFF0")
        } else {
            Log.e(TAG, "Failed to add GATT service")
        }
        
        return success
    }
    
    fun stop() {
        notifyEnabled = false
        connectedDevice = null
        gattServer?.close()
        gattServer = null
        Log.i(TAG, "GATT server stopped")
    }
    
    private fun sendNotification(message: String) {
        if (!notifyEnabled) {
            Log.w(TAG, "Notifications not enabled, cannot send: $message")
            return
        }
        
        val device = connectedDevice
        if (device == null) {
            Log.w(TAG, "No connected device, cannot send notification: $message")
            return
        }
        
        val service = gattServer?.getService(SERVICE_UUID)
        val characteristic = service?.getCharacteristic(CHAR_NOTIFY_STATUS_UUID)
        
        if (characteristic != null) {
            characteristic.value = message.toByteArray()
            val sent = gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            Log.i(TAG, "Sent notification '$message': ${if (sent == true) "success" else "failed"}")
        } else {
            Log.e(TAG, "Characteristic 0xFFF3 not found, cannot send notification")
        }
    }
}

