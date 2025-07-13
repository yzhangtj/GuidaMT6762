package com.guidaco.guidaglassesapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.util.Log
import kotlin.math.abs

class RadarViewModel : ViewModel() {
    
    private val radarDataManager = RadarDataManager.getInstance()
    
    private val _targets = MutableStateFlow<List<RadarTarget>>(emptyList())
    val targets: StateFlow<List<RadarTarget>> = _targets.asStateFlow()
    
    private val _stats = MutableStateFlow(RadarStats())
    val stats: StateFlow<RadarStats> = _stats.asStateFlow()
    
    init {
        startRadarDataCollection()
    }
    
    private fun startRadarDataCollection() {
        // Observe validated targets from RadarDataManager
        viewModelScope.launch {
            radarDataManager.validatedTargets.collect { validatedTargets ->
                // Convert ValidatedTargets to RadarTargets for display
                val radarTargets = validatedTargets.map { validatedTarget ->
                    RadarTarget(
                        distance = validatedTarget.distance,
                        speed = validatedTarget.speed,
                        angle = validatedTarget.angle,
                        rawDistance = (validatedTarget.distance * 100).toInt(),
                        rawSpeed = (validatedTarget.speed * 100).toInt(),
                        rawAngle = (validatedTarget.angle * 100).toInt()
                    )
                }
                
                _targets.value = radarTargets
                updateStats(radarTargets)
                
                Log.d("RadarViewModel", "Updated with ${radarTargets.size} validated targets")
            }
        }
    }
    
    private fun updateStats(targets: List<RadarTarget>) {
        val leftTargets = targets.count { it.angle < -5 }
        val rightTargets = targets.count { it.angle > 5 }
        val movingTargets = targets.count { abs(it.speed) > 0.5f }
        
        _stats.value = RadarStats(
            totalTargets = targets.size,
            leftTargets = leftTargets,
            rightTargets = rightTargets,
            movingTargets = movingTargets
        )
    }
    
    // Method to connect to real radar data
    fun connectToRadarManager(alertManager: AlertManager) {
        // This would be implemented to get real data from AlertManager
        // For now, we'll log that we're connected
        Log.d("RadarViewModel", "Connected to radar system")
    }
}

// Extension function to convert TrackedTarget to RadarTarget
fun TrackedTarget.toRadarTarget(): RadarTarget {
    return RadarTarget(
        distance = this.avgDistance,
        speed = this.avgSpeed,
        angle = this.avgAngle,
        rawDistance = (this.avgDistance * 100).toInt(),
        rawSpeed = (this.avgSpeed * 100).toInt(),
        rawAngle = (this.avgAngle * 100).toInt()
    )
} 