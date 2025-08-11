package com.guidaco.guidaglassesapp

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.AudioManager as AndroidAudioManager
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.*

// Singleton TTS manager to prevent multiple instances
object TtsManager : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var hasRetried = false
    private var appContext: Context? = null
    
    fun init(context: Context) {
        if (tts == null) {
            appContext = context.applicationContext
            Log.i("GuidaAudioManager", "TtsManager: Creating single TTS instance")
            Log.i("GuidaAudioManager", "TtsManager: Current thread: ${Thread.currentThread().name}")
            Log.i("GuidaAudioManager", "TtsManager: Has main looper: ${Looper.myLooper() == Looper.getMainLooper()}")
            
            // Check TTS-related permissions
            checkTtsPermissions(context)
            
            // Check if TTS engines are available before initialization
            checkTtsEnginesAvailability(context)
            
            // Check TTS data availability
            checkTtsDataAvailability(context)
            
            Log.i("GuidaAudioManager", "TtsManager: Initializing with default TTS engine (should use Google TTS)")
            tts = TextToSpeech(appContext, this)
        } else {
            Log.i("GuidaAudioManager", "TtsManager: TTS instance already exists")
        }
    }
    
    private fun checkTtsPermissions(context: Context) {
        try {
            Log.i("GuidaAudioManager", "TtsManager: Checking TTS-related permissions...")
            
            val pm = context.packageManager
            
            // Check if we have BIND_TEXT_TO_SPEECH_SERVICE permission (system level)
            val bindTtsPermission = "android.permission.BIND_TEXT_TO_SPEECH_SERVICE"
            val bindTtsResult = pm.checkPermission(bindTtsPermission, context.packageName)
            Log.i("GuidaAudioManager", "TtsManager: BIND_TEXT_TO_SPEECH_SERVICE permission: ${if (bindTtsResult == android.content.pm.PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}")
            
            // Check RECORD_AUDIO permission (might be needed for some TTS engines)
            val recordAudioPermission = android.Manifest.permission.RECORD_AUDIO
            val recordAudioResult = androidx.core.content.ContextCompat.checkSelfPermission(context, recordAudioPermission)
            Log.i("GuidaAudioManager", "TtsManager: RECORD_AUDIO permission: ${if (recordAudioResult == android.content.pm.PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}")
            
            // Check INTERNET permission (for cloud TTS)
            val internetPermission = android.Manifest.permission.INTERNET
            val internetResult = androidx.core.content.ContextCompat.checkSelfPermission(context, internetPermission)
            Log.i("GuidaAudioManager", "TtsManager: INTERNET permission: ${if (internetResult == android.content.pm.PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}")
            
            // Check if we can query TTS services (might be restricted by package visibility)
            val ttsIntent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val ttsServices = pm.queryIntentServices(ttsIntent, 0)
            val canQueryTts = ttsServices.isNotEmpty()
            Log.i("GuidaAudioManager", "TtsManager: Can query TTS services: $canQueryTts")
            
            // Check package visibility for Google TTS specifically
            val googleTtsIntent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            googleTtsIntent.setPackage("com.google.android.tts")
            val googleTtsServices = pm.queryIntentServices(googleTtsIntent, 0)
            Log.i("GuidaAudioManager", "TtsManager: Can query Google TTS services specifically: ${googleTtsServices.isNotEmpty()}")
            
            // Check if we can resolve the TTS service intent
            val resolveInfo = pm.resolveService(ttsIntent, 0)
            Log.i("GuidaAudioManager", "TtsManager: Can resolve TTS service intent: ${resolveInfo != null}")
            if (resolveInfo != null) {
                Log.i("GuidaAudioManager", "TtsManager: Resolved TTS service: ${resolveInfo.serviceInfo.packageName}/${resolveInfo.serviceInfo.name}")
            }
            
            // Check if Google TTS is enabled (not just installed)
            try {
                val googleTtsAppInfo = pm.getApplicationInfo("com.google.android.tts", 0)
                Log.i("GuidaAudioManager", "TtsManager: Google TTS app enabled: ${googleTtsAppInfo.enabled}")
                Log.i("GuidaAudioManager", "TtsManager: Google TTS app flags: ${googleTtsAppInfo.flags}")
                Log.i("GuidaAudioManager", "TtsManager: Google TTS is system app: ${(googleTtsAppInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0}")
            } catch (e: Exception) {
                Log.e("GuidaAudioManager", "TtsManager: Error checking Google TTS app info: ${e.message}")
            }
            
            // Check current user and profile restrictions
            val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
            if (userManager != null) {
                // Note: No specific TTS restrictions available in UserManager
                Log.i("GuidaAudioManager", "TtsManager: UserManager available for future restriction checks")
            }
            
            // Check system settings for TTS
            try {
                val defaultTtsEngine = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    "tts_default_synth"
                )
                Log.i("GuidaAudioManager", "TtsManager: System default TTS engine: $defaultTtsEngine")
            } catch (e: Exception) {
                Log.w("GuidaAudioManager", "TtsManager: Could not read system TTS settings: ${e.message}")
            }
            
            // Check Android version and package visibility implications
            Log.i("GuidaAudioManager", "TtsManager: Android SDK version: ${android.os.Build.VERSION.SDK_INT}")
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Log.w("GuidaAudioManager", "TtsManager: Android 11+ detected - package visibility restrictions may apply")
                Log.w("GuidaAudioManager", "TtsManager: App may need <queries> declaration in AndroidManifest.xml")
            }
            
            // Check if app has target SDK 30+ (which enforces package visibility)
            try {
                val appInfo = pm.getApplicationInfo(context.packageName, 0)
                Log.i("GuidaAudioManager", "TtsManager: App target SDK: ${appInfo.targetSdkVersion}")
                if (appInfo.targetSdkVersion >= 30) {
                    Log.w("GuidaAudioManager", "TtsManager: App targets SDK 30+ - package visibility restrictions enforced")
                }
            } catch (e: Exception) {
                Log.w("GuidaAudioManager", "TtsManager: Could not check app target SDK: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e("GuidaAudioManager", "TtsManager: Error checking TTS permissions: ${e.message}")
        }
    }
    
    private fun checkTtsEnginesAvailability(context: Context) {
        try {
            Log.i("GuidaAudioManager", "TtsManager: Checking available TTS engines...")
            
            // Check for TTS services
            val pm = context.packageManager
            val ttsIntent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val ttsServices = pm.queryIntentServices(ttsIntent, 0)
            
            Log.i("GuidaAudioManager", "TtsManager: Found ${ttsServices.size} TTS services:")
            ttsServices.forEach { service ->
                Log.i("GuidaAudioManager", "TtsManager:   - ${service.serviceInfo.packageName}/${service.serviceInfo.name}")
                Log.i("GuidaAudioManager", "TtsManager:     Enabled: ${service.serviceInfo.enabled}")
            }
            
            // Check specifically for Google TTS
            try {
                val googleTtsInfo = pm.getPackageInfo("com.google.android.tts", 0)
                Log.i("GuidaAudioManager", "TtsManager: Google TTS package found:")
                Log.i("GuidaAudioManager", "TtsManager:   Version: ${googleTtsInfo.versionName} (${googleTtsInfo.versionCode})")
                Log.i("GuidaAudioManager", "TtsManager:   Enabled: ${pm.getApplicationInfo("com.google.android.tts", 0).enabled}")
            } catch (e: Exception) {
                Log.e("GuidaAudioManager", "TtsManager: Google TTS package not found: ${e.message}")
            }
            
            // Check for other common TTS engines
            val commonEngines = listOf(
                "com.samsung.SMT",
                "com.svox.pico",
                "com.android.tts",
                "com.acapelagroup.android.tts"
            )
            
            commonEngines.forEach { enginePackage ->
                try {
                    val engineInfo = pm.getPackageInfo(enginePackage, 0)
                    Log.i("GuidaAudioManager", "TtsManager: Found engine $enginePackage v${engineInfo.versionName}")
                } catch (e: Exception) {
                    Log.d("GuidaAudioManager", "TtsManager: Engine $enginePackage not found")
                }
            }
            
        } catch (e: Exception) {
            Log.e("GuidaAudioManager", "TtsManager: Error checking TTS engines: ${e.message}")
        }
    }
    
    private fun checkTtsDataAvailability(context: Context) {
        try {
            Log.i("GuidaAudioManager", "TtsManager: Checking TTS data availability...")
            
            // Check if TTS data is available
            val checkIntent = android.content.Intent()
            checkIntent.action = TextToSpeech.Engine.ACTION_CHECK_TTS_DATA
            val activities = context.packageManager.queryIntentActivities(checkIntent, 0)
            Log.i("GuidaAudioManager", "TtsManager: TTS data check activities found: ${activities.size}")
            
            activities.forEach { activity ->
                Log.i("GuidaAudioManager", "TtsManager:   - ${activity.activityInfo.packageName}/${activity.activityInfo.name}")
            }
            
            // Check current locale
            val currentLocale = Locale.getDefault()
            Log.i("GuidaAudioManager", "TtsManager: Current system locale: $currentLocale")
            Log.i("GuidaAudioManager", "TtsManager:   Language: ${currentLocale.language}")
            Log.i("GuidaAudioManager", "TtsManager:   Country: ${currentLocale.country}")
            Log.i("GuidaAudioManager", "TtsManager:   Display name: ${currentLocale.displayName}")
            
        } catch (e: Exception) {
            Log.e("GuidaAudioManager", "TtsManager: Error checking TTS data: ${e.message}")
        }
    }
    
    override fun onInit(status: Int) {
        Log.i("GuidaAudioManager", "TtsManager: onInit called with status: $status")
        Log.i("GuidaAudioManager", "TtsManager: onInit thread: ${Thread.currentThread().name}")
        
        when (status) {
            TextToSpeech.SUCCESS -> {
                isReady = true
                hasRetried = false
                Log.i("GuidaAudioManager", "TtsManager: TTS initialized successfully!")
                
                // Detailed language setup
                setupLanguageWithLogging()
                
                // List available engines with details
                listAvailableEnginesWithDetails()
                
                // Check current engine
                val currentEngine = tts?.defaultEngine
                Log.i("GuidaAudioManager", "TtsManager: Current active engine: $currentEngine")
                
                // Test basic functionality
                testBasicTtsFunctionality()
                
            }
            TextToSpeech.ERROR -> {
                isReady = false
                Log.e("GuidaAudioManager", "TtsManager: Initialization failed with ERROR (-1)")
                Log.e("GuidaAudioManager", "TtsManager: This usually means:")
                Log.e("GuidaAudioManager", "TtsManager:   1. TTS engine process binding failed")
                Log.e("GuidaAudioManager", "TtsManager:   2. Engine not installed or disabled")
                Log.e("GuidaAudioManager", "TtsManager:   3. Multiple TTS instances created")
                Log.e("GuidaAudioManager", "TtsManager:   4. Called from wrong thread (should be main)")
                
                // Single retry after 1 second
                if (!hasRetried && appContext != null) {
                    hasRetried = true
                    Log.i("GuidaAudioManager", "TtsManager: Retrying once in 1000ms")
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.i("GuidaAudioManager", "TtsManager: Retry attempt - shutting down previous instance")
                        tts?.shutdown()
                        tts = null
                        Log.i("GuidaAudioManager", "TtsManager: Retry attempt - creating new instance with default engine")
                        tts = TextToSpeech(appContext, this)
                    }, 1000)
                } else {
                    Log.e("GuidaAudioManager", "TtsManager: Max retries reached, TTS unavailable")
                    logTroubleshootingInfo()
                }
            }
            else -> {
                isReady = false
                Log.e("GuidaAudioManager", "TtsManager: Unknown init status: $status")
                Log.e("GuidaAudioManager", "TtsManager: Expected TextToSpeech.SUCCESS (0) or TextToSpeech.ERROR (-1)")
            }
        }
    }
    
    private fun setupLanguageWithLogging() {
        val defaultLocale = Locale.getDefault()
        Log.i("GuidaAudioManager", "TtsManager: Setting up language for: $defaultLocale")
        
        val languageResult = tts?.setLanguage(defaultLocale)
        Log.i("GuidaAudioManager", "TtsManager: setLanguage result: $languageResult")
        
        when (languageResult) {
            TextToSpeech.LANG_AVAILABLE -> {
                Log.i("GuidaAudioManager", "TtsManager: Language is available")
            }
            TextToSpeech.LANG_COUNTRY_AVAILABLE -> {
                Log.i("GuidaAudioManager", "TtsManager: Language and country are available")
            }
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                Log.i("GuidaAudioManager", "TtsManager: Language, country, and variant are available")
            }
            TextToSpeech.LANG_MISSING_DATA -> {
                Log.w("GuidaAudioManager", "TtsManager: Language data is missing for $defaultLocale")
                Log.w("GuidaAudioManager", "TtsManager: Trying English as fallback...")
                val englishResult = tts?.setLanguage(Locale.ENGLISH)
                Log.i("GuidaAudioManager", "TtsManager: English fallback result: $englishResult")
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.w("GuidaAudioManager", "TtsManager: Language not supported: $defaultLocale")
                Log.w("GuidaAudioManager", "TtsManager: Trying English as fallback...")
                val englishResult = tts?.setLanguage(Locale.ENGLISH)
                Log.i("GuidaAudioManager", "TtsManager: English fallback result: $englishResult")
            }
            else -> {
                Log.e("GuidaAudioManager", "TtsManager: Unknown language result: $languageResult")
            }
        }
        
        // Check what language was actually set
        val actualLanguage = tts?.language
        Log.i("GuidaAudioManager", "TtsManager: Actual language set: $actualLanguage")
    }
    
    private fun listAvailableEnginesWithDetails() {
        val engines = tts?.engines
        Log.i("GuidaAudioManager", "TtsManager: Available engines from TTS object: ${engines?.size ?: 0}")
        engines?.forEach { engine ->
            Log.i("GuidaAudioManager", "TtsManager:   Engine: ${engine.name}")
            Log.i("GuidaAudioManager", "TtsManager:     Label: ${engine.label}")
            Log.i("GuidaAudioManager", "TtsManager:     Icon: ${engine.icon}")
        }
    }
    
    private fun testBasicTtsFunctionality() {
        try {
            Log.i("GuidaAudioManager", "TtsManager: Testing basic TTS functionality...")
            
            // Check if TTS is available
            val available = tts?.isSpeaking
            Log.i("GuidaAudioManager", "TtsManager: TTS isSpeaking (should be false): $available")
            
            // Check voice info (API 21+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val voice = tts?.voice
                Log.i("GuidaAudioManager", "TtsManager: Current voice: ${voice?.name}")
                Log.i("GuidaAudioManager", "TtsManager: Voice locale: ${voice?.locale}")
                Log.i("GuidaAudioManager", "TtsManager: Voice quality: ${voice?.quality}")
                
                val voices = tts?.voices
                Log.i("GuidaAudioManager", "TtsManager: Available voices: ${voices?.size ?: 0}")
            }
            
        } catch (e: Exception) {
            Log.e("GuidaAudioManager", "TtsManager: Error testing TTS functionality: ${e.message}")
        }
    }
    
    private fun logTroubleshootingInfo() {
        Log.e("GuidaAudioManager", "TtsManager: TROUBLESHOOTING INFO:")
        Log.e("GuidaAudioManager", "TtsManager: 1. Check if Google TTS is installed and enabled")
        Log.e("GuidaAudioManager", "TtsManager: 2. Go to Settings > System > Languages & input > Text-to-speech")
        Log.e("GuidaAudioManager", "TtsManager: 3. Set 'Speech Services by Google' as preferred engine")
        Log.e("GuidaAudioManager", "TtsManager: 4. Download language data if needed")
        Log.e("GuidaAudioManager", "TtsManager: 5. Restart the app after configuring TTS")
    }
    
    fun speak(text: String, onComplete: (() -> Unit)? = null): Boolean {
        Log.i("GuidaAudioManager", "TtsManager: speak() called with text: '$text'")
        Log.i("GuidaAudioManager", "TtsManager: TTS ready: $isReady, TTS object: ${tts != null}")
        Log.i("GuidaAudioManager", "TtsManager: Current thread: ${Thread.currentThread().name}")
        
        if (!isReady || tts == null) {
            Log.w("GuidaAudioManager", "TtsManager: TTS not ready, cannot speak")
            Log.w("GuidaAudioManager", "TtsManager: isReady=$isReady, tts=${tts != null}")
            return false
        }
        
        val utteranceId = "tts_${System.currentTimeMillis()}"
        Log.i("GuidaAudioManager", "TtsManager: Using utterance ID: $utteranceId")
        
        if (onComplete != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i("GuidaAudioManager", "TtsManager: ✓ Started speaking: $utteranceId")
                }
                
                override fun onDone(utteranceId: String?) {
                    Log.i("GuidaAudioManager", "TtsManager: ✓ Completed speaking: $utteranceId")
                    onComplete()
                }
                
                override fun onError(utteranceId: String?) {
                    Log.e("GuidaAudioManager", "TtsManager: ✗ Error speaking: $utteranceId")
                    onComplete()
                }
                
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    Log.w("GuidaAudioManager", "TtsManager: ⏹ Stopped speaking: $utteranceId (interrupted: $interrupted)")
                    onComplete()
                }
            })
        }
        
        try {
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            Log.i("GuidaAudioManager", "TtsManager: speak() returned: $result")
            
            when (result) {
                TextToSpeech.SUCCESS -> {
                    Log.i("GuidaAudioManager", "TtsManager: speak() call successful")
                    return true
                }
                TextToSpeech.ERROR -> {
                    Log.e("GuidaAudioManager", "TtsManager: speak() failed with ERROR")
                    return false
                }
                else -> {
                    Log.e("GuidaAudioManager", "TtsManager: speak() returned unknown result: $result")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e("GuidaAudioManager", "TtsManager: Exception during speak(): ${e.message}", e)
            return false
        }
    }
    
    fun isReady(): Boolean = isReady
    
    fun shutdown() {
        Log.i("GuidaAudioManager", "TtsManager: Shutting down TTS")
        tts?.shutdown()
        tts = null
        isReady = false
        hasRetried = false
    }
}

class AudioManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var currentAudioFile: File? = null
    
    // HTTP client for OpenAI TTS API
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    // OpenAI TTS configuration
    //private val openAiApiKey = "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"
    //private val ttsModel = "gpt-4o-mini-tts"
    //private val ttsVoice = "coral" // Cheerful and positive voice
    
    // PCM Audio configuration for streaming
    private val sampleRate = 24000 // OpenAI TTS PCM sample rate
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    
    init {
        initializeToneGenerator()
        // Initialize TTS singleton once
        TtsManager.init(context)
        Log.i("GuidaAudioManager", "AudioManager initialized with OpenAI Streaming TTS and singleton Android TTS")
    }
    
    private fun initializeToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AndroidAudioManager.STREAM_NOTIFICATION, 80)
            Log.i("GuidaAudioManager", "ToneGenerator initialized successfully")
        } catch (e: RuntimeException) {
            Log.e("GuidaAudioManager", "Failed to create ToneGenerator: ${e.message}")
        }
    }
    

    
    fun startRecording(): File? {
        if (isRecording) return null
        
        try {
            val audioFile = File(context.cacheDir, "recorded_audio_${System.currentTimeMillis()}.3gp")
            currentAudioFile = audioFile
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile.absolutePath)
                
                prepare()
                start()
            }
            
            isRecording = true
            return audioFile
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
    }
    
    fun stopRecording(): File? {
        if (!isRecording || mediaRecorder == null) return null
        
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            return currentAudioFile
        } catch (e: RuntimeException) {
            e.printStackTrace()
            return null
        }
    }
    
    fun playAudioFromBytes(audioData: ByteArray, onCompletion: (() -> Unit)? = null) {
        try {
            val tempFile = File(context.cacheDir, "temp_response_${System.currentTimeMillis()}.mp3")
            tempFile.writeBytes(audioData)
            
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    onCompletion?.invoke()
                    release()
                }
                setOnErrorListener { _, _, _ ->
                    onCompletion?.invoke()
                    true
                }
                prepareAsync()
                setOnPreparedListener { start() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onCompletion?.invoke()
        }
    }
    
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        Log.i("GuidaAudioManager", "speak() called with text: \"${text.take(100)}...\"")
        Log.i("GuidaAudioManager", "Using OpenAI Streaming TTS for main speak() method")
        
        // Launch coroutine to handle async streaming TTS
        CoroutineScope(Dispatchers.IO).launch {
            try {
                streamSpeechFromOpenAI(text, onComplete)
            } catch (e: Exception) {
                Log.e("GuidaAudioManager", "Exception during OpenAI Streaming TTS: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Log.i("GuidaAudioManager", "Falling back to audio feedback tones")
                    fallbackAudioFeedback(text)
                    onComplete?.invoke()
                }
            }
        }
    }
    
    private suspend fun streamSpeechFromOpenAI(text: String, onComplete: (() -> Unit)?) {
        withContext(Dispatchers.IO) {
            try {
                Log.i("GuidaAudioManager", "Starting OpenAI Streaming TTS API call...")
                
                // Create request body for streaming PCM
                val requestBody = JSONObject().apply {
                    put("model", ttsModel)
                    put("voice", ttsVoice)
                    put("input", text)
                    put("response_format", "pcm") // Use PCM for fastest streaming
                    put("instructions", "Speak in a clear, cheerful, and helpful tone suitable for a blind user.")
                }.toString()
                
                val request = Request.Builder()
                    .url("https://api.openai.com/v1/audio/speech")
                    .addHeader("Authorization", "Bearer $openAiApiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = httpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body
                    if (responseBody != null) {
                        Log.i("GuidaAudioManager", "OpenAI TTS streaming response received, starting playback...")
                        
                        // Initialize AudioTrack for PCM streaming playback
                        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                        
                        withContext(Dispatchers.Main) {
                            audioTrack?.release()
                            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                AudioTrack.Builder()
                                    .setAudioAttributes(
                                        AudioAttributes.Builder()
                                            .setUsage(AudioAttributes.USAGE_MEDIA)
                                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                            .build()
                                    )
                                    .setAudioFormat(
                                        AudioFormat.Builder()
                                            .setEncoding(audioFormat)
                                            .setSampleRate(sampleRate)
                                            .setChannelMask(channelConfig)
                                            .build()
                                    )
                                    .setBufferSizeInBytes(bufferSize)
                                    .build()
                            } else {
                                @Suppress("DEPRECATION")
                                AudioTrack(
                                    AndroidAudioManager.STREAM_MUSIC,
                                    sampleRate,
                                    channelConfig,
                                    audioFormat,
                                    bufferSize,
                                    AudioTrack.MODE_STREAM
                                )
                            }
                            
                            audioTrack?.play()
                            Log.i("GuidaAudioManager", "AudioTrack started, streaming PCM data...")
                        }
                        
                        // Stream and play PCM data in chunks
                        val inputStream = responseBody.byteStream()
                        val buffer = ByteArray(4096) // 4KB chunks for low latency
                        var bytesRead: Int
                        var totalBytesPlayed = 0
                        
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (bytesRead > 0) {
                                withContext(Dispatchers.Main) {
                                    val bytesWritten = audioTrack?.write(buffer, 0, bytesRead) ?: 0
                                    totalBytesPlayed += bytesWritten
                                    
                                    if (totalBytesPlayed == bytesWritten) {
                                        // First chunk played - audio started
                                        Log.i("GuidaAudioManager", "First audio chunk played - streaming started")
                                    }
                                }
                            }
                        }
                        
                        inputStream.close()
                        
                        // Wait for playback to complete
                        withContext(Dispatchers.Main) {
                            // Set completion listener
                            audioTrack?.setNotificationMarkerPosition(totalBytesPlayed / 2) // 16-bit = 2 bytes per sample
                            audioTrack?.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                                override fun onMarkerReached(track: AudioTrack?) {
                                    Log.i("GuidaAudioManager", "Streaming TTS playback completed")
                                    track?.release()
                                    onComplete?.invoke()
                                }
                                
                                override fun onPeriodicNotification(track: AudioTrack?) {
                                    // Not used
                                }
                            })
                        }
                        
                        Log.i("GuidaAudioManager", "OpenAI TTS streaming completed successfully")
                    } else {
                        Log.e("GuidaAudioManager", "OpenAI TTS streaming response body is null")
                        withContext(Dispatchers.Main) {
                            fallbackAudioFeedback(text)
                            onComplete?.invoke()
                        }
                    }
                } else {
                    val errorBody = response.body?.string()
                    Log.e("GuidaAudioManager", "OpenAI TTS streaming API error: ${response.code} - $errorBody")
                    withContext(Dispatchers.Main) {
                        fallbackAudioFeedback(text)
                        onComplete?.invoke()
                    }
                }
                
                response.close()
            } catch (e: Exception) {
                Log.e("GuidaAudioManager", "Exception during OpenAI TTS streaming: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    fallbackAudioFeedback(text)
                    onComplete?.invoke()
                }
            }
        }
    }
    
    private fun fallbackAudioFeedback(text: String) {
        Log.i("GuidaAudioManager", "Using fallback audio feedback for: ${text.take(50)}...")
        
        // Play different tone patterns based on the type of message
        when {
            text.contains("error", ignoreCase = true) || text.contains("failed", ignoreCase = true) -> {
                // Error sound: low tone
                playErrorTone()
            }
            text.contains("success", ignoreCase = true) || text.contains("connected", ignoreCase = true) -> {
                // Success sound: ascending tones
                playSuccessTone()
            }
            text.contains("capturing", ignoreCase = true) || text.contains("processing", ignoreCase = true) -> {
                // Processing sound: single beep
                playProcessingTone()
            }
            else -> {
                // General notification: double beep
                playNotificationTone()
            }
        }
    }
    
    private fun playErrorTone() {
        toneGenerator?.let { generator ->
            Thread {
                try {
                    generator.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300)
                    Thread.sleep(100)
                    generator.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    Log.e("GuidaAudioManager", "Error playing error tone: ${e.message}")
                }
            }.start()
        }
    }
    
    private fun playSuccessTone() {
        toneGenerator?.let { generator ->
            Thread {
                try {
                    generator.startTone(ToneGenerator.TONE_DTMF_1, 150)
                    Thread.sleep(50)
                    generator.startTone(ToneGenerator.TONE_DTMF_3, 150)
                    Thread.sleep(50)
                    generator.startTone(ToneGenerator.TONE_DTMF_5, 200)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    Log.e("GuidaAudioManager", "Error playing success tone: ${e.message}")
                }
            }.start()
        }
    }
    
    private fun playProcessingTone() {
        toneGenerator?.let { generator ->
            Thread {
                try {
                    generator.startTone(ToneGenerator.TONE_DTMF_2, 200)
                } catch (e: Exception) {
                    Log.e("GuidaAudioManager", "Error playing processing tone: ${e.message}")
                }
            }.start()
        }
    }
    
    private fun playNotificationTone() {
        toneGenerator?.let { generator ->
            Thread {
                try {
                    generator.startTone(ToneGenerator.TONE_DTMF_4, 150)
                    Thread.sleep(100)
                    generator.startTone(ToneGenerator.TONE_DTMF_4, 150)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    Log.e("GuidaAudioManager", "Error playing notification tone: ${e.message}")
                }
            }.start()
        }
    }
    
    fun setSpeechRate(rate: Float) {
        // No-op for OpenAI TTS as it's a text-to-speech API
        // For Android TTS, speech rate is handled by the TTS engine itself
        Log.i("GuidaAudioManager", "Speech rate setting not supported for current TTS implementation")
    }
    
    /**
     * Test Android TTS functionality - speaks the given text using offline Android TTS
     */
    fun testAndroidTts(text: String, onComplete: (() -> Unit)? = null) {
        Log.i("GuidaAudioManager", "Testing Android TTS with text: '$text'")
        Log.i("GuidaAudioManager", "TTS ready: ${TtsManager.isReady()}")
        
        if (!TtsManager.isReady()) {
            Log.e("GuidaAudioManager", "Android TTS not ready, cannot test")
            Log.e("GuidaAudioManager", "Possible solutions:")
            Log.e("GuidaAudioManager", "  1. Install Google TTS from Play Store")
            Log.e("GuidaAudioManager", "  2. Go to Settings > Language & Input > Text-to-speech output")
            Log.e("GuidaAudioManager", "  3. Select and configure a TTS engine")
            Log.e("GuidaAudioManager", "  4. Download TTS language data if needed")
            
            // Play a fallback tone to indicate the test was attempted
            playNotificationTone()
            onComplete?.invoke()
            return
        }
        
        val success = TtsManager.speak(text, onComplete)
        if (!success) {
            Log.e("GuidaAudioManager", "TTS speak() failed")
            playErrorTone()
            onComplete?.invoke()
        }
    }
    
    fun playStartListeningSound() {
        // Play a rising tone sequence (like Apple's start sound)
        toneGenerator?.let { generator ->
            Thread {
                try {
                    generator.startTone(ToneGenerator.TONE_DTMF_1, 100) // Higher pitch
                    Thread.sleep(120)
                    generator.startTone(ToneGenerator.TONE_DTMF_3, 100) // Even higher
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.start()
        }
    }
    
    fun playStopListeningSound() {
        // Play a descending tone sequence (like Apple's stop sound)
        toneGenerator?.let { generator ->
            Thread {
                try {
                    generator.startTone(ToneGenerator.TONE_DTMF_3, 100) // Higher pitch
                    Thread.sleep(120)
                    generator.startTone(ToneGenerator.TONE_DTMF_1, 150) // Lower pitch, longer
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.start()
        }
    }
    
    fun isRecording(): Boolean = isRecording
    
    fun release() {
        Log.i("GuidaAudioManager", "Releasing AudioManager resources...")
        mediaRecorder?.release()
        mediaPlayer?.release()
        audioTrack?.release()
        toneGenerator?.release()
        // Note: TtsManager is a singleton, don't shutdown here unless this is the last AudioManager instance
        Log.i("GuidaAudioManager", "AudioManager resources released (TTS singleton left running)")
    }

    fun speakOffline(text: String, onComplete: (() -> Unit)? = null) {
        Log.i("GuidaAudioManager", "Speaking offline with Android TTS: \"$text\"")
        val success = TtsManager.speak(text, onComplete)
        if (!success) {
            Log.e("GuidaAudioManager", "Offline speech failed to start.")
            playErrorTone()
            onComplete?.invoke()
        }
    }
} 