package com.guidaco.guidaglassesapp

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

data class TrackedTarget(
    val id: Int,
    var distance: Float,          // Current distance in cm
    var angle: Float,            // Current angle in degrees
    var speed: Float,            // Current speed in m/s
    var firstSeen: Long,         // When first detected (timestamp)
    var lastSeen: Long,          // Last detection time
    var hitCount: Int = 1,       // Number of detections
    var missCount: Int = 0,      // Number of missed detections
    var confidence: Float = 0.5f, // Confidence score (0.0 - 1.0)
    var avgDistance: Float = distance,  // Running average distance
    var avgAngle: Float = angle,        // Running average angle
    var avgSpeed: Float = speed,        // Running average speed
    // Rolling average buffers for better stability
    val distanceBuffer: MutableList<Float> = mutableListOf(distance),
    val angleBuffer: MutableList<Float> = mutableListOf(angle),
    val speedBuffer: MutableList<Float> = mutableListOf(speed)
) {
    companion object {
        private const val ROLLING_WINDOW_SIZE = 5  // Number of readings to average
    }
    
    fun updateWithNewReading(newDistance: Float, newAngle: Float, newSpeed: Float) {
        // Update current values
        distance = newDistance
        angle = newAngle
        speed = newSpeed
        
        // Add to rolling buffers
        distanceBuffer.add(newDistance)
        angleBuffer.add(newAngle)
        speedBuffer.add(newSpeed)
        
        // Keep only the last ROLLING_WINDOW_SIZE readings
        if (distanceBuffer.size > ROLLING_WINDOW_SIZE) {
            distanceBuffer.removeAt(0)
        }
        if (angleBuffer.size > ROLLING_WINDOW_SIZE) {
            angleBuffer.removeAt(0)
        }
        if (speedBuffer.size > ROLLING_WINDOW_SIZE) {
            speedBuffer.removeAt(0)
        }
        
        // Calculate rolling averages
        avgDistance = distanceBuffer.average().toFloat()
        avgAngle = angleBuffer.average().toFloat()
        avgSpeed = speedBuffer.average().toFloat()
    }
    
    fun updateConfidence() {
        // Calculate stability metrics
        val distanceStability = if (distanceBuffer.size > 1) {
            val range = distanceBuffer.maxOrNull()!! - distanceBuffer.minOrNull()!!
            1.0f - (range / 100.0f).coerceIn(0.0f, 1.0f) // Penalize >1m variations
        } else 0.5f
        
        val angleStability = if (angleBuffer.size > 1) {
            val range = angleBuffer.maxOrNull()!! - angleBuffer.minOrNull()!!
            1.0f - (range / 60.0f).coerceIn(0.0f, 1.0f) // Penalize >60° variations
        } else 0.5f
        
        val speedStability = if (speedBuffer.size > 1) {
            val range = speedBuffer.maxOrNull()!! - speedBuffer.minOrNull()!!
            1.0f - (range / 8.0f).coerceIn(0.0f, 1.0f) // Penalize >8m/s variations
        } else 0.5f
        
        // Hit ratio component
        val hitRatio = hitCount.toFloat() / (hitCount + missCount)
        
        // Age bonus for stable long-term tracks
        val ageMs = lastSeen - firstSeen
        val ageBonus = if (ageMs > 1000) 0.1f else 0.0f
        
        // Weighted confidence calculation
        confidence = (distanceStability * 0.5f + 
                     angleStability * 0.3f + 
                     speedStability * 0.1f + 
                     hitRatio * 0.1f + 
                     ageBonus).coerceIn(0.0f, 1.0f)
    }
}

class TargetTracker {
    private val tag = "TargetTracker"
    private val trackedTargets = mutableMapOf<Int, TrackedTarget>()
    private var nextId = 1
    private val reusableIds = mutableSetOf<Int>() // IDs that can be reused
    
    companion object {
        // Distance validation - keep tight since position is accurate
        private const val MAX_DISTANCE_DIFF = 20.0f  // cm - position is reliable
        
        // Speed validation - keep loose since speed is unreliable
        private const val MAX_SPEED_DIFF = 8.0f  // m/s - reduced from 10.0 but still loose
        
        // Angle validation - moderate since it's only good for left/right indication
        private const val MAX_ANGLE_DIFF = 25.0f  // degrees - reduced from 30° to tighten
        
        // Track stability requirements - increased for better filtering
        private const val MIN_HIT_COUNT = 4  // hits - increased from 3 for stability
        private const val MAX_MISS_COUNT = 2  // misses - keep at 2 for quick removal
        private const val MIN_CONFIDENCE = 0.6f  // confidence - increased from 0.5
        private const val TRACK_TIMEOUT_MS = 1500L  // ms - keep at 1500ms
        
        // Rolling average window size
        private const val AVERAGE_WINDOW_SIZE = 5  // number of readings to average
    }
    
