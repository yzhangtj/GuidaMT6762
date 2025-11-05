package com.guidaco.guidaglassesapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow<UiState>(UiState.Initializing)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    private val cameraManager = CameraManager(application)
    private val radarManager = RadarManager()
    private val alertManager = AlertManager() // Add AlertManager
    private val httpClient = HttpClient()
    private val settingsDataStore = SettingsDataStore(application)
    // Cache latest phone API URL observed to avoid suspending calls inside collectors
    @Volatile
    private var latestPhoneApiUrl: String? = null
    private val wifiManager = GuidaWifiManager(application)
    private val audioManager = AudioManager(application)
    private var isListening = false
    private var speechRecognitionManager: SpeechRecognitionManager? = null
    private var lastCapturedImage: File? = null
    private var lastRecognizedText: String? = null
    private var speechJob: Job? = null
    private var isSpeakingResponse = false

    val wifiState: StateFlow<GuidaWifiManager.WifiState> = wifiManager.wifiState

    init {
        // Connect RadarManager to AlertManager before starting
        radarManager.setAlertManager(alertManager)
        // Start reading from the radar as soon as the ViewModel is created
        radarManager.start()

        // Observe phone API URL in the background and update HttpClient when it changes
        CoroutineScope(Dispatchers.IO).launch {
            try {
                settingsDataStore.phoneApiUrl.collect { url ->
                    latestPhoneApiUrl = url
                    if (!url.isNullOrBlank()) {
                        Log.i("guida", "MainViewModel observed phoneApiUrl change: $url - updating HttpClient")
                        httpClient.updateServerUrl(url)
                    } else {
                        Log.i("guida", "MainViewModel observed phoneApiUrl cleared")
                        httpClient.updateServerUrl("")
                    }
                }
            } catch (e: Exception) {
                Log.e("guida", "Error collecting phoneApiUrl: ${e.message}", e)
            }
        }

        // Observe WiFi connection state and probe phone API URL once after connection
        CoroutineScope(Dispatchers.IO).launch {
            try {
                wifiState.collect { state ->
                    if (state is GuidaWifiManager.WifiState.Connected) {
                        try {
                            // Use cached latestPhoneApiUrl to avoid suspending calls here
                            val url = latestPhoneApiUrl
                            if (!url.isNullOrBlank()) {
                                Log.i("guida", "WiFi connected. Probing cached phone API URL: $url")
                                httpClient.probePhoneUrl(url) { ok, endpoint ->
                                    if (ok) {
                                        Log.i("guida", "Phone API reachable at $endpoint")
                                        httpClient.updateServerUrl(url)
                                    } else {
                                        Log.w("guida", "Phone API not reachable: $endpoint — clearing stored phone URL and disabling phone routing")
                                        try {
                                            // clear persisted settings asynchronously (DataStore setters are suspend)
                                            CoroutineScope(Dispatchers.IO).launch {
                                                try {
                                                    settingsDataStore.setPhoneApiUrl(null)
                                                    settingsDataStore.setUsePhoneGemma(false)
                                                    latestPhoneApiUrl = null
                                                } catch (e: Exception) {
                                                    Log.e("guida", "Failed to clear phone settings: ${e.message}", e)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e("guida", "Failed to schedule clearing phone settings: ${e.message}", e)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("guida", "Error scheduling phone URL probe: ${e.message}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("guida", "Error collecting wifiState: ${e.message}", e)
            }
        }
    }

    fun initialize(lifecycleOwner: LifecycleOwner) {
        _uiState.value = UiState.Initializing
        cameraManager.initializeCamera(
            lifecycleOwner = lifecycleOwner,
            onSuccess = {
                _uiState.value = UiState.AwaitingInput
            },
            onError = { exception ->
                _uiState.value = UiState.Error("Camera failed: ${exception.message}")
            }
        )
    }

    fun setSpeechRecognitionManager(manager: SpeechRecognitionManager) {
        speechRecognitionManager = manager
    }

    fun onF1ButtonPressed() {
        Log.i("guida", "[MainViewModel] onF1ButtonPressed called. Current state: ${_uiState.value}, isListening: $isListening")
        if (isSpeakingResponse) {
            Log.i("guida", "[MainViewModel] Ignored F1: currently speaking response")
            return
        }
        if (!isListening) {
            // Start capture and listening
            audioManager.playUiNotificationTone()
            _uiState.value = UiState.Processing("Capturing image and starting speech recognition...")
            cameraManager.captureImage(
                onImageCaptured = { file ->
                    lastCapturedImage = file
                    _uiState.value = UiState.Processing("Image captured. Listening...")
                    isListening = true
                    speechRecognitionManager?.let { manager ->
                        // Start listening (suspend function, so launch in coroutine)
                        speechJob = CoroutineScope(Dispatchers.Main).launch {
                            val result = manager.startListeningForSpeech(
                                onPartialResult = { partial ->
                                    Log.i("guida", "[SpeechRecognition] Partial: $partial")
                                    _uiState.value = UiState.Processing("Listening: $partial")
                                },
                                onError = { err ->
                                    _uiState.value = UiState.Error("Speech error: $err")
                                    isListening = false
                                }
                            )
                            lastRecognizedText = result
                            isListening = false
                            _uiState.value = UiState.Processing("Speech recognition completed. Text: ${result ?: "No speech detected"}")
                        }
                    } ?: run {
                        _uiState.value = UiState.Error("SpeechRecognitionManager not set")
                        isListening = false
                    }
                },
                onError = { exception ->
                    _uiState.value = UiState.Error("Failed to capture image: ${exception.message}")
                }
            )
        } else {
            // Stop listening and send data
            audioManager.playUiNotificationTone()
            _uiState.value = UiState.Processing("Stopping speech recognition...")
            
            // Stop the speech recognition immediately and drain best text
            speechRecognitionManager?.stopListening()
            val drained = speechRecognitionManager?.drainFinalText()
            if (!drained.isNullOrEmpty()) {
                lastRecognizedText = drained
            }
            isListening = false

            // Cancel any pending speech job
            speechJob?.cancel()
            speechJob = null

            _uiState.value = UiState.Processing("Speech stopped. Sending data to server...")
            sendDataToServer()
        }
    }

    private fun sendDataToServer() {
        val imageFile = lastCapturedImage
        val text = lastRecognizedText ?: ""
        
        Log.i("guida", "[MainViewModel] Sending data to server:")
        Log.i("guida", "[MainViewModel] Image file: ${imageFile?.absolutePath}")
        Log.i("guida", "[MainViewModel] Text: '$text'")
        
        if (imageFile == null) {
            _uiState.value = UiState.Error("No image captured")
            return
        }
        
        httpClient.sendImageAndText(
            imageFile = imageFile,
            recognizedText = text,
            onSuccess = { response, apiProvider ->
                // Show the response on screen and speak it
                _uiState.value = UiState.ShowingResponse(response, apiProvider)
                
                // Speak the response using online Qwen TTS (chunked)
                audioManager.playUiNotificationTone()
                isSpeakingResponse = true
                audioManager.speakOnlineQwen(getApplication(), response) {
                    // TTS completed for all chunks
                    isSpeakingResponse = false
                    CoroutineScope(Dispatchers.Main).launch {
                        kotlinx.coroutines.delay(500)
                        _uiState.value = UiState.AwaitingInput
                    }
                }
                
                // Reset to awaiting input after a delay
                CoroutineScope(Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(10000) // Show message for 10 seconds to allow TTS to complete and user to read
                    _uiState.value = UiState.AwaitingInput
                }
            },
            onError = { error ->
                _uiState.value = UiState.Error("Failed to send data: $error")
                // Also speak the error
                audioManager.speakOnlineQwen(getApplication(), "Error occurred: $error")
            }
        )
    }

    fun onF2ButtonPressed() {
        // Stub for video recording
        _uiState.value = UiState.Processing("F2 pressed: video recording not implemented yet.")
        // Reset to awaiting input after a short delay
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(2000) // Show message for 2 seconds
            _uiState.value = UiState.AwaitingInput
        }
    }

    fun onVolumeUpPressed() {
        // Test Android TTS when volume up is pressed
        Log.i("guida", "[MainViewModel] Volume up pressed - testing Qwen Online TTS")
        _uiState.value = UiState.Processing("Volume up pressed - testing Qwen Online TTS")
        audioManager.speakOnlineQwen(getApplication(), "Volume up") {
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(500)
                _uiState.value = UiState.AwaitingInput
            }
        }
    }

    fun onVolumeDownPressed() {
        // Stub for volume down
        _uiState.value = UiState.Processing("Volume down pressed.")
        // Reset to awaiting input after a short delay
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(2000) // Show message for 2 seconds
            _uiState.value = UiState.AwaitingInput
        }
    }
    
    // WiFi Management Functions
    fun connectToWifi(ssid: String, password: String) {
        _uiState.value = UiState.Processing("Connecting to WiFi: $ssid")
        wifiManager.connectToWifi(ssid, password)
        // Reset to awaiting input after a short delay
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(3000) // Show message for 3 seconds
            _uiState.value = UiState.AwaitingInput
        }
    }
    
    fun disconnectWifi() {
        wifiManager.disconnect()
        _uiState.value = UiState.Processing("WiFi disconnected")
        // Reset to awaiting input after a short delay
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(2000) // Show message for 2 seconds
            _uiState.value = UiState.AwaitingInput
        }
    }
    
    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled()
    
    fun enableWifi() {
        wifiManager.enableWifi()
        _uiState.value = UiState.Processing("WiFi enabled")
        // Reset to awaiting input after a short delay
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(2000) // Show message for 2 seconds
            _uiState.value = UiState.AwaitingInput
        }
    }
    
    // Test WiFi connection (for development)
    fun testWifiConnection() {
        // Replace with your actual WiFi credentials
        connectToWifi("19-3", "13813355882")
    }
    
    override fun onCleared() {
        super.onCleared()
        cameraManager.release()
        radarManager.stop()
        wifiManager.disconnect()
    }

    sealed class UiState {
        object Initializing : UiState()
        object AwaitingInput : UiState()
        data class Processing(val message: String) : UiState()
        data class Error(val message: String) : UiState()
        data class ToastMessage(val message: String) : UiState()
        data class ShowingResponse(val response: String, val apiProvider: String) : UiState()
    }
} 