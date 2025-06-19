package com.guidaco.guidaapp0606

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.guidaco.guidaapp0606.ui.theme.GuidaApp0606Theme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private lateinit var settingsDataStore: SettingsDataStore
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsDataStore = SettingsDataStore(this)
        
        setContent {
            GuidaApp0606Theme {
                SettingsScreen(
                    settingsDataStore = settingsDataStore,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsDataStore: SettingsDataStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val speechVolume by settingsDataStore.speechVolume.collectAsState(initial = 1.0f)
    val speechRate by settingsDataStore.speechRate.collectAsState(initial = 1.0f)
    
    var volumeSliderValue by remember { mutableFloatStateOf(speechVolume) }
    var rateSliderValue by remember { mutableFloatStateOf(speechRate) }
    var showSavedMessage by remember { mutableStateOf(false) }
    
    LaunchedEffect(speechVolume) {
        volumeSliderValue = speechVolume
    }
    
    LaunchedEffect(speechRate) {
        rateSliderValue = speechRate
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Go back"
                        }
                    ) {
                        Text("←", fontSize = 24.sp)
                    }
                }
            )
        },
        modifier = Modifier.semantics {
            contentDescription = "Settings screen"
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // Speech Volume Setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.speech_volume),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics {
                            contentDescription = "Speech volume setting"
                        }
                    )
                    
                    Text(
                        text = "Volume: ${(volumeSliderValue * 100).toInt()}%",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Slider(
                        value = volumeSliderValue,
                        onValueChange = { volumeSliderValue = it },
                        valueRange = 0.1f..2.0f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Speech volume slider, current volume ${(volumeSliderValue * 100).toInt()} percent"
                            }
                    )
                }
            }
            
            // Speech Rate Setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.speech_rate),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics {
                            contentDescription = "Speech rate setting"
                        }
                    )
                    
                    Text(
                        text = "Rate: ${String.format("%.1f", rateSliderValue)}x",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Slider(
                        value = rateSliderValue,
                        onValueChange = { rateSliderValue = it },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Speech rate slider, current rate ${String.format("%.1f", rateSliderValue)} times normal speed"
                            }
                    )
                }
            }
            
            // Save Button
            Button(
                onClick = {
                    scope.launch {
                        settingsDataStore.setSpeechVolume(volumeSliderValue)
                        settingsDataStore.setSpeechRate(rateSliderValue)
                        showSavedMessage = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics {
                        contentDescription = "Save settings button"
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = stringResource(R.string.save_settings),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            // Saved Message
            if (showSavedMessage) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    showSavedMessage = false
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.settings_saved),
                        modifier = Modifier
                            .padding(16.dp)
                            .semantics {
                                contentDescription = "Settings have been saved"
                            },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
} 