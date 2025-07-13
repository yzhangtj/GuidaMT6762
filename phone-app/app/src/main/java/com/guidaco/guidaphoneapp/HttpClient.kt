package com.guidaco.guidaphoneapp

import android.util.Log
import okhttp3.*
import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class HttpClient {
    companion object {
        private const val TAG = "HttpClient"
        private const val SERVER_URL = "http://192.168.100.5:5000/upload" // Changed to your laptop's IP
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun sendImageAndText(
        imageFile: File,
        recognizedText: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.i(TAG, "Preparing to send image: ${imageFile.name}, text: $recognizedText")
        Log.i("GuidaUpload", "Preparing to send image: ${imageFile.name}, text: $recognizedText")
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("text", recognizedText)
            .addFormDataPart(
                "image", 
                imageFile.name,
                RequestBody.create("image/jpeg".toMediaTypeOrNull(), imageFile)
            )
            .build()

        val request = Request.Builder()
            .url(SERVER_URL)
            .post(requestBody)
            .build()

        Log.i(TAG, "Sending HTTP request to $SERVER_URL")
        Log.i("GuidaUpload", "Sending HTTP request to $SERVER_URL")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "HTTP request failed", e)
                Log.e("GuidaUpload", "HTTP request failed: ${e.message}")
                onError("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                Log.i(TAG, "Server response: ${response.code} - $responseBody")
                Log.i("GuidaUpload", "Server response: ${response.code} - $responseBody")
                
                if (response.isSuccessful) {
                    onSuccess(responseBody)
                } else {
                    onError("Server error: ${response.code} - $responseBody")
                }
            }
        })
    }

    fun updateServerUrl(newUrl: String) {
        // This method can be used to dynamically update the server URL
        Log.i(TAG, "Server URL updated to: $newUrl")
    }
} 