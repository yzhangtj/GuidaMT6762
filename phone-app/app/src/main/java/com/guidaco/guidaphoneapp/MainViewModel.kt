package com.guidaco.guidaphoneapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow<UiState>(UiState.Initializing)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    private val cameraManager = CameraManager(application)

    private val httpClient = HttpClient()
    private val wifiManager = GuidaWifiManager(application)
    private var isListening = false
    private var speechRecognitionManager: SpeechRecognitionManager? = null
    private var lastCapturedImage: File? = null
    private var lastRecognizedText: String? = null

    val wifiState: StateFlow<GuidaWifiManager.WifiState> = wifiManager.wifiState

    init {
        // Initialization removed - no radar functionality in phone app
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

    fun onCaptureButtonPressed() {
        if (_uiState.value !is UiState.AwaitingInput) return
        _uiState.value = UiState.Processing("Capturing image...")
        cameraManager.captureImage(
            onImageCaptured = { file ->
                _uiState.value = UiState.Processing("Image captured. Path: ${file.absolutePath}")
            },
            onError = { exception ->
                _uiState.value = UiState.Error("Failed to capture image: ${exception.message}")
            }
        )
    }

    fun setSpeechRecognitionManager(manager: SpeechRecognitionManager) {
        speechRecognitionManager = manager
    }

    fun onF1ButtonPressed() {
        if (!isListening) {
            // Start capture and listening
            _uiState.value = UiState.Processing("Capturing image and starting speech recognition...")
            cameraManager.captureImage(
                onImageCaptured = { file ->
                    lastCapturedImage = file
                    _uiState.value = UiState.Processing("Image captured. Listening...")
                    isListening = true
                    speechRecognitionManager?.let { manager ->
                        // Start listening (suspend function, so launch in coroutine)
                        CoroutineScope(Dispatchers.Main).launch {
                            val result = manager.startListeningForSpeech(
                                onPartialResult = { partial ->
                                    _uiState.value = UiState.Processing("Listening: $partial")
                                },
                                onError = { err ->
                                    _uiState.value = UiState.Error("Speech error: $err")
                                    isListening = false
                                }
                            )
                            lastRecognizedText = result
                            isListening = false
                            _uiState.value = UiState.Processing("Speech done. Ready to send.")
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
            speechRecognitionManager?.stopListening()
            isListening = false
            _uiState.value = UiState.Processing("Stopped listening. Sending data to server...")
            
            // Send the captured image and recognized text to the server
            sendDataToServer()
        }
    }

    private fun sendDataToServer() {
        val imageFile = lastCapturedImage
        val text = lastRecognizedText ?: ""
        
        if (imageFile == null) {
            _uiState.value = UiState.Error("No image captured")
            return
        }
        
        httpClient.sendImageAndText(
            imageFile = imageFile,
            recognizedText = text,
            onSuccess = { response ->
                _uiState.value = UiState.Processing("Data sent successfully! Server response: $response")
                // Reset to awaiting input after a short delay
                CoroutineScope(Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(2000) // Show success message for 2 seconds
                    _uiState.value = UiState.AwaitingInput
                }
                // Here you could add TTS to speak the response
            },
            onError = { error ->
                _uiState.value = UiState.Error("Failed to send data: $error")
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
        // Stub for volume up
        _uiState.value = UiState.Processing("Volume up pressed.")
        // Reset to awaiting input after a short delay
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(2000) // Show message for 2 seconds
            _uiState.value = UiState.AwaitingInput
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
        connectToWifi("3609", "66668888")
    }
    
    override fun onCleared() {
        super.onCleared()
        cameraManager.release()
        wifiManager.disconnect()
    }

    sealed class UiState {
        object Initializing : UiState()
        object AwaitingInput : UiState()
        data class Processing(val message: String) : UiState()
        data class Error(val message: String) : UiState()
        data class ToastMessage(val message: String) : UiState()
    }
} 