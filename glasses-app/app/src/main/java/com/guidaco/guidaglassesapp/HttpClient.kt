package com.guidaco.guidaglassesapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

class HttpClient {
    companion object {
        private const val TAG = "HttpClient"
        
        // API Configuration - can be switched between providers
        private const val USE_OPENAI = true // Set to false to use Moondream instead
        
        // Moondream API configuration
        private const val MOONDREAM_API_URL = "https://api.moondream.ai/v1/query"
        private const val MOONDREAM_API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJrZXlfaWQiOiI1ODllMmIwNi0yNGNlLTQ3NzQtODk0Ni04NmYyODBkYmY3ZWEiLCJvcmdfaWQiOiJpalo3Z0N4SWM0eDI0ZUdzRkFzeVh2TDhma2VUSzV3bSIsImlhdCI6MTc1MTk3MzM3MSwidmVyIjoxfQ.b2bT7AKSfifNIfwKVboZ41U-ETB7fvnPgF0xPxIC-H0"
        
        // OpenAI Vision API configuration
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
        private const val OPENAI_API_KEY = "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"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            
            // Log the request URL and method
            Log.i(TAG, "Making request to: ${originalRequest.url}")
            Log.i(TAG, "Request method: ${originalRequest.method}")
            
            // Add appropriate headers based on the API being called
            val newRequest = when {
                originalRequest.url.toString().contains("moondream.ai") -> {
                    Log.i(TAG, "Adding X-Moondream-Auth header to Moondream request")
                    originalRequest.newBuilder()
                        .addHeader("X-Moondream-Auth", MOONDREAM_API_KEY)
                        .addHeader("Content-Type", "application/json")
                        .build()
                }
                originalRequest.url.toString().contains("openai.com") -> {
                    Log.i(TAG, "Adding Authorization header to OpenAI request")
                    originalRequest.newBuilder()
                        .addHeader("Authorization", "Bearer $OPENAI_API_KEY")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("User-Agent", "GuidaGlassesApp/1.0")
                        .build()
                }
                else -> originalRequest
            }
            
            // Log all headers being sent
            Log.i(TAG, "Final request headers:")
            for (i in 0 until newRequest.headers.size) {
                Log.i(TAG, "  ${newRequest.headers.name(i)}: ${newRequest.headers.value(i)}")
            }
            
            try {
                val response = chain.proceed(newRequest)
                Log.i(TAG, "Response received: ${response.code} ${response.message}")
                response
            } catch (e: Exception) {
                Log.e(TAG, "Request failed with exception: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
        .addNetworkInterceptor { chain ->
            // This runs after DNS resolution, so we can see the actual IP
            val request = chain.request()
            Log.i(TAG, "Network interceptor - connecting to: ${request.url}")
            
            try {
                val response = chain.proceed(request)
                Log.i(TAG, "Network response: ${response.code}")
                response
            } catch (e: Exception) {
                Log.e(TAG, "Network error: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
        .build()

    fun sendImageAndText(
        imageFile: File,
        recognizedText: String,
        onSuccess: (String, String) -> Unit, // Now returns (response, apiProvider)
        onError: (String) -> Unit
    ) {
        Log.i(TAG, "Preparing to send image: ${imageFile.name}, text: $recognizedText")
        Log.i("GuidaUpload", "Preparing to send image: ${imageFile.name}, text: $recognizedText")
        
        // Route to appropriate API based on current provider
        if (USE_OPENAI) {
            Log.i(TAG, "Using OpenAI Vision API")
            sendToOpenAIVisionAPI(imageFile, recognizedText, 
                { response -> onSuccess(response, "OpenAI Vision") }, 
                onError
            )
        } else {
            Log.i(TAG, "Using Moondream API")
            sendToMoondreamAPI(imageFile, recognizedText, 
                { response -> onSuccess(response, "Moondream") }, 
                onError
            )
        }
    }
    
    private fun sendToMoondreamAPI(
        imageFile: File,
        recognizedText: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            Log.i(TAG, "Sending to Moondream API")
            
            // Check if image file exists and is readable
            if (!imageFile.exists() || !imageFile.canRead()) {
                onError("Image file not found or cannot be read")
                return
            }
            
            Log.i(TAG, "Original image file size: ${imageFile.length()} bytes")
            
            // Compress image to reduce payload size
            val compressedImageBytes = compressImage(imageFile)
            
            // Check if compressed image is too large (Moondream has 10MB limit)
            if (compressedImageBytes.size > 10 * 1024 * 1024) {
                onError("Image is too large even after compression. Please try with a smaller image.")
                return
            }
            
            val imageBase64 = Base64.encodeToString(compressedImageBytes, Base64.NO_WRAP)
            val imageDataUri = "data:image/jpeg;base64,$imageBase64"
            
            Log.i(TAG, "Image compressed and converted to base64 data URI")
            Log.i(TAG, "Original size: ${imageFile.length()} bytes, compressed size: ${compressedImageBytes.size} bytes")
            Log.i(TAG, "Base64 data URI length: ${imageDataUri.length}")
            
            // Use the speech text as the question, or a default question if no speech
            val question = if (recognizedText.isNotEmpty()) {
                recognizedText
            } else {
                "What do you see in this image? Describe what's happening."
            }
            
            Log.i(TAG, "Moondream question: '$question'")
            
            // Debug: Check API key
            Log.i(TAG, "API key length: ${MOONDREAM_API_KEY.length}")
            Log.i(TAG, "API key starts with: ${MOONDREAM_API_KEY.take(20)}...")
            
            // Validate API key format (should be a JWT token)
            if (MOONDREAM_API_KEY.isEmpty()) {
                onError("API key is empty")
                return
            }
            
            if (!MOONDREAM_API_KEY.startsWith("eyJ")) {
                onError("API key format appears invalid (should start with 'eyJ')")
                return
            }
            
            val jwtParts = MOONDREAM_API_KEY.split(".")
            if (jwtParts.size != 3) {
                onError("API key format appears invalid (should have 3 parts separated by dots)")
                return
            }
            
            Log.i(TAG, "API key validation passed")
            
            // Create JSON payload for Moondream API
            val jsonPayload = JSONObject().apply {
                put("image_url", imageDataUri)
                put("question", question)
                put("stream", false) // Non-streaming response
            }
            
            val requestBody = RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                jsonPayload.toString()
            )
            
            // Debug: Log the authorization header value
            val authHeader = MOONDREAM_API_KEY
            Log.i(TAG, "X-Moondream-Auth header: $authHeader")

        val request = Request.Builder()
                .url(MOONDREAM_API_URL)
            .post(requestBody)
            .build()

            // Note: Headers are added by the interceptor
            Log.i(TAG, "Request created, headers will be added by interceptor")
            
            Log.i(TAG, "Sending request to Moondream API: $MOONDREAM_API_URL")
            Log.i("GuidaUpload", "Sending request to Moondream API with question: $question")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Moondream API request failed", e)
                    Log.e("GuidaUpload", "Moondream API request failed: ${e.message}")
                    
                    val errorMessage = when {
                        e.message?.contains("timeout") == true -> "Request timed out. Please check your internet connection and try again."
                        e.message?.contains("Unable to resolve host") == true -> "Cannot connect to Moondream API. Please check your internet connection."
                        e.message?.contains("SSL") == true -> "Secure connection failed. Please check your internet connection."
                        else -> "Network error: ${e.message}"
                    }
                    
                    onError(errorMessage)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                    Log.i(TAG, "Moondream API response: ${response.code} - $responseBody")
                    Log.i("GuidaUpload", "Moondream API response: ${response.code} - $responseBody")
                
                if (response.isSuccessful) {
                        try {
                            // Parse the JSON response to extract the answer
                            val jsonResponse = JSONObject(responseBody)
                            val answer = jsonResponse.optString("answer", "No answer received")
                            val requestId = jsonResponse.optString("request_id", "unknown")
                            
                            Log.i(TAG, "Moondream answer: $answer")
                            Log.i(TAG, "Moondream request ID: $requestId")
                            
                            // Return just the answer without prefix for cleaner display
                            onSuccess(answer)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing Moondream response: ${e.message}")
                            onSuccess("Moondream response: $responseBody")
                        }
                } else {
                        Log.e(TAG, "Moondream API error: ${response.code}")
                        onError("Moondream API error: ${response.code} - $responseBody")
                    }
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing Moondream request", e)
            onError("Moondream preparation error: ${e.message}")
        }
    }

    private fun sendToOpenAIVisionAPI(
        imageFile: File,
        recognizedText: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            Log.i(TAG, "Sending to OpenAI Vision API")
            
            // Check if image file exists and is readable
            if (!imageFile.exists() || !imageFile.canRead()) {
                onError("Image file not found or cannot be read")
                return
            }
            
            Log.i(TAG, "Original image file size: ${imageFile.length()} bytes")
            
            // Compress image to reduce payload size
            val compressedImageBytes = compressImage(imageFile)
            
            // Check if compressed image is too large (OpenAI has 20MB limit)
            if (compressedImageBytes.size > 20 * 1024 * 1024) {
                onError("Image is too large even after compression. Please try with a smaller image.")
                return
            }
            
            val imageBase64 = Base64.encodeToString(compressedImageBytes, Base64.NO_WRAP)
            val imageDataUri = "data:image/jpeg;base64,$imageBase64"
            
            Log.i(TAG, "Image compressed and converted to base64 data URI")
            Log.i(TAG, "Original size: ${imageFile.length()} bytes, compressed size: ${compressedImageBytes.size} bytes")
            Log.i(TAG, "Base64 data URI length: ${imageDataUri.length}")
            
            // Debug: Check API key
            Log.i(TAG, "API key length: ${OPENAI_API_KEY.length}")
            Log.i(TAG, "API key starts with: ${OPENAI_API_KEY.take(20)}...")
            
            // Validate API key format (should start with sk-)
            if (OPENAI_API_KEY.isEmpty()) {
                onError("OpenAI API key is empty")
                return
            }
            
            if (!OPENAI_API_KEY.startsWith("sk-")) {
                onError("OpenAI API key format appears invalid (should start with 'sk-')")
                return
            }
            
            Log.i(TAG, "OpenAI API key validation passed")
            
            // Test connectivity to OpenAI before making the actual request
            testOpenAIConnectivity()
            
            // Use the speech text as the user question, or a default question if no speech
            val userQuestion = if (recognizedText.isNotEmpty()) {
                recognizedText
            } else {
                "What do you see in this image? Describe what's happening in detail."
            }
            
            Log.i(TAG, "OpenAI Vision question: '$userQuestion'")
            
            // Create JSON payload for OpenAI Vision API
            val messagesArray = JSONObject().apply {
                put("role", "user")
                put("content", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", userQuestion)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", imageDataUri)
                            put("detail", "high") // Use high detail for better analysis
                        })
                    })
                })
            }
            
            val jsonPayload = JSONObject().apply {
                put("model", "gpt-4o") // Use GPT-4 Vision model
                put("messages", org.json.JSONArray().apply {
                    put(messagesArray)
                })
                put("max_tokens", 1000)
                put("temperature", 0.7)
            }
            
            val requestBody = RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                jsonPayload.toString()
            )
            
            // Debug: Log the authorization header value
            val authHeader = OPENAI_API_KEY
            Log.i(TAG, "OpenAI Authorization header: Bearer $authHeader")
            
            val request = Request.Builder()
                .url(OPENAI_API_URL)
                .post(requestBody)
                .build()
            
            // Note: Headers are added by the interceptor
            Log.i(TAG, "Request created, headers will be added by interceptor")
            
            Log.i(TAG, "Sending request to OpenAI Vision API: $OPENAI_API_URL")
            Log.i("GuidaUpload", "Sending request to OpenAI Vision API with question: $userQuestion")
            
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "OpenAI Vision API request failed", e)
                    Log.e("GuidaUpload", "OpenAI Vision API request failed: ${e.message}")
                    
                    val errorMessage = when {
                        e.message?.contains("timeout") == true -> "Request timed out. Please check your internet connection and try again."
                        e.message?.contains("Unable to resolve host") == true -> "Cannot connect to OpenAI API. Please check your internet connection."
                        e.message?.contains("SSL") == true -> "Secure connection failed. Please check your internet connection."
                        else -> "Network error: ${e.message}"
                    }
                    
                    onError(errorMessage)
                }
                
                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string() ?: ""
                    Log.i(TAG, "OpenAI Vision API response: ${response.code} - $responseBody")
                    Log.i("GuidaUpload", "OpenAI Vision API response: ${response.code} - $responseBody")
                    
                    if (response.isSuccessful) {
                        try {
                            // Parse the JSON response to extract the answer
                            val jsonResponse = JSONObject(responseBody)
                            val choices = jsonResponse.getJSONArray("choices")
                            if (choices.length() > 0) {
                                val firstChoice = choices.getJSONObject(0)
                                val message = firstChoice.getJSONObject("message")
                                val content = message.getString("content")
                                
                                Log.i(TAG, "OpenAI Vision answer: $content")
                                
                                // Return the clean answer
                                onSuccess(content)
                            } else {
                                onError("No response choices received from OpenAI")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing OpenAI Vision response: ${e.message}")
                            onSuccess("OpenAI response: $responseBody")
                        }
                    } else {
                        Log.e(TAG, "OpenAI Vision API error: ${response.code}")
                        onError("OpenAI Vision API error: ${response.code} - $responseBody")
                    }
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing OpenAI Vision request", e)
            onError("OpenAI Vision preparation error: ${e.message}")
        }
    }

    private fun compressImage(imageFile: File): ByteArray {
        return try {
            // Load the image
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode image file")
                return imageFile.readBytes()
            }
            
            // Calculate new dimensions to keep image under reasonable size
            val maxWidth = 1024
            val maxHeight = 1024
            val ratio = Math.min(
                maxWidth.toFloat() / bitmap.width,
                maxHeight.toFloat() / bitmap.height
            )
            
            val newWidth = (bitmap.width * ratio).toInt()
            val newHeight = (bitmap.height * ratio).toInt()
            
            // Resize bitmap if needed
            val resizedBitmap = if (ratio < 1.0f) {
                Log.i(TAG, "Resizing image from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight}")
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }
            
            // Compress to JPEG with quality 85%
            val outputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            
            // Clean up
            if (resizedBitmap != bitmap) {
                resizedBitmap.recycle()
            }
            bitmap.recycle()
            
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing image: ${e.message}")
            // Fallback to original file if compression fails
            imageFile.readBytes()
        }
    }

    private fun testOpenAIConnectivity() {
        try {
            Log.i(TAG, "Testing connectivity to OpenAI...")
            
            // Try to resolve DNS first
            val url = java.net.URL(OPENAI_API_URL)
            val host = url.host
            Log.i(TAG, "Attempting to resolve host: $host")
            
            val addresses = java.net.InetAddress.getAllByName(host)
            Log.i(TAG, "DNS resolution successful. Found ${addresses.size} addresses:")
            for (address in addresses) {
                Log.i(TAG, "  - ${address.hostAddress}")
            }
            
            // Test direct connectivity only if not using proxy
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, 443), 5000) // 5 second timeout
            socket.close()
            Log.i(TAG, "Direct TCP connection test successful")
            
        } catch (e: Exception) {
            Log.e(TAG, "Connectivity test failed: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "This may indicate network restrictions or DNS issues")
        }
    }

    fun updateServerUrl(newUrl: String) {
        // This method can be used to dynamically update the server URL
        Log.i(TAG, "Server URL updated to: $newUrl")
    }
} 