package com.guidaco.guidaglassesapp

import android.media.AudioManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class RadarManager {
    private var isReading = false
    private val tag = "RadarManager"
    private val devicePath = "/dev/ttyS0"
    private val buffer = mutableListOf<Byte>()
    private var alertManager: AlertManager? = null

    companion object {
        // Protocol Definition
        private val HEADER = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte())
        private const val DEVICE_ADDRESS = 0x01.toByte()
        private const val COMMAND_ID_TARGET_INFO = 0x02.toByte()

        private const val MIN_FRAME_SIZE = 11 // 4 header + 1 addr + 1 cmd + 2 rsv + 2 len + 1 chk
        private const val TARGET_INFO_PACKET_LENGTH = 6 // 6 bytes per target
    }

    fun setAlertManager(alertManager: AlertManager) {
        this.alertManager = alertManager
        Log.d(tag, "AlertManager connected to RadarManager")
    }

    fun start() {
        if (isReading) {
            Log.d(tag, "RadarManager is already running.")
            return
        }

        val deviceFile = File(devicePath)
        if (!deviceFile.exists() || !deviceFile.canRead()) {
            Log.e(tag, "Cannot read from device: $devicePath. Check path and permissions.")
            // Generate sample data for testing when device is not available
            generateSampleData()
            return
        }

        isReading = true
        thread(start = true, isDaemon = true, name = "RadarReaderThread") {
            Log.d(tag, "Starting to read from $devicePath")
            try {
                val inputStream = FileInputStream(deviceFile)
                val readBuffer = ByteArray(256)
                while (isReading) {
                    val bytesRead = inputStream.read(readBuffer)
                    if (bytesRead > 0) {
                        buffer.addAll(readBuffer.take(bytesRead))
                        processBuffer()
                    } else {
                        Thread.sleep(50) // Wait a bit if no data
                    }
                }
                inputStream.close()
            } catch (e: Exception) {
                Log.e(tag, "Error reading from serial port", e)
                // Generate sample data for testing when device reading fails
                generateSampleData()
            } finally {
                isReading = false
                Log.d(tag, "Stopped reading from $devicePath")
            }
        }
    }

    private fun generateSampleData() {
        Log.d(tag, "Generating sample radar data for testing")
        isReading = true
        thread(start = true, isDaemon = true, name = "SampleDataThread") {
            var frameCount = 0
            while (isReading) {
                try {
                    val sampleTargets = mutableListOf<RadarTarget>()
                    
                    // Generate 1-3 sample targets with some variation
                    val numTargets = (1..3).random()
                    for (i in 0 until numTargets) {
                        val baseDistance = 150f + (i * 50f) // 150cm, 200cm, 250cm
                        val distance = baseDistance + (Math.random() * 20f - 10f).toFloat() // Add some noise
                        val angle = (Math.random() * 60f - 30f).toFloat() // -30 to +30 degrees
                        val speed = (Math.random() * 2f - 1f).toFloat() // -1 to +1 m/s
                        
                        val target = RadarTarget(
                            distance = distance,
                            speed = speed,
                            angle = angle,
                            rawDistance = (distance * 100).toInt(),
                            rawSpeed = (speed * 100).toInt(),
                            rawAngle = (angle * 100).toInt()
                        )
                        sampleTargets.add(target)
                    }
                    
                    // Send sample targets to AlertManager
                    alertManager?.processTargets(sampleTargets)
                    
                    frameCount++
                    if (frameCount % 10 == 0) {
                        Log.d(tag, "Generated sample frame $frameCount with ${sampleTargets.size} targets")
                    }
                    
                    Thread.sleep(200) // 5 Hz update rate
                } catch (e: Exception) {
                    Log.e(tag, "Error generating sample data", e)
                    break
                }
            }
        }
    }

    private fun processBuffer() {
        while (buffer.size >= MIN_FRAME_SIZE) {
            val headerIndex = findHeader()
            if (headerIndex == -1) {
                // No header found, clear buffer to avoid infinite loop on bad data
                if (buffer.size > HEADER.size) buffer.clear()
                return
            }

            // Discard any data before the header
            if (headerIndex > 0) {
                for (i in 0 until headerIndex) buffer.removeAt(0)
            }

            // Check if we have enough data for length field
            if (buffer.size < 10) return

            // At this point, buffer starts with the header
            val dataLength = ByteBuffer.wrap(buffer.subList(8, 10).toByteArray())
                .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

            val totalFrameLength = 10 + dataLength + 1 // 10 bytes prefix + data + 1 chk

            if (buffer.size >= totalFrameLength) {
                val frameBytes = buffer.subList(0, totalFrameLength).toByteArray()
                
                // Validate checksum
                if (validateChecksum(frameBytes)) {
                    parseFrame(frameBytes)
                } else {
                    Log.w(tag, "Checksum mismatch! Discarding frame. Data: ${frameBytes.joinToString { String.format("%02X", it) }}")
                }
                
                // Remove processed frame from buffer
                for (i in 0 until totalFrameLength) buffer.removeAt(0)

            } else {
                // Not enough data for a full frame yet, wait for more
                return
            }
        }
    }

    private fun findHeader(): Int {
        for (i in 0..(buffer.size - HEADER.size)) {
            if (buffer.subList(i, i + HEADER.size).toByteArray().contentEquals(HEADER)) {
                return i
            }
        }
        return -1
    }
    
    private fun validateChecksum(frame: ByteArray): Boolean {
        val calculatedSum = frame.slice(4 until frame.size - 1) // From Device Address to end of Data
                                .sumOf { it.toInt() and 0xFF }
        val calculatedChecksum = (calculatedSum and 0xFF).toByte()
        val receivedChecksum = frame.last()
        return calculatedChecksum == receivedChecksum
    }

    private fun parseFrame(frameData: ByteArray) {
        val commandId = frameData[5]
        if (commandId != COMMAND_ID_TARGET_INFO) {
            Log.d(tag, "Received frame with non-target command ID: $commandId")
            return
        }

        val payload = frameData.sliceArray(10 until frameData.size - 1) // Data area
        val numTargets = payload.size / TARGET_INFO_PACKET_LENGTH
        
        if (numTargets > 0) {
            val targets = mutableListOf<RadarTarget>()
            
            Log.i(tag, "🎯 Detected $numTargets targets:")
            for (i in 0 until numTargets) {
                val targetData = payload.sliceArray(i * TARGET_INFO_PACKET_LENGTH until (i + 1) * TARGET_INFO_PACKET_LENGTH)
                val byteBuffer = ByteBuffer.wrap(targetData).order(ByteOrder.LITTLE_ENDIAN)

                val rawDistance = byteBuffer.getShort(0).toInt() and 0xFFFF
                val distance = rawDistance / 100.0f

                val rawSpeed = byteBuffer.getShort(2)
                val speed = rawSpeed / 100.0f
                
                val rawAngle = byteBuffer.getShort(4)
                val angle = rawAngle / 100.0f

                Log.i(tag, "  -> Distance: %.2fcm (raw: %d), Speed: %.2fm/s (raw: %d), Angle: %.2f° (raw: %d)".format(distance, rawDistance, speed, rawSpeed.toInt(), angle, rawAngle.toInt()))
                
                // Create RadarTarget object
                val target = RadarTarget(
                    distance = distance,
                    speed = speed,
                    angle = angle,
                    rawDistance = rawDistance,
                    rawSpeed = rawSpeed.toInt(),
                    rawAngle = rawAngle.toInt()
                )
                targets.add(target)
            }
            
            // Send targets to AlertManager for processing
            alertManager?.processTargets(targets)
        }
    }

    fun stop() {
        isReading = false
    }
} 