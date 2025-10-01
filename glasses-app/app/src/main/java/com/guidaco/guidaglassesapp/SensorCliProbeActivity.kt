package com.guidaco.guidaglassesapp

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.guidaco.guidaglassesapp.detectors.FallCollisionDetector
import kotlin.math.*

class SensorCliProbeActivity : Activity(), SensorEventListener {
    
    companion object {
        private const val TAG = "SensorCliProbe"
    }
    
    private lateinit var sensorManager: SensorManager
    
    // Sensor instances
    private var accelerometer: Sensor? = null
    private var linearAccelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var gameRotationVector: Sensor? = null
    private var rotationVector: Sensor? = null
    
    // Rotation matrix and orientation values
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)
    
    // Fall and collision detector
    private val fallCollisionDetector = FallCollisionDetector(tag = TAG)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on and prevent activity from being paused
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        
        Log.i(TAG, "SensorCliProbeActivity started - MT6762 sensor probe")
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        
        // Initialize sensors
        Log.i(TAG, "Initializing sensors...")
        initializeSensors()
        
        // Register sensor listeners
        Log.i(TAG, "Registering sensor listeners...")
        registerSensorListeners()
        
        Log.i(TAG, "All available sensors registered. Data will be logged to Logcat with tag: $TAG")
        Log.i(TAG, "To view sensor data: adb logcat -s SensorCliProbe")
        Log.i(TAG, "🚨 Fall & Collision Detection ACTIVE - will log FALL/COLLISION events")
        
        // Test sensor data immediately
        Log.i(TAG, "Testing sensor data logging in 2 seconds...")
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "TEST: If you see this but no sensor data, sensors may not be generating events")
        }, 2000)
        
        // Log a test message every 5 seconds to verify activity is running
        val handler = Handler(Looper.getMainLooper())
        val testRunnable = object : Runnable {
            override fun run() {
                Log.i(TAG, "Activity is still running - waiting for sensor data...")
                handler.postDelayed(this, 5000)
            }
        }
        handler.postDelayed(testRunnable, 5000)
    }
    
    private fun initializeSensors() {
        // Accelerometer (TYPE_ACCELEROMETER)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            Log.i(TAG, "✓ Accelerometer found: ${accelerometer!!.name}")
        } else {
            Log.w(TAG, "✗ Accelerometer not available")
        }
        
        // Linear Accelerometer (TYPE_LINEAR_ACCELERATION)
        linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linearAccelerometer != null) {
            Log.i(TAG, "✓ Linear Accelerometer found: ${linearAccelerometer!!.name}")
        } else {
            Log.w(TAG, "✗ Linear Accelerometer not available - skipping")
        }
        
        // Gyroscope (TYPE_GYROSCOPE)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyroscope != null) {
            Log.i(TAG, "✓ Gyroscope found: ${gyroscope!!.name}")
        } else {
            Log.w(TAG, "✗ Gyroscope not available")
        }
        
        // Magnetometer (TYPE_MAGNETIC_FIELD)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magnetometer != null) {
            Log.i(TAG, "✓ Magnetometer found: ${magnetometer!!.name}")
        } else {
            Log.w(TAG, "✗ Magnetometer not available")
        }
        
        // Game Rotation Vector (TYPE_GAME_ROTATION_VECTOR)
        gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (gameRotationVector != null) {
            Log.i(TAG, "✓ Game Rotation Vector found: ${gameRotationVector!!.name}")
        } else {
            Log.w(TAG, "✗ Game Rotation Vector not available")
        }
        
        // Rotation Vector (TYPE_ROTATION_VECTOR)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVector != null) {
            Log.i(TAG, "✓ Rotation Vector found: ${rotationVector!!.name}")
        } else {
            Log.w(TAG, "✗ Rotation Vector not available")
        }
    }
    
    private fun registerSensorListeners() {
        Log.i(TAG, "Starting sensor listener registration...")
        
        // Register accelerometer with fallback sampling rates
        accelerometer?.let { sensor ->
            Log.i(TAG, "About to register accelerometer: ${sensor.name}")
            try {
                // Try FASTEST first
                var success = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                if (!success) {
                    Log.w(TAG, "FASTEST failed, trying GAME rate...")
                    success = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                }
                if (!success) {
                    Log.w(TAG, "GAME failed, trying UI rate...")
                    success = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
                }
                
                Log.i(TAG, "Accelerometer registration result: $success")
                if (success) {
                    Log.i(TAG, "✓ Accelerometer listener registered successfully")
                } else {
                    Log.e(TAG, "✗ Failed to register accelerometer listener with any rate")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception registering accelerometer: ${e.message}")
                // Try with slower rate as fallback
                try {
                    val fallbackSuccess = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                    Log.i(TAG, "Fallback registration (NORMAL rate): $fallbackSuccess")
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback registration also failed: ${e2.message}")
                }
            }
        } ?: Log.w(TAG, "Accelerometer is null, cannot register")
        
        // Register linear accelerometer (fastest rate for testing)
        linearAccelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Registered Linear Accelerometer listener")
        }
        
        // Register gyroscope (fastest rate for testing)
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Registered Gyroscope listener")
        }
        
        // Register magnetometer (fastest rate for testing)
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Registered Magnetometer listener")
        }
        
        // Register game rotation vector (fastest rate for testing)
        gameRotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Registered Game Rotation Vector listener")
        }
        
        // Register rotation vector (fastest rate for testing)
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "Registered Rotation Vector listener")
        }
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        // Process sensor data silently - only log FALL/COLLISION alerts
        val timestamp = event.timestamp // nanoseconds
        
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // TYPE_ACCELEROMETER (m/s², with gravity)
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                
                // Feed to fall/collision detector (with gravity for free-fall detection)
                fallCollisionDetector.onAccelInclGrav(timestamp, x, y, z)
            }
            
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // TYPE_LINEAR_ACCELERATION (m/s², gravity removed)
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                
                // Feed to fall/collision detector (linear acceleration)
                fallCollisionDetector.onLinearAcceleration(timestamp, x, y, z)
            }
            
            Sensor.TYPE_GYROSCOPE -> {
                // TYPE_GYROSCOPE (rad/s)
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                
                // Feed to fall/collision detector
                fallCollisionDetector.onGyroscope(timestamp, x, y, z)
            }
            
            Sensor.TYPE_MAGNETIC_FIELD -> {
                // TYPE_MAGNETIC_FIELD (μT) - not used by detector but available
                // No logging, data not needed for fall/collision detection
            }
            
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                // TYPE_GAME_ROTATION_VECTOR -> attitude angles
                if (event.values.size >= 4) {
                    // Feed rotation vector to detector
                    fallCollisionDetector.onRotationVector(timestamp, event.values)
                    
                    val attitudeAngles = calculateAttitudeAngles(event.values)
                    val azimuth = attitudeAngles[0]
                    val pitch = attitudeAngles[1]
                    val roll = attitudeAngles[2]
                    
                    // Feed orientation angles to detector
                    fallCollisionDetector.onOrientation(timestamp, azimuth, pitch, roll)
                }
            }
            
            Sensor.TYPE_ROTATION_VECTOR -> {
                // TYPE_ROTATION_VECTOR -> attitude angles (backup/secondary)
                if (event.values.size >= 4) {
                    fallCollisionDetector.onRotationVector(timestamp, event.values)
                    
                    val attitudeAngles = calculateAttitudeAngles(event.values)
                    val azimuth = attitudeAngles[0]
                    val pitch = attitudeAngles[1]
                    val roll = attitudeAngles[2]
                    
                    fallCollisionDetector.onOrientation(timestamp, azimuth, pitch, roll)
                }
            }
        }
    }
    
    private fun calculateAttitudeAngles(rotationVector: FloatArray): FloatArray {
        // Convert rotation vector to rotation matrix
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
        
        // Get orientation angles (azimuth, pitch, roll) in radians
        SensorManager.getOrientation(rotationMatrix, orientationValues)
        
        // Convert to degrees
        val azimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
        val pitch = Math.toDegrees(orientationValues[1].toDouble()).toFloat()
        val roll = Math.toDegrees(orientationValues[2].toDouble()).toFloat()
        
        return floatArrayOf(azimuth, pitch, roll)
    }
    
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        val accuracyString = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN"
        }
        Log.i(TAG, "Sensor accuracy changed: ${sensor.name} -> $accuracyString")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Unregister all sensor listeners
        sensorManager.unregisterListener(this)
        Log.i(TAG, "SensorCliProbeActivity destroyed - all sensors unregistered")
    }
    
    override fun onPause() {
        super.onPause()
        // Don't unregister sensors in pause - keep them running
        Log.i(TAG, "Activity paused - sensors continue running")
    }
    
    override fun onResume() {
        super.onResume()
        Log.i(TAG, "Activity resumed - sensors already running")
    }
}

