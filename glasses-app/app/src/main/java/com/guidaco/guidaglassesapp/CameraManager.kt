package com.guidaco.guidaglassesapp

import android.content.Context
import android.os.Environment
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecordingVideo = false
    private var currentVideoFile: File? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    fun initializeCamera(lifecycleOwner: LifecycleOwner, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // Set up image capture
                imageCapture = ImageCapture.Builder()
                    .build()
                
                // Set up video capture
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.fromOrderedList(
                        listOf(Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    ))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)
                
                // Select back camera as default
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()
                
                // Bind use cases to camera
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture,
                    videoCapture
                )
                
                onSuccess()
            } catch (exc: Exception) {
                onError(exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    fun captureImage(onImageCaptured: (File) -> Unit, onError: (Exception) -> Unit) {
        val imageCapture = imageCapture ?: return
        
        // Create output file in Downloads folder for easy access
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        
        val photoFile = File(
            downloadsDir,
            "guida_captured_${System.currentTimeMillis()}.jpg"
        )
        
        // Create output options
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        // Set up image capture listener
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onImageCaptured(photoFile)
                }
                
                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }
    
    fun startVideoRecording(onVideoStarted: (File) -> Unit, onError: (Exception) -> Unit) {
        if (isRecordingVideo) {
            onError(Exception("Video recording already in progress"))
            return
        }
        
        val videoCapture = videoCapture ?: run {
            onError(Exception("Video capture not initialized"))
            return
        }
        
        try {
            // Create output file in Downloads folder
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            val videoFile = File(
                downloadsDir,
                "guida_video_${System.currentTimeMillis()}.mp4"
            )
            currentVideoFile = videoFile
            
            // Save to file directly
            val fileOutputOptions = FileOutputOptions.Builder(videoFile).build()
            
            recording = videoCapture.output
                .prepareRecording(context, fileOutputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecordingVideo = true
                            android.util.Log.i("CameraManager", "Video recording started: ${videoFile.absolutePath}")
                            onVideoStarted(videoFile)
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecordingVideo = false
                            if (event.hasError()) {
                                android.util.Log.e("CameraManager", "Video recording error: ${event.cause}")
                                onError(Exception("Video recording error: ${event.cause}"))
                            } else {
                                android.util.Log.i("CameraManager", "Video recording completed: ${videoFile.absolutePath}")
                            }
                            recording = null
                        }
                        else -> {}
                    }
                }
        } catch (e: Exception) {
            android.util.Log.e("CameraManager", "Failed to start video recording: ${e.message}", e)
            isRecordingVideo = false
            onError(e)
        }
    }
    
    fun stopVideoRecording(onVideoStopped: (File?) -> Unit) {
        if (!isRecordingVideo) {
            onVideoStopped(null)
            return
        }
        
        recording?.stop()
        recording = null
        isRecordingVideo = false
        
        val file = currentVideoFile
        currentVideoFile = null
        android.util.Log.i("CameraManager", "Video recording stopped")
        onVideoStopped(file)
    }
    
    fun isRecordingVideo(): Boolean = isRecordingVideo
    
    fun release() {
        recording?.stop()
        recording = null
        isRecordingVideo = false
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
} 