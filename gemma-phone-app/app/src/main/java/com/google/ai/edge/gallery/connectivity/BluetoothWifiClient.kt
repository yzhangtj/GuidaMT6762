package com.google.ai.edge.gallery.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.OutputStreamWriter
import java.util.UUID
import kotlin.concurrent.thread

class BluetoothWifiClient {
  private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
  private val serviceUUID: UUID =
    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID

  fun sendCredentials(
    device: BluetoothDevice,
    ssid: String,
    password: String,
    context: Context,
    onResult: (Boolean, String) -> Unit,
  ) {
    Log.i(TAG, "=== SENDING CREDENTIALS DEBUG ===")
    Log.i(TAG, "SSID parameter: '$ssid' (length: ${ssid.length})")
    Log.i(TAG, "Password parameter: '$password' (length: ${password.length})")
    Log.i(TAG, "Target device: ${device.name} (${device.address})")

    if (adapter == null) {
      Log.e(TAG, "Bluetooth not supported on this device")
      onResult(false, "Bluetooth not supported on this device")
      return
    }

    if (ssid.isEmpty()) {
      onResult(false, "ERROR: SSID is empty!")
      return
    }

    if (password.isEmpty()) {
      onResult(false, "ERROR: Password is empty!")
      return
    }

    thread {
      var socket: BluetoothSocket? = null
      try {
        Log.i(TAG, "Attempting to connect to ${device.name} (${device.address})")
        adapter.cancelDiscovery()
        Thread.sleep(2000)

        socket =
          try {
              Log.i(TAG, "Trying createRfcommSocketToServiceRecord...")
              device.createRfcommSocketToServiceRecord(serviceUUID)
            } catch (e: Exception) {
              Log.w(TAG, "Standard socket creation failed, trying reflection method...")
              val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
              method.invoke(device, 1) as BluetoothSocket
            }

        Log.i(TAG, "Connecting to socket...")
        // Retry connect a few times because some Android Bluetooth stacks
        // can fail the RFCOMM handshake transiently with read failed / -1.
        var connected = false
        var connectException: Exception? = null
        for (attempt in 1..3) {
          try {
            socket.connect()
            connected = true
            Log.i(TAG, "Connected on attempt $attempt")
            break
          } catch (e: Exception) {
            connectException = e
            Log.w(TAG, "Connect attempt $attempt failed: ${e.message}")
            try {
              Thread.sleep(300)
            } catch (ie: InterruptedException) {
              // ignore
            }
          }
        }
        if (!connected) throw connectException ?: Exception("Bluetooth connect failed")
        Log.i(TAG, "Connected! Preparing to send credentials...")

        val writer = OutputStreamWriter(socket.outputStream)

        // Compute a reasonable phone-local API URL to send to the glasses as a 3rd CSV token.
        // Try to use the device's current Wi-Fi IP; fall back to the common tethering gateway.
        var phoneUrl: String? = null
        try {
          val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
          val ipInt = wifiManager.connectionInfo?.ipAddress ?: 0
          if (ipInt != 0) {
            val ip = (ipInt and 0xff).toString() + "." + ((ipInt shr 8) and 0xff) + "." + ((ipInt shr 16) and 0xff) + "." + ((ipInt shr 24) and 0xff)
            phoneUrl = "http://$ip:5000"
          }
        } catch (e: Exception) {
          Log.w(TAG, "Could not obtain Wi-Fi IP: ${e.message}")
        }

        if (phoneUrl == null) {
          // Common Android hotspot/tethering gateway if Wi‑Fi IP not available.
          phoneUrl = "http://192.168.43.1:5000"
        }

        val message = "$ssid,$password,$phoneUrl"

  Log.i(TAG, "=== FINAL MESSAGE TO SEND ===")
  Log.i(TAG, "Message: '$message'")

        writer.write("$message\n")
        writer.flush()

        // Match legacy behaviour: write, flush, sleep briefly to allow transfer, then close.
        // Reading from the socket is unreliable across device implementations and has caused
        // "read failed" errors on some phones. Reverting to the simpler pattern used in the
        // previous phone app avoids those spurious read failures.
        try {
          Thread.sleep(1000)
        } catch (e: InterruptedException) {
          // ignore
        }

        try {
          writer.close()
        } catch (e: Exception) {
          Log.w(TAG, "Error closing writer: ${e.message}")
        }

        try {
          socket.close()
        } catch (e: Exception) {
          Log.w(TAG, "Error closing socket: ${e.message}")
        }

        Log.i(TAG, "Credentials sent successfully (legacy mode, no ACK read)")
        onResult(true, "Credentials sent successfully!")
      } catch (e: Exception) {
        Log.e(TAG, "Error sending credentials: ${e.message}", e)
        try {
          socket?.close()
        } catch (closeException: Exception) {
          Log.w(TAG, "Error closing socket: ${closeException.message}")
        }
        onResult(false, "Error: ${e.message}")
      }
    }
  }

  companion object {
    private const val TAG = "BluetoothWifiClient"
  }
}


