package com.guidaco.guidaglassesapp

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.guidaco.guidaglassesapp.ui.theme.GuidaGlassesAppTheme
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.guidaco.guidaglassesapp.BluetoothWifiServer
import android.os.Handler
import android.os.Looper
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private var bluetoothWifiServer: BluetoothWifiServer? = null
    private lateinit var audioManager: AudioManager
    private lateinit var settingsDataStore: SettingsDataStore
    private var f1LongPressHandled = false
    private var f1DownTime: Long = 0L
    private val f1LongPressTimeout = 500L // ms
    private val handler = Handler(Looper.getMainLooper())
    private val BLUETOOTH_NAME = "GuidaGlasses-0001"
    private val DISCOVERABLE_DURATION = 120 // seconds
    private val REQUEST_BLUETOOTH_PERMISSIONS = 1002
    private var pendingF1LongPress = false
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleAdvertiseCallback: AdvertiseCallback? = null
    private val f1LongPressRunnable = Runnable {
        f1LongPressHandled = true
        ensureBluetoothPermissionsAndRun {
            startBleAdvertising()
            makeDeviceDiscoverable()
            android.util.Log.i("guida", "F1 manual long press - starting Bluetooth WiFi provisioning")
            try {
                // Set Bluetooth name on UI thread
                runOnUiThread {
                    try {
                        val adapter = BluetoothAdapter.getDefaultAdapter()
                        if (adapter == null) {
                            android.util.Log.e("guida", "Bluetooth not supported on this device.")
                            audioManager.speak("Bluetooth not supported on this device.")
                            Toast.makeText(this, "Bluetooth not supported on this device.", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        adapter.name = BLUETOOTH_NAME
                        if (!adapter.isEnabled) {
                            android.util.Log.e("guida", "Bluetooth is not enabled.")
                            audioManager.speak("Please enable Bluetooth and try again.")
                            Toast.makeText(this, "Please enable Bluetooth and try again.", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        audioManager.speak("Bluetooth pairing mode activated. Device is now discoverable for 2 minutes.")
                        Toast.makeText(this, "If your phone cannot find the glasses, please enable Bluetooth discoverability in system settings.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        android.util.Log.e("guida", "Error in Bluetooth setup: ${e.message}", e)
                        audioManager.speak("Bluetooth error: ${e.message}")
                        Toast.makeText(this, "Bluetooth error: ${e.message}", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                }
                Toast.makeText(this, "Waiting for phone to send WiFi credentials via Bluetooth...", Toast.LENGTH_LONG).show()
                settingsDataStore = SettingsDataStore(this@MainActivity)

                bluetoothWifiServer = BluetoothWifiServer { ssid, password, phoneUrl ->
                    runOnUiThread {
                        try {
                            // DEBUG: Log what we received in MainActivity
                            android.util.Log.i("guida", "=== MAIN ACTIVITY RECEIVED CREDENTIALS ===")
                            android.util.Log.i("guida", "Received SSID: '$ssid' (length: ${ssid.length})")
                            android.util.Log.i("guida", "Received Password: '$password' (length: ${password.length})")
                            
                            if (ssid.isEmpty()) {
                                android.util.Log.e("guida", "ERROR: Received empty SSID!")
                                audioManager.speak("Error: Received empty network name.")
                                Toast.makeText(this, "Error: Received empty network name!", Toast.LENGTH_LONG).show()
                                return@runOnUiThread
                            }
                            
                            if (password.isEmpty()) {
                                android.util.Log.e("guida", "ERROR: Received empty password!")
                                audioManager.speak("Error: Received empty password.")
                                Toast.makeText(this, "Error: Received empty password!", Toast.LENGTH_LONG).show()
                                return@runOnUiThread
                            }
                            
                            audioManager.speak("WiFi credentials received, connecting to network.")
                            Toast.makeText(this, "Received WiFi credentials! Connecting...", Toast.LENGTH_SHORT).show()
                            android.util.Log.i("guida", "Calling viewModel.connectToWifi with SSID='$ssid', password='$password'")
                            viewModel.connectToWifi(ssid, password)

                            // If phone sent its API URL, persist it and enable phone routing
                            if (!phoneUrl.isNullOrBlank()) {
                                android.util.Log.i("guida", "Received Phone API URL over Bluetooth: $phoneUrl")
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        settingsDataStore.setPhoneApiUrl(phoneUrl)
                                        settingsDataStore.setUsePhoneGemma(true)
                                        android.util.Log.i("guida", "Phone API URL persisted and phone routing enabled")
                                    } catch (e: Exception) {
                                        android.util.Log.e("guida", "Failed to persist phone API URL: ${e.message}", e)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("guida", "Error handling received credentials: ${e.message}", e)
                            audioManager.speak("Error handling credentials: ${e.message}")
                        }
                    }
                }
                bluetoothWifiServer?.start()
            } catch (e: Exception) {
                android.util.Log.e("guida", "Error in F1 long press runnable: ${e.message}", e)
                runOnUiThread {
                    audioManager.speak("Error: ${e.message}")
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("guida", "MainActivity onCreate called")
        viewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[MainViewModel::class.java]
        audioManager = AudioManager(this)
        // Removed TTS engine check and prompt, as it is now set programmatically
        // Create and set SpeechRecognitionManager
        val speechRecognitionManager = SpeechRecognitionManager(this)
        viewModel.setSpeechRecognitionManager(speechRecognitionManager)
        
        // Removed offline TTS test; online TTS is triggered via buttons (F2/Volume Up)
        setContent {
            GuidaGlassesAppTheme {
                val permissionsState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
                LaunchedEffect(key1 = true) {
                    if (!permissionsState.allPermissionsGranted) {
                        permissionsState.launchMultiplePermissionRequest()
                    }
                }
                if (permissionsState.allPermissionsGranted) {
                    LaunchedEffect(key1 = Unit) {
                        viewModel.initialize(this@MainActivity)
                    }
                    val uiState by viewModel.uiState.collectAsState()
                    val wifiState by viewModel.wifiState.collectAsState()
                    val context = LocalContext.current
                    // Show Toast for one-shot messages
                    if (uiState is MainViewModel.UiState.ToastMessage) {
                        val msg = (uiState as MainViewModel.UiState.ToastMessage).message
                        LaunchedEffect(msg) {
                            android.util.Log.i("GuidaToast", "Toast: $msg")
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            viewModel.initialize(this@MainActivity) // Reset to AwaitingInput
                        }
                    }
                    MainScreen(
                        uiState = uiState,
                        onControlButtonClick = { viewModel.onF1ButtonPressed() },
                        onSettingsClick = {
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        },
                        onRetryClick = { viewModel.initialize(this@MainActivity) },
                        wifiState = wifiState,
                        onTestWifiClick = { testWifiConnection() },
                        onRadarClick = {
                            startActivity(Intent(this@MainActivity, RadarViewActivity::class.java))
                        }
                    )
                } else {
                    PermissionRequestScreen {
                        permissionsState.launchMultiplePermissionRequest()
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        android.util.Log.i("guida", "onKeyDown called with keyCode: $keyCode")
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_F2 -> {
                if (event?.repeatCount == 0) {
                    f1LongPressHandled = false
                    f1DownTime = System.currentTimeMillis()
                    handler.postDelayed(f1LongPressRunnable, f1LongPressTimeout)
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_F3 -> {
                android.util.Log.i("guida", "F3 button pressed")
                viewModel.onF2ButtonPressed()
                return true
            }
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                android.util.Log.i("guida", "Volume up button pressed")
                viewModel.onVolumeUpPressed()
                return true
            }
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                android.util.Log.i("guida", "Volume down button pressed")
                viewModel.onVolumeDownPressed()
                return true
            }
            android.view.KeyEvent.KEYCODE_POWER -> {
                android.util.Log.i("guida", "Power button pressed")
                // Test WiFi connection when power button is pressed
                testWifiConnection()
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        android.util.Log.i("guida", "onKeyUp called with keyCode: $keyCode")
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_F2,
            android.view.KeyEvent.KEYCODE_F3,
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
            android.view.KeyEvent.KEYCODE_POWER -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_F2) {
                    handler.removeCallbacks(f1LongPressRunnable)
                    if (!f1LongPressHandled) {
                        // Short press: trigger capture + speech recognition
                        Log.i("guida", "F1 short press detected! Calling viewModel.onF1ButtonPressed()")
                        viewModel.onF1ButtonPressed()
                    }
                }
                return true
            }
            else -> return super.onKeyUp(keyCode, event)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.i("guida", "dispatchKeyEvent called with keyCode: ${event.keyCode}, action: ${event.action}")
        return super.dispatchKeyEvent(event)
    }
    
    // Test WiFi connection function
    private fun testWifiConnection() {
        // Replace these with your actual WiFi credentials
        val ssid = "19-3"  // Your actual WiFi name
        val password = "13813355882"  // Your actual WiFi password
        
        android.util.Log.i("guida", "Testing WiFi connection to: $ssid")
        viewModel.connectToWifi(ssid, password)
    }

    private fun ensureBluetoothPermissionsAndRun(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = mutableListOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            val notGranted = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (notGranted.isNotEmpty()) {
                pendingF1LongPress = true
                requestPermissions(notGranted.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
                return
            }
        } else {
            // For legacy support, check BLUETOOTH and BLUETOOTH_ADMIN if needed
        }
        action()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (pendingF1LongPress) {
                    pendingF1LongPress = false
                    handler.post(f1LongPressRunnable)
                }
            } else {
                audioManager.speak("Bluetooth permissions denied. Cannot start pairing mode.")
                Toast.makeText(this, "Bluetooth permissions denied. Cannot start pairing mode.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startBleAdvertising() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return
        bleAdvertiser = adapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        bleAdvertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                android.util.Log.i("guida", "BLE advertising started successfully")
            }
            override fun onStartFailure(errorCode: Int) {
                android.util.Log.e("guida", "BLE advertising failed: $errorCode")
            }
        }
        bleAdvertiser?.startAdvertising(settings, data, bleAdvertiseCallback)
        android.util.Log.i("guida", "BLE advertising started")
    }

    private fun stopBleAdvertising() {
        bleAdvertiser?.stopAdvertising(bleAdvertiseCallback)
        android.util.Log.i("guida", "BLE advertising stopped")
    }

    private fun makeDeviceDiscoverable() {
        try {
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(discoverableIntent)
            android.util.Log.i("guida", "Requesting device to be discoverable for $DISCOVERABLE_DURATION seconds")
        } catch (e: Exception) {
            android.util.Log.e("guida", "Failed to make device discoverable: ${e.message}", e)
            // Try alternative approach - set scan mode directly (requires system permissions)
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter != null) {
                    // This might not work without system permissions, but worth trying
                    android.util.Log.i("guida", "Attempting to set scan mode to discoverable")
                }
            } catch (e2: Exception) {
                android.util.Log.e("guida", "Alternative discoverable method also failed: ${e2.message}", e2)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleAdvertising()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainViewModel.UiState,
    onControlButtonClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRetryClick: () -> Unit,
    wifiState: GuidaWifiManager.WifiState?,
    onTestWifiClick: () -> Unit,
    onRadarClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guida Assistant") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(painter = painterResource(id = R.drawable.ic_settings), contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween // Push content to top and bottom
        ) {
            // --- Top Section ---
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // WiFi status and test button at the top
                wifiState?.let {
                    when (it) {
                        is GuidaWifiManager.WifiState.Status -> StatusCard(status = "WiFi Status", message = it.message)
                        is GuidaWifiManager.WifiState.Error -> StatusCard(status = "WiFi Error", message = it.message, isError = true)
                        is GuidaWifiManager.WifiState.Connected -> StatusCard(status = "WiFi Connected", message = "Connected to ${it.ssid}")
                        is GuidaWifiManager.WifiState.Disconnected -> StatusCard(status = "WiFi", message = "Disconnected")
                        is GuidaWifiManager.WifiState.Connecting -> StatusCard(status = "WiFi", message = "Connecting...")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onTestWifiClick) {
                    Text("Test WiFi Connection")
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRadarClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_radar),
                        contentDescription = "Radar",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Radar View")
                }
            }

            // --- Bottom Section (Main Controls) ---
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when (uiState) {
                    is MainViewModel.UiState.Initializing -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            StatusCard(status = "Status", message = "Initializing...")
                            Spacer(modifier = Modifier.height(16.dp))
                            ControlButton(
                                iconResId = R.drawable.ic_retry,
                                text = "Initializing",
                                onClick = { /* Disabled */ },
                                backgroundColor = Color.Gray,
                                enabled = false
                            )
                        }
                    }
                    is MainViewModel.UiState.AwaitingInput -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            StatusCard(status = "Status", message = "Ready")
                            Spacer(modifier = Modifier.height(16.dp))
                            ControlButton(
                                iconResId = R.drawable.ic_record,
                                text = "Capture",
                                onClick = onControlButtonClick,
                                backgroundColor = MaterialTheme.colorScheme.primary,
                                enabled = true
                            )
                        }
                    }
                    is MainViewModel.UiState.Processing -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            StatusCard(status = "Status", message = uiState.message)
                            Spacer(modifier = Modifier.height(16.dp))
                            ControlButton(
                                iconResId = R.drawable.ic_processing,
                                text = "Processing",
                                onClick = { /* Disabled */ },
                                backgroundColor = Color.Gray,
                                enabled = false
                            )
                        }
                    }
                    is MainViewModel.UiState.Error -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            StatusCard(status = "Error occurred", message = uiState.message, isError = true)
                            Spacer(modifier = Modifier.height(16.dp))
                            ControlButton(
                                iconResId = R.drawable.ic_retry,
                                text = "Retry",
                                onClick = onRetryClick,
                                backgroundColor = Color.Red,
                                enabled = true
                            )
                        }
                    }
                    is MainViewModel.UiState.ShowingResponse -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ResponseCard(response = uiState.response, apiProvider = uiState.apiProvider)
                            Spacer(modifier = Modifier.height(16.dp))
                            ControlButton(
                                iconResId = R.drawable.ic_record,
                                text = "New Query",
                                onClick = onControlButtonClick,
                                backgroundColor = MaterialTheme.colorScheme.primary,
                                enabled = true
                            )
                        }
                    }
                    is MainViewModel.UiState.ToastMessage -> { /* No UI, handled by Toast above */ }
                }
            }
        }
    }
}

