package com.guidaco.guidaapp0606

import android.content.Context
import android.os.Environment
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    fun initializeCamera(lifecycleOwner: LifecycleOwner, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // Set up image capture
                imageCapture = ImageCapture.Builder()
                    .build()
                
                // Select back camera as default
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()
                
                // Bind use cases to camera
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
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
    
    fun release() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
} 