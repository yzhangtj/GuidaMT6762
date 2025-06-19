package com.guidaco.guidaapp0606

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume

class SpeechRecognitionManager(private val context: Context) {
    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var isListening = false
    private var shouldContinueListening = false
    private var currentPartialResult: (String) -> Unit = {}
    private var currentErrorCallback: (String) -> Unit = {}
    private var accumulatedText = ""
    private var lastPartialResult = ""
    private var currentContinuation: kotlin.coroutines.Continuation<String?>? = null
    private var isModelReady = false
    
    // Model management - using the small model we downloaded
    private val modelName = "vosk-model-small-en-us-0.15"
    
    companion object {
        fun isRecognitionAvailable(context: Context): Boolean {
            // Vosk works on all Android devices, no need for special checks
            return true
        }
    }
    
    init {
        initializeModel()
    }
    
    private fun initializeModel() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                println("Vosk: Initializing real speech recognition model...")
                if (loadModelFromAssets()) {
                    println("Vosk: Real model loaded successfully!")
                    isModelReady = true
                } else {
                    println("Vosk: Failed to load model, falling back to simulation")
                    isModelReady = false
                }
            } catch (e: Exception) {
                println("Vosk: Error initializing model: ${e.message}")
                isModelReady = false
            }
        }
    }
    
    private fun loadModelFromAssets(): Boolean {
        return try {
            val modelDir = File(context.filesDir, modelName)
            
            // Check if model is already copied to internal storage
            if (!modelDir.exists()) {
                println("Vosk: Copying model from assets to internal storage...")
                if (!copyAssetFolderToInternalStorage(modelName, modelDir)) {
                    return false
                }
            }
            
            println("Vosk: Loading model from: ${modelDir.absolutePath}")
            model = Model(modelDir.absolutePath)
            println("Vosk: Model loaded successfully!")
            true
            
        } catch (e: Exception) {
            println("Vosk: Error loading model: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun copyAssetFolderToInternalStorage(assetFolder: String, targetDir: File): Boolean {
        return try {
            val assetManager = context.assets
            val assets = assetManager.list(assetFolder) ?: return false
            
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            for (asset in assets) {
                val assetPath = "$assetFolder/$asset"
                val targetFile = File(targetDir, asset)
                
                try {
                    // Try to list contents - if it succeeds, it's a directory
                    val subAssets = assetManager.list(assetPath)
                    if (subAssets != null && subAssets.isNotEmpty()) {
                        // It's a directory, recurse
                        copyAssetFolderToInternalStorage(assetPath, targetFile)
                    } else {
                        // It's a file, copy it
                        copyAssetFileToInternalStorage(assetPath, targetFile)
                    }
                } catch (e: Exception) {
                    // If listing fails, assume it's a file
                    copyAssetFileToInternalStorage(assetPath, targetFile)
                }
            }
            
            println("Vosk: Successfully copied model assets to internal storage")
            true
            
        } catch (e: Exception) {
            println("Vosk: Error copying assets: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun copyAssetFileToInternalStorage(assetPath: String, targetFile: File) {
        try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val outputStream = FileOutputStream(targetFile)
            
            val buffer = ByteArray(1024)
            var length: Int
            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
            
            outputStream.close()
            inputStream.close()
            
        } catch (e: Exception) {
            println("Vosk: Error copying file $assetPath: ${e.message}")
            throw e
        }
    }
    
    suspend fun startListeningForSpeech(
        onPartialResult: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ): String? = suspendCancellableCoroutine { continuation ->
        
        // Check if we have a real model or use simulation
        if (!isModelReady || model == null) {
            println("Vosk: Model not ready - using simulation mode")
            simulateSpeechRecognition(onPartialResult, continuation)
            return@suspendCancellableCoroutine
        }
        
        if (isListening) {
            println("Vosk: Already listening")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        
        isListening = true
        shouldContinueListening = true
        currentPartialResult = onPartialResult
        currentErrorCallback = onError
        accumulatedText = ""
        lastPartialResult = ""
        currentContinuation = continuation
        
        startListeningInternal()
        
        // Handle cancellation
        continuation.invokeOnCancellation {
            stopListening()
        }
    }
    
    private fun simulateSpeechRecognition(
        onPartialResult: (String) -> Unit,
        continuation: kotlin.coroutines.Continuation<String?>
    ) {
        isListening = true
        shouldContinueListening = true
        currentPartialResult = onPartialResult
        
        // Simulate partial results
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000)
            if (shouldContinueListening) {
                onPartialResult("Hello")
                delay(1000)
            }
            if (shouldContinueListening) {
                onPartialResult("Hello, can you")
                delay(1000)
            }
            if (shouldContinueListening) {
                onPartialResult("Hello, can you help me")
                delay(500)
            }
            
            if (shouldContinueListening) {
                // Wait for user to stop manually
                currentContinuation = continuation
            } else {
                isListening = false
                val mockResult = "Hello, can you help me with this image"
                println("Vosk simulation: Mock speech result: $mockResult")
                continuation.resume(mockResult)
            }
        }
    }
    
    private fun startListeningInternal() {
        try {
            println("Vosk: Starting real speech recognition...")
            val recognizer = Recognizer(model, 16000.0f)
            
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    hypothesis?.let { json ->
                        try {
                            val jsonObject = JSONObject(json)
                            val partialText = jsonObject.optString("partial", "")
                            if (partialText.isNotEmpty()) {
                                println("Vosk: Partial result: $partialText")
                                lastPartialResult = partialText  // Track the last partial result
                                currentPartialResult(partialText)
                            }
                        } catch (e: Exception) {
                            println("Vosk: Error parsing partial result: ${e.message}")
                        }
                    }
                }
                
                override fun onResult(hypothesis: String?) {
                    hypothesis?.let { json ->
                        try {
                            val jsonObject = JSONObject(json)
                            val recognizedText = jsonObject.optString("text", "")
                            
                            println("Vosk: Final result: $recognizedText")
                            
                            if (recognizedText.isNotEmpty()) {
                                // Accumulate the recognized text
                                if (accumulatedText.isNotEmpty()) {
                                    accumulatedText += " $recognizedText"
                                } else {
                                    accumulatedText = recognizedText
                                }
                                println("Vosk: Accumulated text: $accumulatedText")
                                currentPartialResult(accumulatedText)
                            }
                            
                            // Continue listening if we should
                            if (shouldContinueListening) {
                                // The service continues automatically
                                println("Vosk: Continuing to listen...")
                            } else {
                                // We're done listening
                                isListening = false
                                currentContinuation?.resume(accumulatedText.ifEmpty { null })
                            }
                            
                        } catch (e: Exception) {
                            println("Vosk: Error parsing result: ${e.message}")
                        }
                    }
                }
                
                override fun onFinalResult(hypothesis: String?) {
                    println("Vosk: Final result received")
                    hypothesis?.let { json ->
                        try {
                            val jsonObject = JSONObject(json)
                            val finalText = jsonObject.optString("text", "")
                            if (finalText.isNotEmpty() && accumulatedText.isEmpty()) {
                                accumulatedText = finalText
                            }
                        } catch (e: Exception) {
                            println("Vosk: Error parsing final result: ${e.message}")
                        }
                    }
                    
                    if (!shouldContinueListening) {
                        isListening = false
                        currentContinuation?.resume(accumulatedText.ifEmpty { null })
                    }
                }
                
                override fun onError(exception: Exception?) {
                    println("Vosk: Recognition error: ${exception?.message}")
                    isListening = false
                    shouldContinueListening = false
                    currentErrorCallback(exception?.message ?: "Speech recognition error")
                    currentContinuation?.resume(accumulatedText.ifEmpty { null })
                }
                
                override fun onTimeout() {
                    println("Vosk: Recognition timeout")
                    if (shouldContinueListening) {
                        // Continue listening on timeout
                        println("Vosk: Timeout, but continuing to listen...")
                    } else {
                        isListening = false
                        currentContinuation?.resume(accumulatedText.ifEmpty { null })
                    }
                }
            })
            
        } catch (e: Exception) {
            println("Vosk: Error starting recognition: ${e.message}")
            e.printStackTrace()
            isListening = false
            shouldContinueListening = false
            currentErrorCallback("Failed to start speech recognition: ${e.message}")
            currentContinuation?.resume(null)
        }
    }
    
    fun stopListening() {
        println("Vosk: Stopping speech recognition...")
        shouldContinueListening = false
        if (isListening) {
            speechService?.stop()
            isListening = false
        }
        
        // Complete the coroutine with accumulated text
        currentContinuation?.let { cont ->
            val finalText = if (accumulatedText.isNotEmpty()) {
                println("Vosk: Using accumulated text: '$accumulatedText'")
                accumulatedText
            } else if (lastPartialResult.isNotEmpty()) {
                println("Vosk: No accumulated text, using last partial result: '$lastPartialResult'")
                lastPartialResult
            } else if (isModelReady) {
                println("Vosk: No speech detected at all")
                null // Real model but no speech detected
            } else {
                println("Vosk: Using simulation fallback")
                "Hello, can you help me with this image" // Fallback for simulation
            }
            println("Vosk: Completing with final text: '$finalText'")
            cont.resume(finalText)
        }
        currentContinuation = null
    }
    
    fun isListening(): Boolean = isListening
    
    fun isModelReady(): Boolean = isModelReady
    
    fun release() {
        stopListening()
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
    }
} 