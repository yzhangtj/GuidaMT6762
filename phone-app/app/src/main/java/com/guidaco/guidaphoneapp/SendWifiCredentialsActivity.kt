package com.guidaco.guidaphoneapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import com.guidaco.guidaphoneapp.BluetoothWifiClient

class SendWifiCredentialsActivity : ComponentActivity() {
    private val TAG = "SendWifiCredentials"
    private lateinit var bluetoothManager: BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? by lazy { 
        bluetoothManager.adapter 
    }
    
    private val bluetoothWifiClient = BluetoothWifiClient()
    
    private val REQUEST_ENABLE_BT = 1
    private val DEBUG_SHOW_ALL_DEVICES = true // Set to false to restore original filtering
    private val PERMISSIONS: Array<String> by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Bluetooth permissions required for scanning", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, "Bluetooth must be enabled to scan for devices", Toast.LENGTH_LONG).show()
        }
    }

    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanCallback: ScanCallback? = null
    private var isBleScanning = false
    private val bleDiscoveredDevices = mutableSetOf<BluetoothDevice>()

    private fun startBleScan(onDeviceFound: () -> Unit, onError: (String) -> Unit) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) return
        bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) return
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(null).build() // Show all devices
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bleScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                bleDiscoveredDevices.add(device)
                onDeviceFound()
            }
            override fun onBatchScanResults(results: List<ScanResult>) {
                for (result in results) {
                    val device = result.device
                    bleDiscoveredDevices.add(device)
                }
                onDeviceFound()
            }
            override fun onScanFailed(errorCode: Int) {
                onError("BLE scan failed: $errorCode")
            }
        }
        bleScanner?.startScan(filters, settings, bleScanCallback)
        isBleScanning = true
        android.util.Log.i(TAG, "BLE scan started")
        // Stop scan after 12 seconds
        CoroutineScope(Dispatchers.Main).launch {
            delay(12000)
            stopBleScan()
        }
    }

    private fun stopBleScan() {
        if (isBleScanning) {
            bleScanner?.stopScan(bleScanCallback)
            isBleScanning = false
            android.util.Log.i(TAG, "BLE scan stopped")
        }
    }

    // Device filtering functions
    private fun resolveDeviceName(device: BluetoothDevice): String {
        val name = device.name
        return if (!name.isNullOrBlank()) name else device.address
    }

    private fun isGuidaDevice(device: BluetoothDevice): Boolean {
        val name = device.name
        val address = device.address ?: return false
        Log.d(TAG, "Checking device: name='${name ?: "NULL"}' address='$address'")
        if (name.isNullOrBlank()) {
            // For MTK devices, don't reject null names - they might be GuidaGlasses
            return false
        }
        return name.startsWith("GuidaGlasses-", ignoreCase = true)
    }
    
    private fun mightBeGuidaDevice(device: BluetoothDevice): Boolean {
        val name = device.name
        if (name.isNullOrBlank()) {
            // Could be GuidaGlasses with unresolved name
            return true
        }
        return name.startsWith("GuidaGlasses-", ignoreCase = true) ||
               name.contains("guida", ignoreCase = true) ||
               name.contains("glasses", ignoreCase = true)
    }

    private fun pairDevice(device: BluetoothDevice, onResult: (Boolean, String) -> Unit) {
        try {
            Log.i(TAG, "Attempting to pair with device: ${device.name} (${device.address})")
            Log.i(TAG, "Current bond state: ${device.bondState}")
            
            // Check if already paired
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "Device is already paired")
                onResult(true, "Device is already paired")
                return
            }
            
            // Check if currently pairing
            if (device.bondState == BluetoothDevice.BOND_BONDING) {
                Log.i(TAG, "Device is currently pairing, waiting for completion...")
                // Just wait for the existing pairing to complete
            }
            
            var timeoutHandler: Handler? = null
            
            // Register pairing receiver
            val pairingReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val bondedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            if (bondedDevice?.address == device.address) {
                                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                                Log.i(TAG, "Bond state changed for ${device.address}: $bondState (${when(bondState) {
                                    BluetoothDevice.BOND_NONE -> "BOND_NONE"
                                    BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
                                    BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
                                    else -> "UNKNOWN"
                                }})")
                                
                                when (bondState) {
                                    BluetoothDevice.BOND_BONDED -> {
                                        Log.i(TAG, "Device paired successfully")
                                        timeoutHandler?.removeCallbacksAndMessages(null)
                                        onResult(true, "Device paired successfully")
                                        try { unregisterReceiver(this) } catch (_: Exception) {}
                                    }
                                    BluetoothDevice.BOND_NONE -> {
                                        Log.e(TAG, "Pairing failed or cancelled")
                                        timeoutHandler?.removeCallbacksAndMessages(null)
                                        onResult(false, "Pairing failed or cancelled")
                                        try { unregisterReceiver(this) } catch (_: Exception) {}
                                    }
                                    BluetoothDevice.BOND_BONDING -> {
                                        Log.i(TAG, "Pairing in progress...")
                                    }
                                }
                            }
                        }
                        BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                            Log.i(TAG, "Pairing request received")
                            val pairingDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            if (pairingDevice?.address == device.address) {
                                Log.i(TAG, "Pairing request is for our target device")
                                // The system will handle the pairing dialog
                            }
                        }
                    }
                }
            }
            
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            }
            registerReceiver(pairingReceiver, filter)
            
            // Start pairing if not already pairing
            if (device.bondState != BluetoothDevice.BOND_BONDING) {
                Log.i(TAG, "Initiating pairing...")
                val paired = device.createBond()
                if (!paired) {
                    Log.e(TAG, "Failed to initiate pairing")
                    onResult(false, "Failed to initiate pairing")
                    try { unregisterReceiver(pairingReceiver) } catch (_: Exception) {}
                    return
                } else {
                    Log.i(TAG, "Pairing initiated successfully")
                }
            }
            
            // Set timeout for pairing
            timeoutHandler = Handler(Looper.getMainLooper())
            timeoutHandler.postDelayed({
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.w(TAG, "Pairing timeout - current state: ${device.bondState}")
                    onResult(false, "Pairing timeout - please accept the pairing request and try again")
                    try { unregisterReceiver(pairingReceiver) } catch (_: Exception) {}
                }
            }, 60000) // 60 second timeout (increased)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during pairing: ${e.message}", e)
            onResult(false, "Pairing error: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        
        setContent {
            var ssid by remember { mutableStateOf("3609") }
            var password by remember { mutableStateOf("66668888") }
            var scanning by remember { mutableStateOf(false) }
            var pairedDevices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
            var discoveredDevices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
            var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
            var errorMessage by remember { mutableStateOf<String?>(null) }

            var isPairing by remember { mutableStateOf(false) }
            var isSendingCredentials by remember { mutableStateOf(false) }

            val context = LocalContext.current
            
            // Debug initial state
            LaunchedEffect(key1 = Unit) {
                Log.d(TAG, "Initial state - isPairing: $isPairing, isSendingCredentials: $isSendingCredentials")
            }

            fun checkPermissions(): Boolean {
                return PERMISSIONS.all { permission ->
                    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
                }
            }

            fun requestPermissions() {
                if (!checkPermissions()) {
                    requestPermissionLauncher.launch(PERMISSIONS)
                }
            }

            // Show dialog/TTS if permissions denied
            LaunchedEffect(key1 = Unit) {
                if (!checkPermissions()) {
                    Toast.makeText(context, "Nearby devices permission is required for Bluetooth scanning. Please grant it in system settings if denied.", Toast.LENGTH_LONG).show()
                }
            }

            fun enableBluetooth() {
                if (bluetoothAdapter?.isEnabled == false) {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBluetoothLauncher.launch(enableBtIntent)
                }
            }

            fun refreshPairedDevices() {
                if (checkPermissions()) {
                    val bonded = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
                    pairedDevices = bonded.sortedBy { it.name ?: it.address }
                }
            }



            fun startBluetoothScan() {
                if (!checkPermissions()) {
                    requestPermissions()
                    Toast.makeText(context, "Nearby devices permission is required for Bluetooth scanning.", Toast.LENGTH_LONG).show()
                    return
                }
                if (bluetoothAdapter?.isEnabled == false) {
                    enableBluetooth()
                    return
                }
                scanning = true
                errorMessage = null
                discoveredDevices = emptyList()
                bleDiscoveredDevices.clear()
                refreshPairedDevices()
                Toast.makeText(context, "Scanning for GuidaGlasses...", Toast.LENGTH_SHORT).show()
                
                // Combined device map for both BLE and classic Bluetooth
                val found = mutableMapOf<String, BluetoothDevice>()
                
                // Start BLE scan
                this@SendWifiCredentialsActivity.startBleScan(
                    onDeviceFound = {
                        // Update discovered devices from BLE scan
                        bleDiscoveredDevices.forEach { device ->
                            found[device.address] = device
                        }
                        discoveredDevices = found.values.sortedBy { d -> d.name ?: d.address }
                    },
                    onError = { msg -> 
                        errorMessage = "BLE scan error: $msg"
                        Log.e(TAG, "BLE scan error: $msg")
                    }
                )
                
                // Classic Bluetooth discovery receivers
                val nameReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == BluetoothDevice.ACTION_NAME_CHANGED) {
                            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            device?.let {
                                found[it.address] = it
                                discoveredDevices = found.values.sortedBy { d -> d.name ?: d.address }
                                Log.d(TAG, "Device name resolved: ${it.name} (${it.address})")
                            }
                        }
                    }
                }
                
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        when (intent?.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                device?.let {
                                    found[it.address] = it
                                    discoveredDevices = found.values.sortedBy { d -> d.name ?: d.address }
                                    Log.d(TAG, "Device found: ${it.name ?: "Unknown"} (${it.address})")
                                    
                                    // If device name is null, try to fetch it
                                    if (it.name.isNullOrBlank()) {
                                        try {
                                        it.fetchUuidsWithSdp()
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to fetch UUIDs for ${it.address}: ${e.message}")
                                        }
                                    }
                                }
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                scanning = false
                                discoveredDevices = found.values.sortedBy { d -> d.name ?: d.address }
                                
                                // Check if we found any GuidaGlasses devices
                                val guidaDevices = discoveredDevices.filter { isGuidaDevice(it) }
                                if (guidaDevices.isNotEmpty()) {
                                    Log.i(TAG, "Found ${guidaDevices.size} GuidaGlasses device(s)")
                                    Toast.makeText(context, "Found ${guidaDevices.size} GuidaGlasses device(s)!", Toast.LENGTH_SHORT).show()
                                } else if (discoveredDevices.isEmpty()) {
                                    errorMessage = "No devices found. Make sure your glasses are powered on and discoverable."
                                    Log.w(TAG, "No devices found during scan")
                                } else {
                                    Log.i(TAG, "Found ${discoveredDevices.size} devices, but no GuidaGlasses")
                                    Toast.makeText(context, "Found ${discoveredDevices.size} devices. If your glasses are not listed, try long-pressing F1 on the glasses.", Toast.LENGTH_LONG).show()
                                }
                                
                                try { unregisterReceiver(this) } catch (_: Exception) {}
                                try { unregisterReceiver(nameReceiver) } catch (_: Exception) {}
                            }
                        }
                    }
                }
                
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                val nameFilter = IntentFilter(BluetoothDevice.ACTION_NAME_CHANGED)
                
                try {
                registerReceiver(receiver, filter)
                registerReceiver(nameReceiver, nameFilter)
                bluetoothAdapter?.startDiscovery()
                    Log.i(TAG, "Started classic Bluetooth discovery")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start Bluetooth discovery: ${e.message}")
                    errorMessage = "Failed to start scan: ${e.message}"
                    scanning = false
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = "Provision Glasses WiFi",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("WiFi SSID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("WiFi Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { startBluetoothScan() },
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            scanning -> "Scanning..."
                            else -> "Scan for Glasses"
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Show scan status
                if (scanning) {
                    Text(
                        text = "Scanning for GuidaGlasses...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Show pairing/sending status
                if (isPairing) {
                    Text(
                        text = "Pairing with ${selectedDevice?.name ?: "device"}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (isSendingCredentials) {
                    Text(
                        text = "Sending WiFi credentials...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Error message
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                
                // Device list
                LazyColumn(
                    modifier = Modifier.height(200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (pairedDevices.isNotEmpty()) {
                        item {
                            Text("Paired Devices:", style = MaterialTheme.typography.titleMedium)
                        }
                        items(pairedDevices) { device ->
                            val isGuida = this@SendWifiCredentialsActivity.isGuidaDevice(device)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isGuida) Color(0xFFE3F2FD) else Color.Transparent),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = (device == selectedDevice),
                                        onClick = { selectedDevice = device }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                                text = this@SendWifiCredentialsActivity.resolveDeviceName(device) + " (Paired)" + if (!isGuida) " [Not GuidaGlasses]" else "",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (!isGuida) {
                                            Text(
                                                text = device.address,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (discoveredDevices.isNotEmpty()) {
                        item {
                            Text("Discovered Devices:", style = MaterialTheme.typography.titleMedium)
                        }
                        items(discoveredDevices) { device ->
                            val isGuida = this@SendWifiCredentialsActivity.isGuidaDevice(device)
                            val mightBeGuida = this@SendWifiCredentialsActivity.mightBeGuidaDevice(device)
                            val isPaired = pairedDevices.any { it.address == device.address }
                            if (DEBUG_SHOW_ALL_DEVICES || isGuida || mightBeGuida) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            when {
                                                isGuida -> Color(0xFFE3F2FD) // Blue for confirmed GuidaGlasses
                                                mightBeGuida -> Color(0xFFFFF3E0) // Orange for potential GuidaGlasses
                                                else -> Color.Transparent
                                            }
                                        ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = (device == selectedDevice),
                                            onClick = { selectedDevice = device }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = this@SendWifiCredentialsActivity.resolveDeviceName(device) + 
                                                      if (isPaired) " (Paired)" else " (Discovered)" +
                                                      when {
                                                          isGuida -> " [GuidaGlasses]"
                                                          mightBeGuida && device.name.isNullOrBlank() -> " [Unknown - might be GuidaGlasses]"
                                                          mightBeGuida -> " [Potential GuidaGlasses]"
                                                          else -> " [Other Device]"
                                                      },
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            // Always show address for debugging
                                            Text(
                                                text = device.address,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            // Show additional info for devices with null names
                                            if (device.name.isNullOrBlank()) {
                                                Text(
                                                    text = "Name not resolved yet - try waiting or rescanning",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Make sure button is always visible
                Button(
                    onClick = {
                        selectedDevice?.let { device ->
                            Log.i(TAG, "Button clicked - starting pairing process")
                            isPairing = true
                            isSendingCredentials = false
                            errorMessage = null
                            
                            // First, pair the device
                            pairDevice(device) { pairingSuccess, pairingMessage ->
                                runOnUiThread {
                                    Log.i(TAG, "Pairing result: success=$pairingSuccess, message=$pairingMessage")
                                    isPairing = false
                                    if (pairingSuccess) {
                                        // Pairing successful, refresh paired devices list
                                        refreshPairedDevices()
                                        // Now send credentials
                                        isSendingCredentials = true
                                        Toast.makeText(context, "Pairing successful! Sending credentials...", Toast.LENGTH_SHORT).show()
                                        
                                                                // DEBUG: Log what we're about to send
                        Log.i(TAG, "=== PHONE APP SENDING DEBUG ===")
                        Log.i(TAG, "About to send SSID: '$ssid' (length: ${ssid.length})")
                        Log.i(TAG, "About to send Password: '$password' (length: ${password.length})")
                        Log.i(TAG, "To device: ${device.name} (${device.address})")
                        
                        bluetoothWifiClient.sendCredentials(device, ssid, password) { success, message ->
                            runOnUiThread {
                                Log.i(TAG, "Credentials sending result: success=$success, message=$message")
                                isSendingCredentials = false
                                if (success) {
                                    Toast.makeText(context, "Credentials sent successfully!", Toast.LENGTH_LONG).show()
                                } else {
                                    errorMessage = "Failed to send credentials: $message"
                                    Toast.makeText(context, "Failed to send credentials: $message", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                                    } else {
                                        // Pairing failed
                                        errorMessage = "Pairing failed: $pairingMessage"
                                        Toast.makeText(context, "Pairing failed: $pairingMessage", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } ?: run {
                            Log.w(TAG, "No device selected")
                            Toast.makeText(context, "Please select a device first", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = ssid.isNotBlank() && password.isNotBlank() && selectedDevice != null && !isPairing && !isSendingCredentials,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val buttonText = if (isPairing) {
                        "Pairing..."
                    } else if (isSendingCredentials) {
                        "Sending Credentials..."
                    } else {
                        "Pair & Send Credentials"
                    }
                    Log.d(TAG, "Button render - text: '$buttonText', isPairing: $isPairing, isSendingCredentials: $isSendingCredentials, selectedDevice: ${selectedDevice?.name}")
                    Text(
                        text = buttonText,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any ongoing discovery
        bluetoothAdapter?.cancelDiscovery()
        this@SendWifiCredentialsActivity.stopBleScan()
    }
} 