    /**
     * Process new radar detections and return filtered, stable targets
     */
    fun processDetections(rawTargets: List<RadarTarget>): List<TrackedTarget> {
        val currentTime = System.currentTimeMillis()
        
        Log.e(tag, "=== FRAME PROCESSING START ===")
        Log.e(tag, "Raw detections: ${rawTargets.size}")
        Log.e(tag, "Existing tracks: ${trackedTargets.size}")
        
        // Step 1: Match new detections to existing tracks
        val matchedTracks = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<Int>()
        
        for (detection in rawTargets.withIndex()) {
            val (detectionIndex, target) = detection
            var bestMatch: TrackedTarget? = null
            var bestScore = Float.MAX_VALUE
            
            for (track in trackedTargets.values) {
                val score = calculateMatchingScore(target, track)
                if (score < bestScore && isWithinGate(target, track)) {
                    bestScore = score
                    bestMatch = track
                }
            }
            
            if (bestMatch != null) {
                // Update existing track
                updateTrack(bestMatch, target, currentTime)
                matchedTracks.add(bestMatch.id)
                matchedDetections.add(detectionIndex)
                
                Log.d(tag, "Matched detection $detectionIndex to track ${bestMatch.id} (score: ${"%.2f".format(bestScore)})")
            }
        }
        
        // Step 2: Create new tracks for unmatched detections
        for (detection in rawTargets.withIndex()) {
            val (detectionIndex, target) = detection
            if (detectionIndex !in matchedDetections) {
                val newTrack = createNewTrack(target, currentTime)
                trackedTargets[newTrack.id] = newTrack
                
                Log.d(tag, "Created new track ${newTrack.id} for detection $detectionIndex")
            }
        }
        
        // Step 3: Update miss counts for unmatched tracks
        for (track in trackedTargets.values) {
            if (track.id !in matchedTracks) {
                track.missCount++
                Log.d(tag, "Track ${track.id} missed (miss count: ${track.missCount})")
            }
        }
        
        // Step 4: Remove expired or unreliable tracks
        val tracksToRemove = mutableListOf<Int>()
        for (track in trackedTargets.values) {
            val shouldRemove = track.missCount >= MAX_MISS_COUNT || 
                              (currentTime - track.lastSeen) > TRACK_TIMEOUT_MS
            
            if (shouldRemove) {
                tracksToRemove.add(track.id)
                reusableIds.add(track.id) // Mark ID for reuse
                Log.d(tag, "Removing track ${track.id} (miss: ${track.missCount}, age: ${currentTime - track.lastSeen}ms)")
            }
        }
        
        tracksToRemove.forEach { trackedTargets.remove(it) }
        
        // Step 5: Filter stable tracks for output
        val stableTracks = trackedTargets.values.filter { track ->
            track.hitCount >= MIN_HIT_COUNT && track.confidence >= MIN_CONFIDENCE
        }
        
        Log.e(tag, "Stable tracks for alerts: ${stableTracks.size}")
        for (track in stableTracks) {
            Log.e(tag, "Track ${track.id}: dist=${track.avgDistance.toInt()}cm, " +
                      "angle=${track.avgAngle.toInt()}deg, hits=${track.hitCount}, " +
                      "conf=${"%.2f".format(track.confidence)}")
        }
        Log.e(tag, "=== FRAME PROCESSING END ===")
        
        return stableTracks
    }
    
    private fun calculateMatchingScore(detection: RadarTarget, track: TrackedTarget): Float {
        val distanceDiff = abs(detection.distance - track.avgDistance)
        val angleDiff = abs(detection.angle - track.avgAngle)
        
        // Normalize differences
        val normalizedDistance = distanceDiff / MAX_DISTANCE_DIFF
        val normalizedAngle = angleDiff / MAX_ANGLE_DIFF
        
        // Weighted scoring: distance is more important than angle for consistency
        // Higher confidence tracks get slight preference for stable ID assignment
        val confidenceBonus = (1.0f - track.confidence) * 0.1f
        
        return (normalizedDistance * 0.7f) + (normalizedAngle * 0.3f) + confidenceBonus
    }
    
    private fun isWithinGate(detection: RadarTarget, track: TrackedTarget): Boolean {
        val distanceOk = abs(detection.distance - track.avgDistance) <= MAX_DISTANCE_DIFF
        val angleOk = abs(detection.angle - track.avgAngle) <= MAX_ANGLE_DIFF
        // Speed check disabled due to hardware sensor issues
        
        return distanceOk && angleOk
    }
    
    private fun updateTrack(track: TrackedTarget, detection: RadarTarget, currentTime: Long) {
        // Update raw values
        track.updateWithNewReading(detection.distance, detection.angle, detection.speed)
        track.lastSeen = currentTime
        track.hitCount++
        track.missCount = 0 // Reset miss count on successful detection
        
        // Update running averages
        track.updateConfidence()
        
        Log.v(tag, "Updated track ${track.id}: " +
                  "raw(${detection.distance.toInt()}cm, ${detection.angle.toInt()}°) -> " +
                  "avg(${track.avgDistance.toInt()}cm, ${track.avgAngle.toInt()}°)")
    }
    
    private fun createNewTrack(detection: RadarTarget, currentTime: Long): TrackedTarget {
        // Try to reuse an ID first, otherwise use next available ID
        val assignedId = if (reusableIds.isNotEmpty()) {
            val reusedId = reusableIds.first()
            reusableIds.remove(reusedId)
            reusedId
        } else {
            nextId++
        }
        
        val track = TrackedTarget(
            id = assignedId,
            distance = detection.distance,
            angle = detection.angle,
            speed = detection.speed,
            firstSeen = currentTime,
            lastSeen = currentTime
        )
        
        Log.v(tag, "Created track ${track.id}: ${detection.distance.toInt()}cm, ${detection.angle.toInt()}°")
        return track
    }
    
    /**
     * Get current track statistics for debugging
     */
    fun getTrackingStats(): String {
        val totalTracks = trackedTargets.size
        val stableTracks = trackedTargets.values.count { 
            it.hitCount >= MIN_HIT_COUNT && it.confidence >= MIN_CONFIDENCE 
        }
        val newTracks = trackedTargets.values.count { it.hitCount < MIN_HIT_COUNT }
        
        return "Tracks: $totalTracks total, $stableTracks stable, $newTracks new"
    }
} 