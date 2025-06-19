package com.guidaco.guidaapp0606

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.AudioManager as AndroidAudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.util.*

class AudioManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var textToSpeech: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null
    private var isRecording = false
    private var currentAudioFile: File? = null
    
    private var ttsInitialized = false
    
    init {
        initializeTextToSpeech()
        initializeToneGenerator()
    }
    
    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                ttsInitialized = true
            }
        }
    }
    
    private fun initializeToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AndroidAudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: RuntimeException) {
            // ToneGenerator creation can fail on some devices
            println("Failed to create ToneGenerator: ${e.message}")
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
        if (ttsInitialized && textToSpeech != null) {
            val utteranceId = "tts_${System.currentTimeMillis()}"
            
            if (onComplete != null) {
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    
                    override fun onDone(utteranceId: String?) {
                        onComplete()
                    }
                    
                    override fun onError(utteranceId: String?) {
                        onComplete()
                    }
                })
            }
            
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            onComplete?.invoke()
        }
    }
    
    fun setSpeechRate(rate: Float) {
        textToSpeech?.setSpeechRate(rate)
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
        mediaRecorder?.release()
        mediaPlayer?.release()
        textToSpeech?.shutdown()
        toneGenerator?.release()
    }
} 