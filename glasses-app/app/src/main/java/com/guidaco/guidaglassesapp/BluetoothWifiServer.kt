package com.guidaco.guidaglassesapp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.concurrent.thread

class BluetoothWifiServer(
    // Updated callback includes optional phoneApiUrl (third token) when provided by phone
    private val onCredentialsReceived: (ssid: String, password: String, phoneApiUrl: String?) -> Unit
) {
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var serverSocket: BluetoothServerSocket? = null
    private var running = false
    private val serviceName = "GuidaWifiProvision"
    private val serviceUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID

    fun start() {
        if (adapter == null) {
            Log.e("BluetoothWifiServer", "Bluetooth not supported on this device")
            return
        }
        
        // Stop any existing server first
        stop()
        
        running = true
        thread {
            var clientSocket: BluetoothSocket? = null
            try {
                Log.i("BluetoothWifiServer", "Creating server socket...")
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(serviceName, serviceUUID)
                Log.i("BluetoothWifiServer", "Bluetooth server started, waiting for connection...")
                
                // Accept connection with timeout handling
                clientSocket = serverSocket!!.accept()
                Log.i("BluetoothWifiServer", "Bluetooth client connected from: ${clientSocket.remoteDevice.name}")
                
                val reader = BufferedReader(InputStreamReader(clientSocket.inputStream))
                Log.i("BluetoothWifiServer", "Reading credentials...")
                
                val line = reader.readLine()
                
                // DEBUG: Comprehensive logging of received data
                Log.i("BluetoothWifiServer", "=== GLASSES APP RECEIVING DEBUG ===")
                Log.i("BluetoothWifiServer", "Raw received line: '$line'")
                Log.i("BluetoothWifiServer", "Line is null: ${line == null}")
                Log.i("BluetoothWifiServer", "Line length: ${line?.length ?: 0}")
                if (line != null) {
                    Log.i("BluetoothWifiServer", "Line bytes: ${line.toByteArray().contentToString()}")
                    Log.i("BluetoothWifiServer", "Line isEmpty: ${line.isEmpty()}")
                    Log.i("BluetoothWifiServer", "Line isBlank: ${line.isBlank()}")
                }
                
                if (line != null && line.isNotEmpty()) {
                    val trimmedLine = line.trim()
                    Log.i("BluetoothWifiServer", "Trimmed line: '$trimmedLine'")
                    
                    val parts = trimmedLine.split(",")
                    Log.i("BluetoothWifiServer", "Split parts count: ${parts.size}")
                    for (i in parts.indices) {
                        Log.i("BluetoothWifiServer", "Part[$i]: '${parts[i]}'")
                    }
                    // Accept either 2-part (ssid,password) or 3-part (ssid,password,phoneUrl)
                    if (parts.size >= 2) {
                        val ssid = parts[0].trim()
                        val password = parts[1].trim()
                        val phoneUrl = if (parts.size >= 3) parts[2].trim() else null
                        Log.i("BluetoothWifiServer", "=== FINAL PARSED CREDENTIALS ===")
                        Log.i("BluetoothWifiServer", "SSID: '$ssid' (length: ${ssid.length})")
                        Log.i("BluetoothWifiServer", "Password: '$password' (length: ${password.length})")
                        if (!phoneUrl.isNullOrEmpty()) {
                            Log.i("BluetoothWifiServer", "Phone API URL token received: '$phoneUrl'")
                        }
                        
                        if (ssid.isEmpty()) {
                            Log.e("BluetoothWifiServer", "ERROR: Parsed SSID is empty!")
                        }
                        if (password.isEmpty()) {
                            Log.e("BluetoothWifiServer", "ERROR: Parsed password is empty!")
                        }
                        
                        Log.i("BluetoothWifiServer", "Calling onCredentialsReceived...")
                        onCredentialsReceived(ssid, password, phoneUrl)
                    } else {
                        Log.e("BluetoothWifiServer", "Invalid credentials format - expected at least 2 parts, got ${parts.size}")
                        Log.e("BluetoothWifiServer", "Parts were: $parts")
                    }
                } else {
                    Log.e("BluetoothWifiServer", "Received empty or null data")
                }
                
                // Send a small ACK back to the phone so the phone's writer/read loops
                // can see an explicit response and avoid "read failed" timeouts.
                try {
                    val writer = java.io.OutputStreamWriter(clientSocket.outputStream)
                    writer.write("OK\n")
                    writer.flush()
                    writer.close()
                    Log.i("BluetoothWifiServer", "Sent ACK to client")
                } catch (e: Exception) {
                    Log.w("BluetoothWifiServer", "Failed to send ACK to client: ${e.message}")
                }

                reader.close()
                clientSocket.close()
                Log.i("BluetoothWifiServer", "Connection closed successfully")
                
            } catch (e: Exception) {
                Log.e("BluetoothWifiServer", "Server error: ${e.message}", e)
                try {
                    clientSocket?.close()
                } catch (closeException: Exception) {
                    Log.w("BluetoothWifiServer", "Error closing client socket: ${closeException.message}")
                }
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("BluetoothWifiServer", "Error closing server socket: ${e.message}")
        }
    }
} 