@Composable
fun StatusCard(status: String, message: String, isError: Boolean = false) {
        Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
            colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
            modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
            ) {
            Text(text = status, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = message, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ResponseCard(response: String, apiProvider: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$apiProvider Response",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = response,
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun ControlButton(
    iconResId: Int,
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    enabled: Boolean
) {
    val rotation = remember { Animatable(0f) }
                
    if (iconResId == R.drawable.ic_processing) {
        LaunchedEffect(Unit) {
            rotation.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
    } else {
        LaunchedEffect(Unit) {
            rotation.snapTo(0f)
        }
    }

        Button(
        onClick = onClick,
        modifier = Modifier.size(150.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor),
        enabled = enabled
        ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = text,
                modifier = Modifier
                    .size(48.dp)
                    .rotate(rotation.value)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = text)
        }
    }
}

@Composable
fun PermissionRequestScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Permissions Required", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "This app needs Camera and Audio permissions to function correctly. Please grant them.",
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermissions) {
            Text("Grant Permissions")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    GuidaGlassesAppTheme {
        MainScreen(
            uiState = MainViewModel.UiState.AwaitingInput,
            onControlButtonClick = {},
            onSettingsClick = {},
            onRetryClick = {},
            wifiState = null,
            onTestWifiClick = {},
            onRadarClick = {}
        )
    }
}