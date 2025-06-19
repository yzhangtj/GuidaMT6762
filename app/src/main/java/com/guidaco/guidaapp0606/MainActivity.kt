package com.guidaco.guidaapp0606

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.guidaco.guidaapp0606.ui.theme.GuidaApp0606Theme

class MainActivity : ComponentActivity() {
    private lateinit var audioManager: AudioManager
    private lateinit var cameraManager: CameraManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        audioManager = AudioManager(this)
        cameraManager = CameraManager(this)
        
        setContent {
            GuidaApp0606Theme {
                MainScreen(
                    audioManager = audioManager,
                    cameraManager = cameraManager,
                    onNavigateToSettings = {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    }
                )
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        audioManager.release()
        cameraManager.release()
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    audioManager: AudioManager,
    cameraManager: CameraManager,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    
    // Initialize camera when permissions are granted
    LaunchedEffect(Unit) {
        cameraManager.initializeCamera(
            lifecycleOwner = activity,
            onSuccess = { /* Camera initialized */ },
            onError = { /* Handle error */ }
        )
    }
    
    val viewModel: MainViewModel = viewModel(
        factory = MainViewModelFactory(context, audioManager, cameraManager)
    )
    
    val uiState by viewModel.uiState.collectAsState()
    
    // Permission handling
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )
    
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
        // Vosk works on all devices - no special availability checks needed
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.main_title),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.main_subtitle),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.semantics {
                            contentDescription = context.getString(R.string.settings_button_desc)
                        }
                    ) {
                        Text("⚙", fontSize = 24.sp)
                    }
                }
            )
        },
        modifier = Modifier.semantics {
            contentDescription = context.getString(R.string.main_content_desc)
        }
    ) { innerPadding ->
        
        if (!permissionsState.allPermissionsGranted) {
            // Show permission request UI
            PermissionRequestScreen(
                onRequestPermissions = {
                    permissionsState.launchMultiplePermissionRequest()
                }
            )
        } else {
            // Main app content
            MainContent(
                uiState = uiState,
                onRecordButtonPressed = viewModel::onRecordButtonPressed,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@Composable
fun PermissionRequestScreen(
    onRequestPermissions: () -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.permission_error),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = stringResource(R.string.camera_permission_required),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = stringResource(R.string.audio_permission_required),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
                
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Grant permissions button"
                        }
                ) {
                    Text(
                        text = "Grant Permissions",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun MainContent(
    uiState: MainUiState,
    onRecordButtonPressed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (uiState.appState) {
                    AppState.READY -> MaterialTheme.colorScheme.surfaceVariant
                    AppState.RECORDING -> MaterialTheme.colorScheme.primaryContainer
                    AppState.PROCESSING -> MaterialTheme.colorScheme.secondaryContainer
                    AppState.PLAYING -> MaterialTheme.colorScheme.tertiaryContainer
                    AppState.ERROR -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Status",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = uiState.statusMessage.ifEmpty { stringResource(R.string.ready) },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        contentDescription = "Current status: ${uiState.statusMessage.ifEmpty { context.getString(R.string.ready) }}"
                    }
                )
                
                // Show partial speech text during recording
                if (uiState.appState == AppState.RECORDING && uiState.partialSpeechText.isNotEmpty()) {
                    Text(
                        text = "\"${uiState.partialSpeechText}\"",
                        fontSize = 16.sp,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            contentDescription = "Speech: ${uiState.partialSpeechText}"
                        }
                    )
                }
                
                if (uiState.errorMessage.isNotEmpty()) {
                    Text(
                        text = uiState.errorMessage,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            contentDescription = "Error: ${uiState.errorMessage}"
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Main Record Button
        Button(
            onClick = onRecordButtonPressed,
            modifier = Modifier
                .size(200.dp)
                .semantics {
                    contentDescription = "Speech recognition button" + 
                            when (uiState.appState) {
                                AppState.READY -> ". Press to start listening for speech"
                                AppState.RECORDING -> ". Press to stop listening"
                                AppState.PROCESSING -> ". Currently processing"
                                AppState.PLAYING -> ". Currently playing response"
                                AppState.ERROR -> ". Press to retry"
                            }
                },
            colors = ButtonDefaults.buttonColors(
                containerColor = when (uiState.appState) {
                    AppState.READY -> MaterialTheme.colorScheme.primary
                    AppState.RECORDING -> MaterialTheme.colorScheme.error
                    AppState.PROCESSING -> MaterialTheme.colorScheme.secondary
                    AppState.PLAYING -> MaterialTheme.colorScheme.tertiary
                    AppState.ERROR -> MaterialTheme.colorScheme.error
                }
            ),
            shape = RoundedCornerShape(100.dp),
            enabled = uiState.appState != AppState.PROCESSING && uiState.appState != AppState.PLAYING
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = when (uiState.appState) {
                        AppState.READY -> "🎤"
                        AppState.RECORDING -> "⏹"
                        AppState.PROCESSING -> "⚙"
                        AppState.PLAYING -> "🔊"
                        AppState.ERROR -> "🔄"
                    },
                    fontSize = 48.sp
                )
                
                Text(
                    text = when (uiState.appState) {
                        AppState.READY -> stringResource(R.string.start_listening)
                        AppState.RECORDING -> stringResource(R.string.stop_listening)
                        AppState.PROCESSING -> stringResource(R.string.processing)
                        AppState.PLAYING -> "Playing"
                        AppState.ERROR -> "Retry"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
        

        
        Spacer(modifier = Modifier.weight(1f))
        
        // Instructions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Text(
                text = when (uiState.appState) {
                    AppState.READY -> "Press the button to start speech recognition and capture an image"
                    AppState.RECORDING -> "Listening for speech... Speak clearly into the microphone"
                    AppState.PROCESSING -> "Processing your speech and image, please wait..."
                    AppState.PLAYING -> "Playing text-to-speech response..."
                    AppState.ERROR -> "An error occurred. Press the button to try again"
                },
                modifier = Modifier
                    .padding(16.dp)
                    .semantics {
                        contentDescription = "Instructions: " + when (uiState.appState) {
                            AppState.READY -> "Press the button to start speech recognition and capture an image"
                            AppState.RECORDING -> "Listening for speech. Speak clearly into the microphone"
                            AppState.PROCESSING -> "Processing your speech and image, please wait"
                            AppState.PLAYING -> "Playing text-to-speech response"
                            AppState.ERROR -> "An error occurred. Press the button to try again"
                        }
                    },
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}