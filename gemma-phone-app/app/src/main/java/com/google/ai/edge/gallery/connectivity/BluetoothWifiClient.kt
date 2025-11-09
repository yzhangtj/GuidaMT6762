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

        // If the device is not bonded (paired), try to initiate bonding so the remote
        // server will accept RFCOMM connections on some stacks.
        try {
          if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "Device not bonded (state=${device.bondState}), initiating bonding...")
            val initiated = try {
              device.createBond()
            } catch (e: Exception) {
              Log.w(TAG, "createBond() failed: ${e.message}")
              false
            }
            if (initiated) {
              // Wait up to 20s for bonding to complete
              val start = System.currentTimeMillis()
              while (System.currentTimeMillis() - start < 20_000 && device.bondState != BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "Waiting for bond to complete, current state=${device.bondState}")
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
              }
              Log.i(TAG, "Bond state after wait: ${device.bondState}")
            } else {
              Log.w(TAG, "Bonding not initiated or failed")
            }
          } else {
            Log.i(TAG, "Device already bonded")
          }
        } catch (e: Exception) {
          Log.w(TAG, "Error while attempting bonding: ${e.message}")
        }

        var socketType = "secure"
        socket =
          try {
            Log.i(TAG, "Trying createRfcommSocketToServiceRecord...")
            socketType = "secure"
            device.createRfcommSocketToServiceRecord(serviceUUID)
          } catch (e: Exception) {
            Log.w(TAG, "Standard socket creation failed: ${e.message}. Trying insecure socket or reflection...")
            // Try insecure socket (some devices accept insecure RFCOMM)
            try {
              Log.i(TAG, "Trying createInsecureRfcommSocketToServiceRecord...")
              socketType = "insecure"
              device.createInsecureRfcommSocketToServiceRecord(serviceUUID)
            } catch (ie: Exception) {
              Log.w(TAG, "Insecure socket creation failed: ${ie.message}. Falling back to reflection...")
              socketType = "reflection"
              val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
              method.invoke(device, 1) as BluetoothSocket
            }
          }

        Log.i(TAG, "Connecting to socket... (type=$socketType)")
        // Retry connect more times because some Android Bluetooth stacks
        // can fail the RFCOMM handshake transiently with read failed / -1.
        var connected = false
        var connectException: Exception? = null
        val maxConnectAttempts = 6
        for (attempt in 1..maxConnectAttempts) {
          try {
            socket.connect()
            connected = true
            Log.i(TAG, "Connected on attempt $attempt")
            break
          } catch (e: Exception) {
            connectException = e
            Log.w(TAG, "Connect attempt $attempt failed: ${e.message}")
            try {
              Thread.sleep(700)
            } catch (ie: InterruptedException) {
              // ignore
            }
          }
        }
        if (!connected) throw connectException ?: Exception("Bluetooth connect failed")
        Log.i(TAG, "Connected! Preparing to send credentials...")

        val writer = OutputStreamWriter(socket.outputStream)
        val reader = socket.inputStream.bufferedReader()

        // Compute a reasonable phone-local API URL to send to the glasses as a 3rd CSV token.
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
          phoneUrl = "http://192.168.43.1:5000"
        }

        val message = "$ssid,$password,$phoneUrl"

        Log.i(TAG, "=== FINAL MESSAGE TO SEND ===")
        Log.i(TAG, "Message: '$message'")

        // Retries for send+ACK
        val maxAttempts = 3
        val ackTimeoutMs = 3000L
        var sentOk = false
        var lastError: String? = null
        for (attempt in 1..maxAttempts) {
          try {
            writer.write("$message\n")
            writer.flush()
            Log.i(TAG, "Wrote message, waiting for ACK (attempt $attempt)")

                // Wait for a short ACK line from the glasses.
                // Use a short-lived executor to perform a blocking readLine() with a timeout,
                // which is more portable across Bluetooth stacks than reader.ready().
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                  val future = executor.submit<String?> {
                    try {
                      reader.readLine()
                    } catch (e: Exception) {
                      Log.w(TAG, "Reader thread exception: ${e.message}")
                      null
                    }
                  }

                  val ack = try {
                    future.get(ackTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                  } catch (te: Exception) {
                    Log.w(TAG, "ACK read timed out or failed: ${te.message}")
                    future.cancel(true)
                    null
                  }

                  Log.i(TAG, "Received ACK: $ack")
                  if (ack != null && ack.trim().equals("OK", ignoreCase = true)) {
                    sentOk = true
                  }
                } finally {
                  try {
                    executor.shutdownNow()
                  } catch (e: Exception) {
                    // ignore
                  }
                }
            if (sentOk) break

            // If no ack, backoff and retry
            Log.w(TAG, "No ACK received on attempt $attempt")
            lastError = "No ACK received"
            Thread.sleep(300)
          } catch (e: Exception) {
            Log.w(TAG, "Error during send/ack attempt $attempt: ${e.message}")
            lastError = e.message
            try {
              Thread.sleep(300)
            } catch (ie: InterruptedException) {
              // ignore
            }
          }
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

        if (sentOk) {
          Log.i(TAG, "Credentials sent successfully (ACK received)")
          onResult(true, "Credentials sent successfully (ACK)")
        } else {
          Log.e(TAG, "Failed to receive ACK after $maxAttempts attempts")
          onResult(false, "Failed to receive ACK: ${lastError ?: "unknown"}")
        }
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


