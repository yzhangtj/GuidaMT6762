package com.guidaco.guidaglassesapp

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

data class ValidatedTarget(
    val id: String,
    val distance: Float,
    val speed: Float,
    val angle: Float,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)

data class TargetLog(
    val target: ValidatedTarget,
    val logMessage: String,
    val timestamp: Long = System.currentTimeMillis()
)

class RadarDataManager private constructor() {
    companion object {
        @Volatile
        private var INSTANCE: RadarDataManager? = null
        
        fun getInstance(): RadarDataManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RadarDataManager().also { INSTANCE = it }
            }
        }
    }
    
    private val tag = "RadarDataManager"
    
    private val _validatedTargets = MutableStateFlow<List<ValidatedTarget>>(emptyList())
    val validatedTargets: StateFlow<List<ValidatedTarget>> = _validatedTargets.asStateFlow()
    
    private val _targetLogs = MutableStateFlow<List<TargetLog>>(emptyList())
    val targetLogs: StateFlow<List<TargetLog>> = _targetLogs.asStateFlow()
    
    private val logHistory = CopyOnWriteArrayList<TargetLog>()
    private val maxLogSize = 1000 // Keep last 1000 log entries
    
    fun updateValidatedTargets(targets: List<TrackedTarget>) {
        val validTargets = targets.filter { it.hitCount >= 4 && it.confidence >= 0.6 }
            .map { trackedTarget ->
                ValidatedTarget(
                    id = "T${trackedTarget.id}", // Consistent track ID with prefix
                    distance = trackedTarget.avgDistance,
                    speed = trackedTarget.avgSpeed,
                    angle = trackedTarget.avgAngle,
                    confidence = trackedTarget.confidence
                )
            }
        
        _validatedTargets.value = validTargets
        
        // Log validated targets
        validTargets.forEach { target ->
            val logMessage = "Validated Target ${target.id}: Distance=${String.format("%.1f", target.distance)}cm, " +
                    "Speed=${String.format("%.2f", target.speed)}m/s, " +
                    "Angle=${String.format("%.1f", target.angle)}°, " +
                    "Confidence=${String.format("%.2f", target.confidence)}"
            
            val targetLog = TargetLog(target, logMessage)
            logHistory.add(targetLog)
            
            // Keep log size manageable
            if (logHistory.size > maxLogSize) {
                logHistory.removeAt(0)
            }
        }
        
        _targetLogs.value = logHistory.toList()
        
        Log.d(tag, "Updated with ${validTargets.size} validated targets")
    }
    
    fun clearLogs() {
        logHistory.clear()
        _targetLogs.value = emptyList()
    }
} 