package com.guidaco.guidaphoneapp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.OutputStreamWriter
import java.util.UUID
import kotlin.concurrent.thread

class BluetoothWifiClient {
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val serviceUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID

    fun sendCredentials(device: BluetoothDevice, ssid: String, password: String, onResult: (Boolean, String) -> Unit) {
        // DEBUG: Log exactly what we're about to send
        Log.i("BluetoothWifiClient", "=== SENDING CREDENTIALS DEBUG ===")
        Log.i("BluetoothWifiClient", "SSID parameter: '$ssid' (length: ${ssid.length})")
        Log.i("BluetoothWifiClient", "Password parameter: '$password' (length: ${password.length})")
        Log.i("BluetoothWifiClient", "Target device: ${device.name} (${device.address})")
        
        if (adapter == null) {
            Log.e("BluetoothWifiClient", "Bluetooth not supported on this device")
            onResult(false, "Bluetooth not supported on this device")
            return
        }
        
        if (ssid.isEmpty()) {
            Log.e("BluetoothWifiClient", "ERROR: SSID is empty!")
            onResult(false, "ERROR: SSID is empty!")
            return
        }
        
        if (password.isEmpty()) {
            Log.e("BluetoothWifiClient", "ERROR: Password is empty!")
            onResult(false, "ERROR: Password is empty!")
            return
        }
        
        thread {
            var socket: BluetoothSocket? = null
            try {
                Log.i("BluetoothWifiClient", "Attempting to connect to ${device.name} (${device.address})")
                adapter.cancelDiscovery()
                
                // Wait a bit after pairing before attempting connection
                Thread.sleep(2000)
                
                // Try multiple connection methods
                socket = try {
                    Log.i("BluetoothWifiClient", "Trying createRfcommSocketToServiceRecord...")
                    device.createRfcommSocketToServiceRecord(serviceUUID)
                } catch (e: Exception) {
                    Log.w("BluetoothWifiClient", "Standard socket creation failed, trying reflection method...")
                    // Fallback method using reflection
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, 1) as BluetoothSocket
                }
                
                Log.i("BluetoothWifiClient", "Connecting to socket...")
                socket.connect()
                Log.i("BluetoothWifiClient", "Connected! Preparing to send credentials...")
                
                val writer = OutputStreamWriter(socket.outputStream)
                val message = "$ssid,$password"
                
                // DEBUG: Log exactly what we're sending
                Log.i("BluetoothWifiClient", "=== FINAL MESSAGE TO SEND ===")
                Log.i("BluetoothWifiClient", "Message: '$message'")
                Log.i("BluetoothWifiClient", "Message length: ${message.length}")
                Log.i("BluetoothWifiClient", "Message bytes: ${message.toByteArray().contentToString()}")
                
                writer.write("$message\n")
                writer.flush()
                
                Log.i("BluetoothWifiClient", "Data written to socket and flushed")
                
                // Give time for data to be sent
                Thread.sleep(1000)
                
                writer.close()
                socket.close()
                Log.i("BluetoothWifiClient", "Credentials sent successfully!")
                onResult(true, "Credentials sent successfully!")
                
            } catch (e: Exception) {
                Log.e("BluetoothWifiClient", "Error sending credentials: ${e.message}", e)
                try {
                    socket?.close()
                } catch (closeException: Exception) {
                    Log.w("BluetoothWifiClient", "Error closing socket: ${closeException.message}")
                }
                onResult(false, "Error: ${e.message}")
            }
        }
    }
} 