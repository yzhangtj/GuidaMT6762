package com.guidaco.testapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private val TAG = "TestAppMain"
    private val OPENAI_API_KEY = "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"
    private val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
    private val DEFAULT_PROMPT = "What do you see?"
    private var imageUri: Uri? = null

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val f1Button = findViewById<Button>(R.id.f1Button)
        f1Button.setOnClickListener {
            Log.i(TAG, "F1 button pressed. Checking permissions...")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
            } else {
                captureImage()
            }
        }
    }

    private fun captureImage() {
        Log.i(TAG, "Launching camera intent...")
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        cameraLauncher.launch(intent)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val bitmap = data?.extras?.get("data") as? Bitmap
            if (bitmap != null) {
                Log.i(TAG, "Image captured. Compressing and encoding...")
                val base64Image = bitmapToBase64(bitmap)
                Log.i(TAG, "Image encoded. Sending to OpenAI...")
                sendToOpenAI(base64Image, DEFAULT_PROMPT)
            } else {
                Log.e(TAG, "Failed to get bitmap from camera intent.")
            }
        } else {
            Log.e(TAG, "Camera intent cancelled or failed.")
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val byteArray = outputStream.toByteArray()
        Log.i(TAG, "Bitmap size: ${byteArray.size} bytes")
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun sendToOpenAI(base64Image: String, prompt: String) {
        Log.i(TAG, "Preparing JSON payload for OpenAI Vision API...")
        val imageDataUri = "data:image/jpeg;base64,$base64Image"
        val payload = JSONObject().apply {
            put("model", "gpt-4o")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", imageDataUri)
                            })
                        })
                    })
                })
            })
            put("max_tokens", 300)
        }
        Log.i(TAG, "Payload: $payload")
        val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), payload.toString())
        val request = Request.Builder()
            .url(OPENAI_API_URL)
            .addHeader("Authorization", "Bearer $OPENAI_API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()
        Log.i(TAG, "Sending request to OpenAI Vision API...")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "OpenAI API request failed: ${e.message}", e)
            }
            override fun onResponse(call: Call, response: Response) {
                Log.i(TAG, "OpenAI API response code: ${response.code}")
                val body = response.body?.string()
                Log.i(TAG, "OpenAI API response body: $body")
                if (!response.isSuccessful) {
                    Log.e(TAG, "OpenAI API error: $body")
                }
            }
        })
    }
} 