package com.guidaco.guidaglassesapp

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
import com.guidaco.guidaglassesapp.ui.theme.GuidaGlassesAppTheme
import kotlinx.coroutines.launch
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

class SettingsActivity : ComponentActivity() {
    private lateinit var settingsDataStore: SettingsDataStore
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsDataStore = SettingsDataStore(this)
        
        setContent {
            GuidaGlassesAppTheme {
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
    val phoneApiUrlFlow by settingsDataStore.phoneApiUrl.collectAsState(initial = null)
    val usePhoneGemmaFlow by settingsDataStore.usePhoneGemma.collectAsState(initial = false)
    
    var volumeSliderValue by remember { mutableFloatStateOf(speechVolume) }
    var rateSliderValue by remember { mutableFloatStateOf(speechRate) }
    var showSavedMessage by remember { mutableStateOf(false) }
    var phoneUrlText by remember { mutableStateOf(phoneApiUrlFlow ?: "") }
    var usePhoneGemma by remember { mutableStateOf(usePhoneGemmaFlow) }
    
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
                        // Persist phone settings as well
                        settingsDataStore.setPhoneApiUrl(phoneUrlText.takeIf { it.isNotBlank() })
                        settingsDataStore.setUsePhoneGemma(usePhoneGemma)
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

            // Phone Gemma Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Phone Gemma (local)",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Use Phone Gemma (preferred)")
                        Switch(checked = usePhoneGemma, onCheckedChange = { usePhoneGemma = it }, colors = SwitchDefaults.colors())
                    }

                    OutlinedTextField(
                        value = phoneUrlText,
                        onValueChange = { phoneUrlText = it },
                        label = { Text("Phone API URL (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "If your phone is acting as a hotspot, a typical URL is http://192.168.43.1:5000. When provided, the glasses will try the phone-local service first and fall back to the cloud if it is unreachable.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
} 