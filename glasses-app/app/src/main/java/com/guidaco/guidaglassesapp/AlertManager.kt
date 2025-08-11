package com.guidaco.guidaglassesapp

import android.util.Log
import kotlin.math.abs

data class RadarTarget(
    val distance: Float,        // in cm
    val speed: Float,          // in m/s
    val angle: Float,          // in degrees (azimuth)
    val rawDistance: Int,
    val rawSpeed: Int,
    val rawAngle: Int
)

class AlertManager {
    private val tag = "AlertManager"
    private val targetTracker = TargetTracker()
    private val radarDataManager = RadarDataManager.getInstance()
    
    companion object {
        // Safety thresholds (speed and distance-based logic)
        private const val REACTION_TIME = 1.0f // seconds
        private const val SAFETY_MARGIN = 100.0f // 1.0m in cm
        private const val STATIC_SPEED_THRESHOLD = 0.3f // m/s
        
        // Direction thresholds (angles in degrees - horizontal only)
        private const val AZIMUTH_LEFT = 0f
        private const val AZIMUTH_RIGHT = 0f
        
        // Alert distance thresholds (for fallback distance-only alerts)
        private const val CLOSE_DISTANCE_THRESHOLD = 50.0f // 50cm - immediate alert
        private const val MEDIUM_DISTANCE_THRESHOLD = 100.0f // 1m - warning alert
        private const val FAR_DISTANCE_THRESHOLD = 500.0f // 5m - info alert
    }
    
    init {
        Log.e(tag, "AlertManager initialized - speed and distance-based alerts with target tracking (horizontal radar only)")
    }
    
    /**
     * Process radar targets with full speed and distance-based alerts
     * Uses TargetTracker to filter noise and maintain stable objects
     */
    fun processTargets(targets: List<RadarTarget>) {
        Log.e(tag, "AlertManager processing ${targets.size} raw targets")
        
        if (targets.isEmpty()) {
            Log.e(tag, "No raw targets to process")
            return
        }
        
        // Step 1: Filter targets through tracking system
        val stableTargets = targetTracker.processDetections(targets)
        
        Log.e(tag, "Filtered to ${stableTargets.size} stable targets")
        Log.e(tag, "Tracking stats: ${targetTracker.getTrackingStats()}")
        
        // Step 1.5: Update RadarDataManager with validated targets
        radarDataManager.updateValidatedTargets(stableTargets)
        
        // Step 2: Process only stable, tracked targets for alerts
        stableTargets.forEachIndexed { targetIndex, trackedTarget ->
            // Convert TrackedTarget back to RadarTarget for alert processing
            val radarTarget = RadarTarget(
                distance = trackedTarget.avgDistance,
                speed = trackedTarget.avgSpeed,
                angle = trackedTarget.avgAngle,
                rawDistance = (trackedTarget.avgDistance * 100).toInt(),
                rawSpeed = (trackedTarget.avgSpeed * 100).toInt(),
                rawAngle = (trackedTarget.avgAngle * 100).toInt()
            )
            
            Log.e(tag, "Processing stable track ${trackedTarget.id}: " +
                      "dist=${trackedTarget.avgDistance.toInt()}cm, " +
                      "speed=${"%.2f".format(trackedTarget.avgSpeed)}m/s, " +
                      "angle=${trackedTarget.avgAngle.toInt()}°, " +
                      "confidence=${"%.2f".format(trackedTarget.confidence)}, " +
                      "hits=${trackedTarget.hitCount}")
            
            processTarget(radarTarget, targetIndex, trackedTarget)
        }
        
        if (stableTargets.isEmpty()) {
            Log.e(tag, "No stable targets for alert processing")
        }
    }
    
    /**
     * Process individual target with full speed and distance-based logic
     * Uses tracked data to make more reliable speed-based decisions
     */
    private fun processTarget(target: RadarTarget, targetIndex: Int, trackedTarget: TrackedTarget? = null) {
        // Extract radar object data
        val distance = target.distance // in cm
        val speed = abs(target.speed) // Now using speed for alerts with tracking filtering
        val azimuth = target.angle // horizontal angle in degrees (-90 to +90)
        
        // Determine Direction (textual)
        val direction = when {
            azimuth < AZIMUTH_LEFT -> "Left"
            azimuth > AZIMUTH_RIGHT -> "Right"
            else -> "Center"
        }
        
        // Determine Audio Channel
        val audioChannel = when {
            azimuth < AZIMUTH_LEFT -> "left"
            azimuth > AZIMUTH_RIGHT -> "right"
            else -> "center"
        }
        
        // Full Alert Decision Logic - SPEED AND DISTANCE BASED
        if (speed < STATIC_SPEED_THRESHOLD) {
            // Static or very slow objects - use distance-based thresholds
            if (distance < CLOSE_DISTANCE_THRESHOLD) {
                // Very close static object
                triggerAlert("Close static obstacle", "high", audioChannel, distance, speed, targetIndex, azimuth, trackedTarget)
                activateCamera()
            } else if (distance < MEDIUM_DISTANCE_THRESHOLD) {
                // Medium distance static object
                triggerAlert("Nearby static obstacle", "medium", audioChannel, distance, speed, targetIndex, azimuth, trackedTarget)
                activateCamera()
            } else if (distance < FAR_DISTANCE_THRESHOLD) {
                // Far static object
                triggerAlert("Detected static obstacle", "low", audioChannel, distance, speed, targetIndex, azimuth, trackedTarget)
            }
        } else {
            // Moving objects - use speed-based reaction distance
            val reactionDist = (speed * REACTION_TIME * 100) + SAFETY_MARGIN // Convert to cm
            if (distance < reactionDist) {
                // Determine alert level based on speed and distance
                val alertLevel = when {
                    distance < CLOSE_DISTANCE_THRESHOLD || speed > 2.0f -> "high"
                    distance < MEDIUM_DISTANCE_THRESHOLD || speed > 1.0f -> "medium"
                    else -> "low"
                }
                triggerAlert("Approaching object", alertLevel, audioChannel, distance, speed, targetIndex, azimuth, trackedTarget)
                activateCamera()
            } else if (distance < FAR_DISTANCE_THRESHOLD && speed > 1.5f) {
                // Fast moving object even at distance
                triggerAlert("Fast approaching object", "medium", audioChannel, distance, speed, targetIndex, azimuth, trackedTarget)
            }
        }
    }
    
