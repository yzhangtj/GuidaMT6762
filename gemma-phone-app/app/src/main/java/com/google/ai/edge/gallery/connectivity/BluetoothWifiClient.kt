package com.google.ai.edge.gallery.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
        socket.connect()
        Log.i(TAG, "Connected! Preparing to send credentials...")

        val writer = OutputStreamWriter(socket.outputStream)
        val message = "$ssid,$password"

        Log.i(TAG, "=== FINAL MESSAGE TO SEND ===")
        Log.i(TAG, "Message: '$message'")

        writer.write("$message\n")
        writer.flush()
        Thread.sleep(1000)

        writer.close()
        socket.close()
        Log.i(TAG, "Credentials sent successfully!")
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


