package com.google.ai.edge.gallery.ui.provisioning

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.connectivity.GuidaWifiManager
import com.google.ai.edge.gallery.connectivity.ProvisioningViewModel
import androidx.compose.runtime.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvisioningDialog(
  onDismiss: () -> Unit,
  viewModel: ProvisioningViewModel = hiltViewModel(),
) {
  val devices by viewModel.devices.collectAsState()
  val statusMessage by viewModel.status.collectAsState()
  val wifiState by viewModel.wifiState.collectAsState()

  var selectedDevice by remember(devices) { mutableStateOf<BluetoothDevice?>(devices.firstOrNull()) }
  var ssid by remember { mutableStateOf(viewModel.getSuggestedSsid().orEmpty()) }
  var password by remember { mutableStateOf("") }
  var expanded by remember { mutableStateOf(false) }

  LaunchedEffect(devices) {
    if (selectedDevice == null && devices.isNotEmpty()) {
      selectedDevice = devices.first()
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      Button(
        onClick = {
          val device = selectedDevice
          when {
            device == null -> viewModel.setStatus("Select a paired device first")
            ssid.isBlank() || password.isBlank() -> viewModel.setStatus("SSID and password cannot be empty")
            else -> viewModel.sendCredentials(device, ssid, password)
          }
        },
        enabled = devices.isNotEmpty(),
      ) {
        Text("Send to Glasses")
      }
    },
    dismissButton = {
      TextButton(onDismiss) { Text("Close") }
    },
    title = { Text("Connect Glasses") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose a paired Bluetooth device and send Wi-Fi credentials.")

        Box {
          OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = selectedDevice?.name ?: selectedDevice?.address ?: "Select device",
            onValueChange = {},
            label = { Text("Glasses device") },
            readOnly = true,
            trailingIcon = {
              IconButton(onClick = { expanded = !expanded }) {
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
              }
            },
            placeholder = { Text("Select a paired device") },
          )
          DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            devices.forEach { device ->
              DropdownMenuItem(
                text = { Text(device.name ?: device.address) },
                onClick = {
                  selectedDevice = device
                  expanded = false
                }
              )
            }
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
          OutlinedButton(onClick = { viewModel.refreshDevices() }) {
            Text("Refresh devices")
          }
        }

        if (devices.isEmpty()) {
          Text("No paired Bluetooth devices found. Pair the glasses in Android settings first.")
        }

        OutlinedTextField(
          value = ssid,
          onValueChange = { ssid = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Wi-Fi SSID") },
        )
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Wi-Fi password") },
          visualTransformation = PasswordVisualTransformation(),
        )

        val wifiStatus =
          when (val state = wifiState) {
            is GuidaWifiManager.WifiState.Connected -> "Connected to ${state.ssid}"
            is GuidaWifiManager.WifiState.Connecting -> "Connecting..."
            is GuidaWifiManager.WifiState.Error -> state.message
            is GuidaWifiManager.WifiState.Status -> state.message
            GuidaWifiManager.WifiState.Disconnected -> "Wi-Fi disconnected"
          }
        Text("Wi-Fi status: $wifiStatus")

        statusMessage?.let { Text(it) }
      }
    },
  )
}


