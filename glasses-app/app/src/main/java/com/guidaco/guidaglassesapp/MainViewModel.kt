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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow<UiState>(UiState.Initializing)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    private val cameraManager = CameraManager(application)
    private val radarManager = RadarManager()
    private val alertManager = AlertManager() // Add AlertManager
    private val httpClient = HttpClient()
    private val wifiManager = GuidaWifiManager(application)
    private val audioManager = AudioManager(application)
    // Store last-received credentials (from phone provisioning) so Test WiFi can reuse them
    private var pendingSsid: String? = null
    private var pendingPassword: String? = null
    private var isListening = false
    private var isRecordingVideo = false
    private var speechRecognitionManager: SpeechRecognitionManager? = null
    private var lastCapturedImage: File? = null
    private var lastRecognizedText: String? = null
    private var speechJob: Job? = null
    private var currentVideoFile: File? = null

    val wifiState: StateFlow<GuidaWifiManager.WifiState> = wifiManager.wifiState

    init {
        // Connect RadarManager to AlertManager before starting
        radarManager.setAlertManager(alertManager)
        // Start reading from the radar as soon as the ViewModel is created
        radarManager.start()
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
        // F1 short press: Do nothing (only long press for power on/off)
        Log.i("guida", "[MainViewModel] F1 short press - no action")
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
                
                // Speak the response to the user
                audioManager.speakOffline(response)
                
                // Reset to awaiting input after a delay
                CoroutineScope(Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(10000) // Show message for 10 seconds to allow TTS to complete and user to read
                    _uiState.value = UiState.AwaitingInput
                }
            },
            onError = { error ->
                _uiState.value = UiState.Error("Failed to send data: $error")
                // Also speak the error
                audioManager.speakOffline("Error occurred: $error")
            }
        )
    }

    fun onF2ButtonPressed() {
        Log.i("guida", "[MainViewModel] onF2ButtonPressed called. isRecordingVideo: $isRecordingVideo, isListening: $isListening")
        
        // If video is recording, stop video recording
        if (isRecordingVideo) {
            stopVideoRecording()
            return
        }
        
        // Otherwise, handle capture + speech recognition
        if (!isListening) {
            // Start capture and listening
            audioManager.speakOffline("Capturing image and starting speech recognition")
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
            audioManager.speakOffline("Stopping speech recognition and sending data")
            _uiState.value = UiState.Processing("Stopping speech recognition...")
            
            // Stop the speech recognition
            speechRecognitionManager?.stopListening()
            
            // Wait for the speech job to complete, then send data
            speechJob?.let { job ->
                CoroutineScope(Dispatchers.Main).launch {
                    job.join() // Wait for speech recognition to complete
                    isListening = false
                    _uiState.value = UiState.Processing("Speech stopped. Sending data to server...")
                    sendDataToServer()
                }
            } ?: run {
                // No speech job running, send data immediately
                isListening = false
                _uiState.value = UiState.Processing("Sending data to server...")
                sendDataToServer()
            }
        }
    }
    
    fun startVideoRecording() {
        if (isRecordingVideo) {
            Log.w("guida", "[MainViewModel] Video recording already in progress")
            return
        }
        
        Log.i("guida", "[MainViewModel] Starting video recording")
        audioManager.speakOffline("Starting video recording")
        _uiState.value = UiState.Processing("Starting video recording...")
        
        cameraManager.startVideoRecording(
            onVideoStarted = { file ->
                isRecordingVideo = true
                currentVideoFile = file
                _uiState.value = UiState.Processing("Recording video...")
                audioManager.speakOffline("Video recording started")
                Log.i("guida", "[MainViewModel] Video recording started: ${file.absolutePath}")
            },
            onError = { exception ->
                isRecordingVideo = false
                _uiState.value = UiState.Error("Failed to start video recording: ${exception.message}")
                audioManager.speakOffline("Video recording failed: ${exception.message}")
                Log.e("guida", "[MainViewModel] Video recording error: ${exception.message}", exception)
            }
        )
    }
    
    fun stopVideoRecording() {
        if (!isRecordingVideo) {
            Log.w("guida", "[MainViewModel] No video recording in progress")
            return
        }
        
        Log.i("guida", "[MainViewModel] Stopping video recording")
        audioManager.speakOffline("Stopping video recording")
        _uiState.value = UiState.Processing("Stopping video recording...")
        
        cameraManager.stopVideoRecording { file ->
            isRecordingVideo = false
            if (file != null) {
                _uiState.value = UiState.Processing("Video saved: ${file.name}")
                audioManager.speakOffline("Video recording stopped and saved")
                Log.i("guida", "[MainViewModel] Video recording stopped: ${file.absolutePath}")
                
                // Reset to awaiting input after a delay
                CoroutineScope(Dispatchers.Main).launch {
                    kotlinx.coroutines.delay(3000)
                    _uiState.value = UiState.AwaitingInput
                }
            } else {
                _uiState.value = UiState.Error("Failed to save video")
                audioManager.speakOffline("Failed to save video")
            }
            currentVideoFile = null
        }
    }
    
    fun isRecordingVideo(): Boolean = isRecordingVideo

    fun onVolumeUpPressed() {
        // Test Android TTS when volume up is pressed
        Log.i("guida", "[MainViewModel] Volume up pressed - testing Android TTS")
        _uiState.value = UiState.Processing("Volume up pressed - testing Android TTS")
        
        // Test Android TTS with "volume up" message
        audioManager.testAndroidTts("Volume up") {
            Log.i("guida", "[MainViewModel] Android TTS test completed")
            // Reset to awaiting input after TTS completes
        CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(500) // Brief delay after TTS completes
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
        // Save pending credentials so UI/test flow can re-use them
        pendingSsid = ssid
        pendingPassword = password
        _uiState.value = UiState.Processing("Connecting to WiFi: $ssid")
        wifiManager.connectToWifi(ssid, password)
        // Reset to awaiting input after a short delay
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(3000) // Show message for 3 seconds
            _uiState.value = UiState.AwaitingInput
        }
    }

    fun getPendingCredentials(): Pair<String, String>? {
        return if (!pendingSsid.isNullOrEmpty() && pendingPassword != null) {
            Pair(pendingSsid!!, pendingPassword!!)
        } else null
    }

    /** Called by MainActivity to update phone-local API URL when provisioning sends it */
    fun updatePhoneApiUrl(newUrl: String) {
        try {
            httpClient.updateServerUrl(newUrl)
            Log.i("guida", "Phone API URL updated in HttpClient: $newUrl")
        } catch (e: Exception) {
            Log.e("guida", "Failed to update phone API URL: ${e.message}", e)
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