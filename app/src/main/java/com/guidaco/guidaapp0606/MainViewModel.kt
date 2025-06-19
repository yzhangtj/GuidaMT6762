package com.guidaco.guidaapp0606

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

enum class AppState {
    READY,
    RECORDING,
    PROCESSING,
    PLAYING,
    ERROR
}

data class MainUiState(
    val appState: AppState = AppState.READY,
    val statusMessage: String = "",
    val errorMessage: String = "",
    val isRecording: Boolean = false,
    val partialSpeechText: String = ""
)

class MainViewModel(
    private val context: Context,
    private val audioManager: AudioManager,
    private val cameraManager: CameraManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val speechRecognitionManager = SpeechRecognitionManager(context)
    private var currentImageFile: File? = null
    
    fun onRecordButtonPressed() {
        when (_uiState.value.appState) {
            AppState.READY -> startSpeechRecognition()
            AppState.RECORDING -> stopSpeechRecognition()
            AppState.PROCESSING, AppState.PLAYING -> {
                // Do nothing during processing or playback
            }
            AppState.ERROR -> {
                // Reset to ready state
                _uiState.value = _uiState.value.copy(
                    appState = AppState.READY,
                    errorMessage = "",
                    statusMessage = context.getString(R.string.ready),
                    partialSpeechText = ""
                )
            }
        }
    }
    
    private fun startSpeechRecognition() {
        viewModelScope.launch {
            try {
                println("Starting speech recognition process...")
                
                // Check if Vosk model is ready
                if (!speechRecognitionManager.isModelReady()) {
                    showError("Speech recognition model is loading. Please wait and try again.")
                    return@launch
                }
                
                println("Vosk model is ready, proceeding...")
                
                // Capture image first
                cameraManager.captureImage(
                    onImageCaptured = { imageFile ->
                        currentImageFile = imageFile
                        println("📸 Camera: Image captured successfully!")
                        println("📸 Camera: File path: ${imageFile.absolutePath}")
                        println("📸 Camera: File size: ${imageFile.length()} bytes")
                        println("📸 Camera: File exists: ${imageFile.exists()}")
                        
                        // Start speech recognition
                        _uiState.value = _uiState.value.copy(
                            appState = AppState.RECORDING,
                            isRecording = true,
                            statusMessage = "📸 Image captured! Listening... Speak now",
                            partialSpeechText = ""
                        )
                        
                        // Play start listening sound cue
                        audioManager.playStartListeningSound()
                        
                        // Start continuous listening for speech
                        startContinuousListening()
                    },
                    onError = { exception ->
                        println("📸 Camera: Error capturing image: ${exception.message}")
                        showError("Camera error: ${exception.message}")
                    }
                )
                
            } catch (e: Exception) {
                showError("Error starting speech recognition: ${e.message}")
            }
        }
    }
    
    private fun startContinuousListening() {
        viewModelScope.launch {
            try {
                println("Starting continuous speech recognition...")
                val finalText = speechRecognitionManager.startListeningForSpeech(
                    onPartialResult = { partialText ->
                        println("Partial result: '$partialText'")
                        _uiState.value = _uiState.value.copy(
                            partialSpeechText = partialText
                        )
                    },
                    onError = { errorMessage ->
                        println("Speech recognition error: $errorMessage")
                        showError("Speech recognition error: $errorMessage")
                    }
                )
                
                // This will be reached when speech recognition is manually stopped
                println("Continuous speech recognition completed with: '$finalText'")
                
                if (!finalText.isNullOrEmpty()) {
                    processTextAndImage(finalText, currentImageFile)
                } else {
                    showError("No speech was captured")
                }
                
            } catch (e: Exception) {
                println("Exception in continuous speech recognition: ${e.message}")
                showError("Speech recognition failed: ${e.message}")
            }
        }
    }
    
    private fun stopSpeechRecognition() {
        println("User manually stopped speech recognition")
        
        // Play stop listening sound cue
        audioManager.playStopListeningSound()
        
        speechRecognitionManager.stopListening()
        
        _uiState.value = _uiState.value.copy(
            appState = AppState.PROCESSING,
            isRecording = false,
            statusMessage = "Processing speech..."
        )
        
        // The speech recognition will complete and provide the final text
        // Processing will be handled in the startContinuousListening callback
    }
    
    private suspend fun processTextAndImage(recognizedText: String, imageFile: File?) {
        println("processTextAndImage called with text: '$recognizedText', imageFile: ${imageFile?.exists()}")
        
        if (imageFile == null) {
            println("Error: imageFile is null")
            showError("Missing image file")
            return
        }
        
        try {
            println("Setting UI state to PROCESSING")
            _uiState.value = _uiState.value.copy(
                appState = AppState.PROCESSING,
                isRecording = false,
                statusMessage = "Processing request..."
            )
            
            audioManager.speak("Processing your request")
            
            println("📤 Backend: Converting JPEG image to base64...")
            println("📤 Backend: Original JPEG size: ${imageFile.length()} bytes")
            println("📤 Backend: Speech text: '$recognizedText'")
            
            // Create multipart request
            val textPart = RequestBody.create(MultipartBody.FORM, recognizedText)
            val imagePart = MultipartBody.Part.createFormData(
                "image",
                imageFile.name,
                RequestBody.create("image/jpeg".toMediaTypeOrNull(), imageFile)
            )
            
            println("📤 Backend: Request ready - sending to http://52.160.88.93/navigate")
            println("📤 Backend: Request details:")
            println("📤 Backend: - Text part: $recognizedText")
            println("📤 Backend: - Image part: ${imageFile.name} (${imageFile.length()} bytes)")
            
            // Send request to backend
            val response = ApiClient.apiService.processTextAndImage(textPart, imagePart)

            // Print HTTP status code and message
            println("📤 Backend: HTTP status: ${response.code()} ${response.message()}")
            println("📤 Backend: Response headers: ${response.headers()}")

            // Print raw response body as string
            val rawResponseBody = response.body()?.toString()
            println("📤 Backend: Raw response body: $rawResponseBody")

            // Print error body if present
            val errorBodyString = response.errorBody()?.string()
            if (errorBodyString != null && errorBodyString.isNotEmpty()) {
                println("📤 Backend: Error body: $errorBodyString")
            }
            
            if (response.isSuccessful) {
                val backendResponse = response.body()
                println("📤 Backend: Raw response body: $backendResponse")
                val responseText = backendResponse?.output ?: run {
                    // Try to log the raw response string if output is missing
                    val rawString = response.raw().toString()
                    println("📤 Backend: No 'output' field. Raw response: $rawString")
                    rawString
                }
                println("📤 Backend: Received response: '$responseText'")
                val cleanText = responseText.replace("\n", " ")
                playTextResponse(cleanText)
            } else {
                val errorBody = response.errorBody()?.string()
                println("📤 Backend: Error response: ${response.code()} - ${response.message()} | Error body: $errorBody")
                showError("Server error: ${response.code()} | $errorBody")
            }
            
        } catch (e: Exception) {
            println("Exception in processTextAndImage: ${e.message}")
            e.printStackTrace()
            showError("Network error: ${e.message}")
        }
    }
    
    private fun playTextResponse(responseText: String) {
        _uiState.value = _uiState.value.copy(
            appState = AppState.PLAYING,
            statusMessage = "Playing response..."
        )

        // Split the response into sentences and speak them one by one with a short pause
        val sentences = responseText.split(Regex("(?<=[.!?])\\s+"))
        speakSentences(sentences, 0)
    }

    private fun speakSentences(sentences: List<String>, index: Int) {
        if (index < sentences.size) {
            audioManager.speak(sentences[index].trim()) {
                // After each sentence, wait a short time, then speak the next
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    speakSentences(sentences, index + 1)
                }, 200) // 200ms pause
            }
        } else {
            // All sentences spoken, return to READY state
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    appState = AppState.READY,
                    statusMessage = context.getString(R.string.ready),
                    partialSpeechText = ""
                )
            }
        }
    }
    
    private fun showError(message: String) {
        println("showError called with message: '$message'")
        _uiState.value = _uiState.value.copy(
            appState = AppState.ERROR,
            errorMessage = message,
            isRecording = false,
            statusMessage = context.getString(R.string.error_occurred),
            partialSpeechText = ""
        )
        audioManager.speak("Error occurred: $message")
    }
    

    
    override fun onCleared() {
        super.onCleared()
        speechRecognitionManager.release()
        audioManager.release()
        cameraManager.release()
    }
}

class MainViewModelFactory(
    private val context: Context,
    private val audioManager: AudioManager,
    private val cameraManager: CameraManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(context, audioManager, cameraManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
} 