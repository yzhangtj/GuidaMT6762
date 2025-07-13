# Radar Sensor Integration Guide

## Overview

This guide explains how to use the radar sensor with your MT6762-based smart glasses for blind users. The radar sensor provides distance, speed, and angle information for obstacle detection and navigation assistance.

## Hardware Setup

### MT6762 Board Connection
- **Default Baudrate**: 921600
- **Communication**: Serial or TCP/IP (depending on your board configuration)
- **Power**: 3.3V or 5V (check your specific radar module)
- **Data Lines**: TX/RX for serial communication

### Radar Sensor Specifications
- **Detection Range**: Typically 0.2m to 10m
- **Field of View**: ±45° (adjustable)
- **Update Rate**: 10-50Hz (depending on configuration)
- **Accuracy**: ±0.1m for distance, ±0.1m/s for speed

## Communication Protocol

### Frame Format
```
0xAA 0xAA 0xAA 0xAA 0x01 0x02 0x00 0x00 LSB MSB DATA Check
```

### Frame Structure
1. **Protocol Header**: 4 bytes (0xAA 0xAA 0xAA 0xAA)
2. **Device Address**: 1 byte (0x01)
3. **Command ID**: 1 byte (0x02)
4. **Reserved**: 2 bytes (0x00 0x00)
5. **Data Length**: 2 bytes (LSB first, then MSB)
6. **Data Section**: Variable length (6 bytes per radar point)
7. **Checksum**: 1 byte

### Radar Point Data (6 bytes per point)
- **Distance**: 2 bytes (uint16_t, LSB first, multiplier ×100)
- **Speed**: 2 bytes (int16_t, LSB first, multiplier ×100)
- **Angle**: 2 bytes (int16_t, LSB first, multiplier ×100)

### Example Frame
```
Header:     AA AA AA AA
Address:    01
Command:    02
Reserved:   00 00
Length:     12 00 (18 bytes = 3 points × 6 bytes)
Data:       96 00 32 00 00 00  (Point 1: 1.5m, 0.5m/s, 0°)
            D4 00 E8 FF 96 00  (Point 2: 2.12m, -0.24m/s, 1.5°)
            50 00 78 00 9C FF  (Point 3: 0.8m, 1.2m/s, -1.0°)
Checksum:   XX
```

## Software Implementation

### Key Classes

#### RadarManager
Main class for handling radar communication and data processing.

```kotlin
val radarManager = RadarManager()

// Connect to radar
radarManager.connectToRadar("192.168.1.100", 8080)
// or
radarManager.connectToRadarSerial("/dev/ttyUSB0")

// Get radar data
val points = radarManager.radarData.value
val closest = radarManager.getClosestObject()
val warning = radarManager.getObstacleWarning()
```

#### RadarPoint
Data class representing a single radar detection point.

```kotlin
data class RadarPoint(
    val distance: Float,  // in meters
    val speed: Float,     // in m/s
    val angle: Float      // in degrees
)
```

#### RadarFrame
Data class representing a complete radar frame.

```kotlin
data class RadarFrame(
    val deviceAddress: Byte,
    val commandId: Byte,
    val dataLength: Int,
    val points: List<RadarPoint>,
    val checksum: Byte,
    val isValid: Boolean
)
```

### Usage Examples

#### Basic Obstacle Detection
```kotlin
// Check for obstacles in front
if (radarManager.hasObstacleInFront(maxDistance = 2.0f, angleRange = 30.0f)) {
    val warning = radarManager.getObstacleWarning()
    if (warning != null) {
        audioManager.speak(warning)
    }
}
```

#### Get Objects in Specific Range
```kotlin
// Get objects within 1-3 meters
val nearbyObjects = radarManager.getObjectsInRange(1.0f, 3.0f)

// Get objects moving towards user (negative speed)
val approachingObjects = radarManager.getObjectsAtSpeed(-Float.MAX_VALUE, -0.1f)

// Get objects in front (within ±15°)
val frontObjects = radarManager.getObjectsInAngleRange(-15f, 15f)
```

#### Real-time Monitoring
```kotlin
// Observe radar data changes
viewModelScope.launch {
    radarManager.radarData.collect { points ->
        // Process new radar data
        updateObstacleMap(points)
        checkForCollisions(points)
    }
}
```

## Testing

### Radar Test Activity
Use the built-in test activity to verify radar functionality:

1. Launch the app
2. Tap the 📡 icon in the top bar
3. Use "Test Protocol" to verify parsing
4. Try connecting to your radar sensor

### Protocol Testing
The `RadarTestUtils` class provides testing utilities:

```kotlin
// Run all tests
RadarTestUtils.runAllTests()

// Create sample frame
val frame = RadarTestUtils.createSampleRadarFrame()

// Generate random test data
val randomPoints = RadarTestUtils.generateRandomRadarPoints(5)
```

## Integration with Main App

### Adding Radar to MainActivity
```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var radarManager: RadarManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        radarManager = RadarManager()
        
        // Start radar monitoring
        startRadarMonitoring()
    }
    
    private fun startRadarMonitoring() {
        lifecycleScope.launch {
            radarManager.radarData.collect { points ->
                // Update UI or trigger alerts
                checkForObstacles(points)
            }
        }
    }
}
```

### Audio Alerts for Blind Users
```kotlin
private fun checkForObstacles(points: List<RadarPoint>) {
    val closest = points.minByOrNull { it.distance }
    
    when {
        closest?.distance ?: Float.MAX_VALUE < 0.5f -> {
            audioManager.speak("DANGER! Obstacle very close")
        }
        closest?.distance ?: Float.MAX_VALUE < 1.0f -> {
            audioManager.speak("Warning! Obstacle ahead")
        }
        closest?.distance ?: Float.MAX_VALUE < 2.0f -> {
            audioManager.speak("Obstacle detected")
        }
    }
}
```

## Troubleshooting

### Common Issues

1. **No Data Received**
   - Check physical connections
   - Verify baudrate settings
   - Ensure correct device address

2. **Invalid Checksums**
   - Check for data corruption
   - Verify frame format
   - Test with known good data

3. **Incorrect Distance/Speed Values**
   - Check multiplier settings (×100)
   - Verify byte order (LSB first)
   - Calibrate sensor if needed

### Debug Information
Enable debug logging to see detailed information:

```kotlin
// In RadarManager
Log.d(TAG, "Received radar frame: ${frame.points.size} points")
Log.d(TAG, "Frame hex: ${RadarTestUtils.bytesToHex(frameData)}")
```

## Configuration

### Adjustable Parameters
- **Detection Range**: Modify distance filtering
- **Angle Range**: Adjust field of view for obstacle detection
- **Speed Thresholds**: Set minimum speed for moving object detection
- **Update Rate**: Configure data refresh frequency

### Safety Settings
- **Minimum Distance**: 0.2m (sensor limitation)
- **Maximum Distance**: 10m (typical range)
- **Warning Distance**: 1.0m (user configurable)
- **Danger Distance**: 0.5m (user configurable)

## Future Enhancements

1. **Multi-sensor Fusion**: Combine radar with camera data
2. **Path Planning**: Use radar data for navigation
3. **Object Classification**: Identify different types of obstacles
4. **Predictive Alerts**: Anticipate moving obstacles
5. **Environmental Mapping**: Build spatial awareness

## Support

For issues or questions:
1. Check the test activity for protocol validation
2. Review logcat output for detailed error messages
3. Verify hardware connections and power supply
4. Test with known good radar data frames 