package com.guidaco.guidaglassesapp

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.net.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

object QwenTtsClient {
    // Use DashScope multimodal-generation for TTS (China mainland)
    private const val BASE_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        val host = BuildConfig.PROXY_HOST
        val port = BuildConfig.PROXY_PORT.toIntOrNull()
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (!host.isNullOrBlank() && port != null && port > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
            android.util.Log.i("QwenTts", "Using HTTP proxy ${host}:${port}")
        } else {
            android.util.Log.i("QwenTts", "No proxy configured; connecting directly")
        }
        builder.build()
    }

    @Throws(Exception::class)
    fun synthesizeToFile(
        context: Context,
        apiKey: String,
        text: String,
        voice: String = "zhiyuan",
        format: String = "wav"
    ): File {
        require(apiKey.isNotBlank()) { "QWEN_API_KEY 为空，请在 local.properties 配置或 BuildConfig 注入。" }
        require(text.isNotBlank()) { "待合成文本不能为空。" }

        android.util.Log.i("QwenTts", "Synth start: model=qwen3-tts-flash voice=$voice len=${text.length}")

        // DashScope body per docs: model + input { text, voice, language_type }
        val supportedVoices = setOf(
            "Cherry","Ethan","Jennifer","Ryan","Katerina","Elias",
            "Jada","Dylan","Sunny","Li","Marcus","Roy","Peter","Rocky","Kiki","Eric"
        )
        val voiceToUse = if (supportedVoices.contains(voice)) voice else "Cherry"

        val bodyJson = JSONObject()
            .put("model", "qwen3-tts-flash")
            .put("input", JSONObject()
                .put("text", text)
                .put("voice", voiceToUse)
                .put("language_type", "Chinese")
            )
            .toString()

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { resp ->
            val respText = resp.body?.string() ?: throw RuntimeException("Empty response body")
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $respText")

            android.util.Log.i("QwenTts", "HTTP ${resp.code} received, length=${respText.length}")
            val root = JSONObject(respText)
            val output = if (root.has("output")) root.getJSONObject("output") else root

            // Prefer URL in output.audio.url (valid ~24h)
            val audioObj = output.optJSONObject("audio")
            val url = audioObj?.optString("url", null)
            if (!url.isNullOrBlank()) {
                val audioUrlRaw = url
                val audioUrl = audioUrlRaw.replaceFirst(Regex("^http://"), "https://")
                android.util.Log.i("QwenTts", "audioUrlRaw=$audioUrlRaw audioUrl(final)=$audioUrl")
                val getReq = Request.Builder().url(audioUrl).get().build()
                client.newCall(getReq).execute().use { getResp ->
                    val audioBytes = getResp.body?.bytes() ?: throw RuntimeException("Empty audio bytes")
                    if (!getResp.isSuccessful) throw RuntimeException("Audio GET ${getResp.code}")
                    val ext = if (format.lowercase() == "mp3") "mp3" else "wav"
                    val outFile = File(context.cacheDir, "qwen_tts_${UUID.randomUUID()}.$ext")
                    FileOutputStream(outFile).use { it.write(audioBytes) }
                    android.util.Log.i("QwenTts", "Audio saved (url): ${outFile.absolutePath} (${audioBytes.size} bytes)")
                    return outFile
                }
            }

            // Fallback: base64 data
            var audioData = output.optString("audio")
            if (audioData.isBlank() && output.has("audio_data")) audioData = output.getString("audio_data")
            require(audioData.isNotBlank()) { "响应里没有 audio/url：$respText" }

            val commaIdx = audioData.indexOf(',')
            val base64Part = if (commaIdx >= 0) audioData.substring(commaIdx + 1) else audioData
            val audioBytes = Base64.decode(base64Part, Base64.DEFAULT)
            val ext = if (format.lowercase() == "mp3") "mp3" else "wav"
            val outFile = File(context.cacheDir, "qwen_tts_${UUID.randomUUID()}.$ext")
            FileOutputStream(outFile).use { it.write(audioBytes) }
            android.util.Log.i("QwenTts", "Audio saved (base64): ${outFile.absolutePath} (${audioBytes.size} bytes)")
            return outFile
        }
    }

    fun playFile(context: Context, file: File, onDone: (() -> Unit)? = null, onError: ((Exception) -> Unit)? = null) {
        try {
            val mp = MediaPlayer()
            mp.setDataSource(context, Uri.fromFile(file))
            mp.setOnCompletionListener { it.release(); onDone?.invoke() }
            mp.setOnErrorListener { player, what, extra -> player.release(); onError?.invoke(RuntimeException("MediaPlayer error: $what,$extra")); true }
            android.util.Log.i("QwenTts", "MediaPlayer prepare/play: ${file.absolutePath}")
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            onError?.invoke(e)
        }
    }
}