    /**
     * Trigger alert with detailed logging and full speed/distance analysis
     * Now includes speed in alert decisions thanks to TargetTracker filtering
     */
    private fun triggerAlert(message: String, level: String, channel: String, distance: Float, speed: Float, targetIndex: Int, azimuth: Float = 0f, trackedTarget: TrackedTarget? = null) {
        // Map distance to volume (0.5-5.0m -> 1.0-0.2)
        val distanceM = distance / 100.0f
        val volume = mapRange(distanceM, 0.5f, 5.0f, 1.0f, 0.2f)
        
        // Map speed to pitch (0-5 m/s -> 400-1200Hz)
        val pitch = mapRange(speed, 0.0f, 5.0f, 400.0f, 1200.0f)
        
        // Map distance and speed to beep interval (closer/faster -> more frequent)
        val baseInterval = mapRange(distanceM, 0.5f, 5.0f, 200.0f, 1200.0f)
        val speedFactor = mapRange(speed, 0.0f, 3.0f, 1.0f, 0.5f) // Faster = more frequent
        val interval = baseInterval * speedFactor
        
        // Log alert details
        Log.e(tag, "ALERT [${level.uppercase()}] Target #$targetIndex: $message")
        Log.e(tag, "  Distance: ${distance}cm (${String.format("%.2f", distanceM)}m)")
        Log.e(tag, "  Speed: ${speed}m/s (${String.format("%.1f", speed * 3.6f)}km/h)")
        
        // Determine direction from azimuth for logging
        val direction = when {
            azimuth < AZIMUTH_LEFT -> "Left"
            azimuth > AZIMUTH_RIGHT -> "Right"
            else -> "Center"
        }
        Log.e(tag, "  Direction: $direction ($channel channel)")
        
        // Calculate and log reaction metrics
        val reactionDist = (speed * REACTION_TIME * 100) + SAFETY_MARGIN
        val timeToImpact = if (speed > 0.1f) distance / (speed * 100) else Float.MAX_VALUE
        Log.e(tag, "  Reaction distance: ${reactionDist.toInt()}cm, Time to impact: ${"%.1f".format(timeToImpact)}s")
        
        Log.e(tag, "  Audio params: Volume=${String.format("%.2f", volume)}, Pitch=${pitch.toInt()}Hz, Interval=${interval.toInt()}ms")
        
        // Add tracking information if available
        trackedTarget?.let { track ->
            Log.e(tag, "  Tracking info: ID=${track.id}, hits=${track.hitCount}, confidence=${"%.2f".format(track.confidence)}")
            Log.e(tag, "    Raw vs Avg: dist=${track.distance.toInt()}vs${track.avgDistance.toInt()}cm, " +
                      "speed=${"%.2f".format(track.speed)}vs${"%.2f".format(track.avgSpeed)}m/s, " +
                      "angle=${track.angle.toInt()}vs${track.avgAngle.toInt()}°")
            Log.e(tag, "    Track age: ${(track.lastSeen - track.firstSeen)}ms, " +
                      "missed: ${track.missCount}")
        } ?: Log.e(tag, "  Tracking info: Not available (raw detection)")
        
        // Alert level details
        val beepRepeats = when (level) {
            "high" -> 3
            "medium" -> 2
            "low" -> 1
            else -> 1
        }
        Log.e(tag, "  Alert level: $level (beep repeat: $beepRepeats times)")
    }
    
    /**
     * Simulate camera activation for obstacle documentation
     */
    private fun activateCamera() {
        Log.e(tag, "CAMERA: Activated for obstacle documentation")
        // In real implementation: trigger camera capture, flash, or recording
    }
    
    /**
     * Map a value from one range to another
     */
    private fun mapRange(value: Float, fromMin: Float, fromMax: Float, toMin: Float, toMax: Float): Float {
        val clampedValue = value.coerceIn(fromMin, fromMax)
        return toMin + (clampedValue - fromMin) * (toMax - toMin) / (fromMax - fromMin)
    }
} 