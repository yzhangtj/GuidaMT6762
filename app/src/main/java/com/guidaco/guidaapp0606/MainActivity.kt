package com.guidaco.guidaapp0606

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
import com.guidaco.guidaapp0606.ui.theme.GuidaApp0606Theme
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("guida", "MainActivity onCreate called")
        viewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[MainViewModel::class.java]
        // Create and set SpeechRecognitionManager
        val speechRecognitionManager = SpeechRecognitionManager(this)
        viewModel.setSpeechRecognitionManager(speechRecognitionManager)
        setContent {
            GuidaApp0606Theme {
                val permissionsState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
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
                        onControlButtonClick = { viewModel.onCaptureButtonPressed() },
                        onSettingsClick = {
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        },
                        onRetryClick = { viewModel.initialize(this@MainActivity) },
                        wifiState = wifiState,
                        onTestWifiClick = { testWifiConnection() }
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
            android.view.KeyEvent.KEYCODE_F1 -> {
                android.util.Log.i("guida", "F1 button pressed")
                viewModel.onF1ButtonPressed()
                return true
            }
            android.view.KeyEvent.KEYCODE_F2 -> {
                android.util.Log.i("guida", "F2 button pressed")
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
            android.view.KeyEvent.KEYCODE_F1,
            android.view.KeyEvent.KEYCODE_F2,
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
            android.view.KeyEvent.KEYCODE_POWER -> {
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
        val ssid = "3609"  // Your actual WiFi name
        val password = "66668888"  // Your actual WiFi password
        
        android.util.Log.i("guida", "Testing WiFi connection to: $ssid")
        viewModel.connectToWifi(ssid, password)
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
    onTestWifiClick: () -> Unit
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
            verticalArrangement = Arrangement.Top
        ) {
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
            Spacer(modifier = Modifier.height(24.dp))
            // --- Restore original main controls below ---
            when (uiState) {
                is MainViewModel.UiState.Initializing -> {
                    StatusCard(status = "Status", message = "Initializing...")
                    ControlButton(
                        iconResId = R.drawable.ic_retry,
                        text = "Initializing",
                        onClick = { /* Disabled */ },
                        backgroundColor = Color.Gray,
                        enabled = false
                    )
                }
                is MainViewModel.UiState.AwaitingInput -> {
                    StatusCard(status = "Status", message = "Ready")
                    ControlButton(
                        iconResId = R.drawable.ic_record,
                        text = "Capture",
                        onClick = onControlButtonClick,
                        backgroundColor = MaterialTheme.colorScheme.primary,
                        enabled = true
                    )
                }
                is MainViewModel.UiState.Processing -> {
                    StatusCard(status = "Status", message = uiState.message)
                    ControlButton(
                        iconResId = R.drawable.ic_processing,
                        text = "Processing",
                        onClick = { /* Disabled */ },
                        backgroundColor = Color.Gray,
                        enabled = false
                    )
                }
                is MainViewModel.UiState.Error -> {
                    StatusCard(status = "Error occurred", message = uiState.message, isError = true)
                    ControlButton(
                        iconResId = R.drawable.ic_retry,
                        text = "Retry",
                        onClick = onRetryClick,
                        backgroundColor = Color.Red,
                        enabled = true
                    )
                }
                is MainViewModel.UiState.ToastMessage -> { /* No UI, handled by Toast above */ }
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
    GuidaApp0606Theme {
        MainScreen(
            uiState = MainViewModel.UiState.AwaitingInput,
            onControlButtonClick = {},
            onSettingsClick = {},
            onRetryClick = {},
            wifiState = null,
            onTestWifiClick = {}
        )
    }
}