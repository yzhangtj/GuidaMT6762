package com.guidaco.guidaglassesapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class HttpClient {
    companion object {
        private const val TAG = "HttpClient"
        private const val DEBUG_NETWORK = true
        
        // API Configuration - can be switched between providers
        private const val USE_QWEN = true // Set to true to use Qwen, false to use OpenAI, set to null to use Moondream

        // Qwen (千问) API configuration - use OpenAI compatible endpoint
        private const val QWEN_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        private val QWEN_API_KEY: String get() = BuildConfig.QWEN_API_KEY

        // Moondream API configuration
        private const val MOONDREAM_API_URL = "https://api.moondream.ai/v1/query"
        private val MOONDREAM_API_KEY: String get() = BuildConfig.MOONDREAM_API_KEY

        // OpenAI Vision API configuration
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
        private val OPENAI_API_KEY: String get() = BuildConfig.OPENAI_API_KEY
    }

    private val client = OkHttpClient.Builder()
        //.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("172.20.10.1", 7890)))
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor { message ->
                if (DEBUG_NETWORK) Log.d("OkHttp", message)
            }.apply {
                level = if (DEBUG_NETWORK) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
                redactHeader("Authorization")
                redactHeader("X-Moondream-Auth")
            }
        )
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                if (DEBUG_NETWORK) Log.d(TAG, "[NET] callStart: ${call.request().url}")
            }
            override fun dnsStart(call: Call, domainName: String) {
                if (DEBUG_NETWORK) Log.d(TAG, "[NET] dnsStart: $domainName")
            }
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
                if (DEBUG_NETWORK) Log.d(TAG, "[NET] dnsEnd: $domainName -> ${inetAddressList}")
            }
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                if (DEBUG_NETWORK) Log.d(TAG, "[NET] connectStart: dest=${inetSocketAddress}, proxy=$proxy")
            }
            override fun secureConnectStart(call: Call) {
                if (DEBUG_NETWORK) Log.d(TAG, "[NET] secureConnectStart (TLS)")
            }
            override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
                Log.e(TAG, "[NET] connectFailed: dest=${inetSocketAddress}, proxy=$proxy, protocol=$protocol", ioe)
            }
            override fun responseHeadersEnd(call: Call, response: Response) {
                if (DEBUG_NETWORK) Log.d(TAG, "[NET] responseHeadersEnd: code=${response.code} url=${response.request.url}")
            }
            override fun callFailed(call: Call, ioe: IOException) {
                Log.e(TAG, "[NET] callFailed: url=${call.request().url}", ioe)
            }
        })
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val requestId = System.currentTimeMillis().toString()
            Log.i(TAG, "[REQ $requestId] Making request to: ${originalRequest.url}")

            val newRequest = when {
                originalRequest.url.toString().contains("dashscope.aliyuncs.com") -> {
                    originalRequest.newBuilder()
                        .addHeader("Authorization", "Bearer $QWEN_API_KEY")
                        .addHeader("Content-Type", "application/json")
                        .build()
                }
                originalRequest.url.toString().contains("openai.com") -> {
                    originalRequest.newBuilder()
                        .addHeader("Authorization", "Bearer $OPENAI_API_KEY")
                        .addHeader("Content-Type", "application/json")
                        .build()
                }
                originalRequest.url.toString().contains("moondream.ai") -> {
                    originalRequest.newBuilder()
                        .addHeader("Authorization", "Bearer $MOONDREAM_API_KEY")
                        .addHeader("Content-Type", "application/json")
                        .build()
                }
                else -> originalRequest
            }
            val t0 = System.nanoTime()
            val response = chain.proceed(newRequest)
            val dtMs = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "[REQ $requestId] Completed: code=${response.code} in ${dtMs}ms url=${response.request.url}")
            response
        }
        .build()

    // Optional short-timeout client for phone-local calls
    private val phoneClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // If the phone API URL is set (via SettingsDataStore), requests will be routed here first.
    @Volatile
    private var phoneApiUrl: String? = null

    fun sendImageAndText(
        imageFile: File,
        recognizedText: String,
        onSuccess: (String, String) -> Unit, // Now returns (response, apiProvider)
        onError: (String) -> Unit
    ) {
        Log.i(TAG, "Preparing to send image: ${imageFile.name}, text: $recognizedText")
        Log.i("GuidaUpload", "Preparing to send image: ${imageFile.name}, text: $recognizedText")
        
        // If phone-local Gemma URL is configured, try it first.
        val localUrl = phoneApiUrl
        if (!localUrl.isNullOrBlank()) {
            Log.i(TAG, "Attempting to use phone-local Gemma at $localUrl")
            sendToPhoneApp(imageFile, recognizedText,
                { response -> onSuccess(response, "Phone Gemma") },
                { phoneErr ->
                    Log.w(TAG, "Phone Gemma failed: $phoneErr - falling back to cloud providers")
                    // Fallback to configured cloud provider
                    routeToCloudProviders(imageFile, recognizedText, onSuccess, onError)
                }
            )
            return
        }

        // No phone URL configured, route to configured cloud provider
        routeToCloudProviders(imageFile, recognizedText, onSuccess, onError)
    }

    private fun routeToCloudProviders(
        imageFile: File,
        recognizedText: String,
        onSuccess: (String, String) -> Unit,
        onError: (String) -> Unit
    ) {
        when {
            USE_QWEN == true -> {
                Log.i(TAG, "Using Qwen Vision API")
                sendToQwenAPI(imageFile, recognizedText,
                    { response -> onSuccess(response, "Qwen Vision") },
                    onError
                )
            }
            USE_QWEN == false -> {
                Log.i(TAG, "Using OpenAI Vision API")
                sendToOpenAIVisionAPI(imageFile, recognizedText,
                    { response -> onSuccess(response, "OpenAI Vision") },
                    onError
                )
            }
            else -> {
                Log.i(TAG, "Using Moondream API")
                sendToMoondreamAPI(imageFile, recognizedText,
                    { response -> onSuccess(response, "Moondream") },
                    onError
                )
            }
        }
    }

    private fun sendToQwenAPI(
        imageFile: File,
        recognizedText: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            Log.i(TAG, "Sending to Qwen API")

            // Check if image file exists and is readable
            if (!imageFile.exists() || !imageFile.canRead()) {
                onError("Image file not found or cannot be read")
                return
            }

            Log.i(TAG, "Original image file size: ${imageFile.length()} bytes")

            // Compress image to reduce payload size
            val compressedImageBytes = compressImage(imageFile)

            // Check if compressed image is too large (Qwen VL models typically support up to certain limits)
            if (compressedImageBytes.size > 20 * 1024 * 1024) {
                onError("Image is too large even after compression. Please try with a smaller image.")
                return
            }

            val imageBase64 = Base64.encodeToString(compressedImageBytes, Base64.NO_WRAP)
            val imageDataUri = "data:image/jpeg;base64,$imageBase64"

            Log.i(TAG, "Image compressed and converted to base64 data URI")
            Log.i(TAG, "Original size: ${imageFile.length()} bytes, compressed size: ${compressedImageBytes.size} bytes")
            Log.i(TAG, "Base64 data URI length: ${imageDataUri.length}")

            // Debug: Check API key (masked)
            Log.i(TAG, "API key length: ${QWEN_API_KEY.length}")
            Log.i(TAG, "API key starts with: ${QWEN_API_KEY.take(4)}****")

            // Validate API key format (should start with sk-)
            if (QWEN_API_KEY.isEmpty()) {
                onError("Qwen API key is empty")
                return
            }

            if (!QWEN_API_KEY.startsWith("sk-")) {
                onError("Qwen API key format appears invalid (should start with 'sk-')")
                return
            }

            Log.i(TAG, "Qwen API key validation passed")

            // Use the speech text as the user question, or a default question if no speech
            val userQuestion = if (recognizedText.isNotEmpty()) {
                "You are a concise, second-person visual narrator. Answer user's question concisely in plain text (no markdown, no lists) and ask ONE specific follow-up question that helps the user act next. User's question is: $recognizedText. Total length ≤ 80 words."
            } else {
                "You are a concise, second-person visual narrator. Answer in a few sentences, plain text (no markdown, no lists). Structure your answer as exactly TWO parts: 1) A short second-person scene sentence that starts with \"You are …\" describing the image. 2) Ask ONE specific follow-up question that helps the user act next. Constraints: Total length ≤ 80 words."
            }

            Log.i(TAG, "Qwen Vision question: '$userQuestion'")

            // Create JSON payload for Qwen OpenAI-compatible chat completions
            val messagesArray = org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("type", "text")
                            put("text", userQuestion)
                        })
                        put(org.json.JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", org.json.JSONObject().apply { put("url", imageDataUri) })
                        })
                    })
                })
            }

            val jsonPayload = org.json.JSONObject().apply {
                put("model", "qwen-vl-plus")
                put("messages", messagesArray)
                put("max_tokens", 1000)
                put("temperature", 0.7)
            }

            val requestBody = RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                jsonPayload.toString()
            )

            // Debug: Log masked auth header
            val authHeader = QWEN_API_KEY
            Log.i(TAG, "Qwen Authorization header: Bearer ${authHeader.take(4)}****")

            val request = Request.Builder()
                .url(QWEN_API_URL)
                .post(requestBody)
                .build()

            // Note: Headers are added by the interceptor
            Log.i(TAG, "Request created, headers will be added by interceptor")
            Log.i(TAG, "Payload sizes: imageCompressed=${compressedImageBytes.size}B, json=${jsonPayload.toString().length} chars")

            Log.i(TAG, "Sending request to Qwen Vision API: $QWEN_API_URL")
            Log.i("GuidaUpload", "Sending request to Qwen Vision API with question: $userQuestion")

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Qwen Vision API request failed", e)
                    Log.e("GuidaUpload", "Qwen Vision API request failed: ${e.message}")

                    val errorMessage = when {
                        e.message?.contains("timeout") == true -> "Request timed out. Please check your internet connection and try again."
                        e.message?.contains("Unable to resolve host") == true -> "Cannot connect to Qwen API. Please check your internet connection."
                        e.message?.contains("SSL") == true -> "Secure connection failed. Please check your internet connection."
                        else -> "Network error: ${e.message}"
                    }

                    onError(errorMessage)
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string() ?: ""
                    Log.i(TAG, "Qwen Vision API response: ${response.code} - $responseBody")
                    Log.i("GuidaUpload", "Qwen Vision API response: ${response.code} - $responseBody")

                    if (response.isSuccessful) {
                        try {
                            // Parse the JSON response for OpenAI-compatible schema first
                            val jsonResponse = org.json.JSONObject(responseBody)
                            var extracted: String? = null

                            // OpenAI-compatible: { choices: [ { message: { content: "..." } } ] }
                            val compatChoices = jsonResponse.optJSONArray("choices")
                            if (compatChoices != null && compatChoices.length() > 0) {
                                val first = compatChoices.getJSONObject(0)
                                val msg = first.optJSONObject("message")
                                if (msg != null) {
                                    val contentStr = msg.optString("content", "")
                                    if (contentStr.isNotEmpty()) extracted = contentStr
                                }
                            }

                            // Legacy DashScope: { output: { choices: [ { message: { content: [ {text: "..."}, ... ] } } ] } }
                            if (extracted == null) {
                                val output = jsonResponse.optJSONObject("output")
                                if (output != null) {
                                    val choices = output.optJSONArray("choices")
                                    if (choices != null && choices.length() > 0) {
                                        val firstChoice = choices.getJSONObject(0)
                                        val message = firstChoice.optJSONObject("message")
                                        if (message != null) {
                                            val contentValue = message.opt("content")
                                            var aggregatedText = ""
                                            if (contentValue is org.json.JSONArray) {
                                                for (i in 0 until contentValue.length()) {
                                                    val item = contentValue.optJSONObject(i)
                                                    if (item != null) {
                                                        val textPart = item.optString("text", "")
                                                        if (textPart.isNotEmpty()) {
                                                            if (aggregatedText.isNotEmpty()) aggregatedText += "\n"
                                                            aggregatedText += textPart
                                                        }
                                                    }
                                                }
                                            } else if (contentValue is String) {
                                                aggregatedText = contentValue
                                            }
                                            if (aggregatedText.isNotEmpty()) extracted = aggregatedText
                                        }
                                    }
                                }
                            }

                            if (!extracted.isNullOrEmpty()) {
                                Log.i(TAG, "Qwen Vision answer: $extracted")
                                onSuccess(extracted)
                            } else {
                                onError("No output in Qwen response")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing Qwen Vision response: ${e.message}")
                            onSuccess("Qwen response: $responseBody")
                        }
                    } else {
                        Log.e(TAG, "Qwen Vision API error: ${response.code}")
                        onError("Qwen Vision API error: ${response.code} - $responseBody")
                    }
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing Qwen request", e)
            onError("Qwen preparation error: ${e.message}")
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
                "You are a concise, second-person visual narrator. Answer user's question concisely in plain text (no markdown, no lists) and ask ONE specific follow-up question that helps the user act next. User's question is: $recognizedText. Total length ≤ 80 words."
            } else {
                "You are a concise, second-person visual narrator. Answer in a few sentences, plain text (no markdown, no lists). Structure your answer as exactly TWO parts: 1) A short second-person scene sentence that starts with \"You are …\" describing the image. 2) Ask ONE specific follow-up question that helps the user act next. Constraints: Total length ≤ 80 words."
            }
            
            Log.i(TAG, "Moondream question: '$question'")
            
            // Debug: Check API key (masked)
            Log.i(TAG, "API key length: ${MOONDREAM_API_KEY.length}")
            Log.i(TAG, "API key starts with: ${MOONDREAM_API_KEY.take(4)}****")
            
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
            
            // Debug: Log masked auth header
            val authHeader = MOONDREAM_API_KEY
            Log.i(TAG, "X-Moondream-Auth header starts: ${authHeader.take(4)}****")

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
            
            // NOTE: Removed old direct-connection connectivity tests.

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
            
            // Debug: Check API key (masked)
            Log.i(TAG, "API key length: ${OPENAI_API_KEY.length}")
            Log.i(TAG, "API key starts with: ${OPENAI_API_KEY.take(4)}****")
            
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
            
            // Use the speech text as the user question, or a default question if no speech
            val userQuestion = if (recognizedText.isNotEmpty()) {
                "You are a concise, second-person visual narrator. Answer user's question concisely in plain text (no markdown, no lists) and ask ONE specific follow-up question that helps the user act next. User's question is: $recognizedText. Total length ≤ 80 words."
            } else {
                "You are a concise, second-person visual narrator. Answer in a few sentences, plain text (no markdown, no lists). Structure your answer as exactly TWO parts: 1) A short second-person scene sentence that starts with \"You are …\" describing the image. 2) Ask ONE specific follow-up question that helps the user act next. Constraints: Total length ≤ 80 words."
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
            
            // Debug: Log masked auth header
            val authHeader = OPENAI_API_KEY
            Log.i(TAG, "OpenAI Authorization header: Bearer ${authHeader.take(4)}****")
            
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

    // NOTE: All custom connectivity test functions have been removed.

    fun updateServerUrl(newUrl: String) {
        // This method can be used to dynamically update the server URL
        val trimmed = newUrl.trim()
        phoneApiUrl = if (trimmed.isEmpty()) null else trimmed
        Log.i(TAG, "Server URL updated to: $phoneApiUrl")
    }

    /**
     * Send to phone-local Gemma service. If PHONE API URL is not set, calls onError immediately.
     * Expected endpoint: {phoneApiUrl}/v1/gemma/vision (POST, multipart form with 'image' file and 'question' field)
     */
    private fun sendToPhoneApp(
        imageFile: File,
        recognizedText: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val base = phoneApiUrl
        if (base.isNullOrBlank()) {
            onError("Phone API URL not configured")
            return
        }

        try {
            if (!imageFile.exists() || !imageFile.canRead()) {
                onError("Image file not found or cannot be read")
                return
            }

            val compressed = compressImage(imageFile)
            val imageBody = RequestBody.create("image/jpeg".toMediaTypeOrNull(), compressed)

            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("question", recognizedText)
                .addFormDataPart("image", imageFile.name, imageBody)
                .build()

            val endpoint = base.trimEnd('/') + "/v1/gemma/vision"
            val request = Request.Builder()
                .url(endpoint)
                .post(multipart)
                .build()

            Log.i(TAG, "Sending request to phone Gemma: $endpoint")
            phoneClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Phone Gemma request failed", e)
                    onError("Phone Gemma request failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    Log.i(TAG, "Phone Gemma response: ${response.code} - $body")
                    if (response.isSuccessful) {
                        try {
                            // Try to extract a sensible answer field
                            val json = JSONObject(body)
                            val answer = when {
                                json.has("answer") -> json.optString("answer")
                                json.has("result") -> json.optString("result")
                                json.has("response") -> json.optString("response")
                                else -> body
                            }
                            onSuccess(answer)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing phone response: ${e.message}", e)
                            onSuccess(body)
                        }
                    } else {
                        onError("Phone Gemma API error: ${response.code} - $body")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing Phone Gemma request: ${e.message}", e)
            onError("Phone Gemma preparation error: ${e.message}")
        }
    }

    /**
     * Probe the given phone API base URL for a healthy Gemma endpoint.
     * Calls onResult(true, endpoint) if any of the probe endpoints responds with 2xx/3xx.
     * Otherwise calls onResult(false, errorMessage).
     */
    fun probePhoneUrl(baseUrl: String, onResult: (Boolean, String?) -> Unit) {
        // Normalize
        val trimmed = baseUrl.trim().trimEnd('/')
        val candidates = listOf(
            "$trimmed/v1/gemma/health",
            "$trimmed/health",
            trimmed
        )

        // Short timeout client for probes
        val probeClient = phoneClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()

        // Try candidates sequentially
        fun tryNext(index: Int) {
            if (index >= candidates.size) {
                onResult(false, "No reachable endpoint for $baseUrl")
                return
            }
            val endpoint = candidates[index]
            try {
                val request = Request.Builder().url(endpoint).get().build()
                probeClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.w(TAG, "Probe failed for $endpoint: ${e.message}")
                        tryNext(index + 1)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val code = response.code
                        response.close()
                        if (code in 200..399) {
                            Log.i(TAG, "Probe success for $endpoint (code=$code)")
                            onResult(true, endpoint)
                        } else {
                            Log.w(TAG, "Probe non-OK for $endpoint (code=$code)")
                            tryNext(index + 1)
                        }
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Probe error for ${candidates[index]}: ${e.message}")
                tryNext(index + 1)
            }
        }

        tryNext(0)
    }
} 