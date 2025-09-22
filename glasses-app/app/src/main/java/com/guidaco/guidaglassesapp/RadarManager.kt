package com.guidaco.guidaglassesapp

import android.media.AudioManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RadarManager {
    private var isReading = false
    private val tag = "RadarManager"
    private val devicePath = "/dev/ttyS0"
    private val buffer = mutableListOf<Byte>()
    private var alertManager: AlertManager? = null

    // Executor to offload AlertManager/TargetTracker processing off the reader thread
    // Use ThreadPoolExecutor so we can inspect the queue for diagnostics
    private val processingExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>()
    )

    companion object {
        // Protocol Definition
        private val HEADER = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte())
        private const val DEVICE_ADDRESS = 0x01.toByte()
        private const val COMMAND_ID_TARGET_INFO = 0x02.toByte()

        private const val MIN_FRAME_SIZE = 11 // 4 header + 1 addr + 1 cmd + 2 rsv + 2 len + 1 chk
        private const val TARGET_INFO_PACKET_LENGTH = 6 // 6 bytes per target
        
    // Merge thresholds for duplicate detections (pulse radar often gives two entries)
    // Loosened to reduce false separate entries for close objects
    private const val MERGE_DISTANCE_THRESHOLD = 50.0f // cm (was 20)
    private const val MERGE_ANGLE_THRESHOLD = 25.0f // degrees (was 10)
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
                    Log.d(tag, "ReaderThread: waiting to read")
                    val bytesRead = inputStream.read(readBuffer)
                    Log.d(tag, "ReaderThread: read returned $bytesRead")
                    if (bytesRead > 0) {
                        buffer.addAll(readBuffer.take(bytesRead))
                        Log.d(tag, "ReaderThread: buffer size after read=${buffer.size}")
                        try {
                            processBuffer()
                        } catch (e: Exception) {
                            Log.e(tag, "Error in processBuffer", e)
                        }
                    } else {
                        Log.d(tag, "ReaderThread: no data, sleeping")
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
                    
                    // Send sample targets to AlertManager via executor so generation can't block
                    try {
                        processingExecutor.submit {
                            alertManager?.processTargets(sampleTargets)
                        }
                        Log.d(tag, "SampleData: submitted ${sampleTargets.size} targets to processingExecutor; queue=${processingExecutor.queue.size}")
                    } catch (e: Exception) {
                        Log.e(tag, "SampleData: failed to submit to executor", e)
                    }
                    
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
        Log.d(tag, "processBuffer: enter (bufferSize=${buffer.size})")
        while (buffer.size >= MIN_FRAME_SIZE) {
            val headerIndex = findHeader()
            if (headerIndex == -1) {
                // No header found: don't clear the entire buffer (that can lose
                // a partial header). Instead, remove bytes up to keeping the
                // last HEADER.size-1 bytes so a split header across reads is preserved.
                if (buffer.size > HEADER.size) {
                    val keep = HEADER.size - 1
                    val removeCount = buffer.size - keep
                    for (i in 0 until removeCount) buffer.removeAt(0)
                    Log.w(tag, "processBuffer: no header found, removed $removeCount bytes, kept $keep tail bytes (bufferSize=${buffer.size})")
                } else {
                    // Buffer is small and no full header yet; just wait for more data.
                    Log.w(tag, "processBuffer: no header found, buffer too small (${buffer.size}), waiting")
                }
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
                    Log.d(tag, "processBuffer: valid frame of length $totalFrameLength, parsing")
                    parseFrame(frameBytes)
                } else {
                    Log.w(tag, "Checksum mismatch! Discarding frame. Data: ${frameBytes.joinToString { String.format("%02X", it) }}")
                }
                
                // Remove processed frame from buffer
                for (i in 0 until totalFrameLength) buffer.removeAt(0)

            } else {
                // Not enough data for a full frame yet, wait for more
                Log.d(tag, "processBuffer: incomplete frame (have ${buffer.size}, need $totalFrameLength), returning")
                return
            }
        }
        Log.d(tag, "processBuffer: exit")
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
        Log.d(tag, "parseFrame: start (len=${frameData.size})")
        val commandId = frameData[5]
        if (commandId != COMMAND_ID_TARGET_INFO) {
            Log.d(tag, "Received frame with non-target command ID: $commandId")
            return
        }

        val payload = frameData.sliceArray(10 until frameData.size - 1) // Data area
        val numTargets = payload.size / TARGET_INFO_PACKET_LENGTH
        
        if (numTargets > 0) {
            val targets = mutableListOf<RadarTarget>()
            
            Log.i(tag, "Detected $numTargets targets:")
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
            
            // Merge nearby/duplicate detections (pulse radar can report the same object twice)
            val mergedTargets = mutableListOf<RadarTarget>()
            for (t in targets) {
                var merged = false
                for (mt in mergedTargets) {
                    val distDiff = kotlin.math.abs(t.distance - mt.distance)
                    val angleDiff = kotlin.math.abs(t.angle - mt.angle)
                    if (distDiff <= MERGE_DISTANCE_THRESHOLD && angleDiff <= MERGE_ANGLE_THRESHOLD) {
                        // Combine by averaging values (simple but effective)
                        val newDistance = (mt.distance + t.distance) / 2.0f
                        val newSpeed = (mt.speed + t.speed) / 2.0f
                        val newAngle = (mt.angle + t.angle) / 2.0f
                        val newRawDistance = (mt.rawDistance + t.rawDistance) / 2
                        val newRawSpeed = (mt.rawSpeed + t.rawSpeed) / 2
                        val newRawAngle = (mt.rawAngle + t.rawAngle) / 2

                        // Update mt in place
                        val index = mergedTargets.indexOf(mt)
                        mergedTargets[index] = RadarTarget(
                            distance = newDistance,
                            speed = newSpeed,
                            angle = newAngle,
                            rawDistance = newRawDistance,
                            rawSpeed = newRawSpeed,
                            rawAngle = newRawAngle
                        )
                        merged = true
                        break
                    }
                }
                if (!merged) mergedTargets.add(t)
            }

            if (mergedTargets.size != targets.size) {
                Log.d(tag, "parseFrame: merged ${targets.size} -> ${mergedTargets.size} targets")
                mergedTargets.forEachIndexed { idx, mt ->
                    Log.i(tag, "  -> Merged[$idx]: Distance=${"%.2f".format(mt.distance)}cm (raw:${mt.rawDistance}), Speed=${"%.2f".format(mt.speed)}m/s (raw:${mt.rawSpeed}), Angle=${"%.2f".format(mt.angle)}° (raw:${mt.rawAngle})")
                }
            }

            // Send merged targets to AlertManager for processing on the processingExecutor so parsing stays fast
            try {
                processingExecutor.submit { alertManager?.processTargets(mergedTargets) }
                Log.d(tag, "parseFrame: submitted ${mergedTargets.size} targets to processingExecutor; queue=${processingExecutor.queue.size}")
            } catch (e: Exception) {
                Log.e(tag, "parseFrame: failed to submit to executor", e)
            }
        }
        Log.d(tag, "parseFrame: end")
    }

    fun stop() {
        isReading = false
        try {
            processingExecutor.shutdownNow()
        } catch (ignored: Exception) {
        }
    }
}