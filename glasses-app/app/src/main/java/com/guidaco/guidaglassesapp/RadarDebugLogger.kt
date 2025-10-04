package com.guidaco.guidaglassesapp

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Lightweight reader that prints raw radar measurements to logcat once per second.
 * Use only for debugging; do not run alongside RadarManager to avoid double-reading the bus.
 */
class RadarDebugLogger(
    private val devicePath: String = "/dev/ttyS0",
    private val logTag: String = "RadarDebugLogger"
) {
    @Volatile
    private var isRunning = false
    private var readerThread: Thread? = null

    private val buffer = mutableListOf<Byte>()
    private var latestTargets: List<TargetInfo> = emptyList()
    private var lastLogTimestamp = 0L

    private data class TargetInfo(
        val distanceCm: Float,
        val speedMs: Float,
        val angleDeg: Float
    )

    companion object {
        private val HEADER = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte())
        private const val COMMAND_ID_TARGET_INFO = 0x02.toByte()
        private const val MIN_FRAME_SIZE = 11
        private const val TARGET_INFO_PACKET_LENGTH = 6
        private const val LOG_INTERVAL_MS = 1_000L
    }

    fun start() {
        if (isRunning) {
            Log.w(logTag, "RadarDebugLogger already running")
            return
        }

        // Try to detect which serial port has the radar
        val possiblePorts = listOf("/dev/ttyS0", "/dev/ttyS1")
        var actualDevicePath = devicePath
        
        for (port in possiblePorts) {
            val testFile = File(port)
            if (testFile.exists() && testFile.canRead()) {
                Log.i(logTag, "Found readable port: $port")
                actualDevicePath = port
                break
            }
        }

        val deviceFile = File(actualDevicePath)
        if (!deviceFile.exists() || !deviceFile.canRead()) {
            Log.e(logTag, "Cannot read from device $actualDevicePath. Check connection and permissions.")
            Log.e(logTag, "Tried ports: ${possiblePorts.joinToString()}")
            return
        }
        
        Log.i(logTag, "Using radar port: $actualDevicePath")
        
        // Configure serial port with correct baud rate (921600) asynchronously to avoid blocking if stty isn't present
        try {
            Thread {
                try {
                    // Try common variants; many Android builds don't support -F, so also try redirection form
                    val cmds = listOf(
                        "stty -F $actualDevicePath 921600 cs8 -cstopb -parenb raw",
                        "stty 921600 cs8 -cstopb -parenb raw < $actualDevicePath"
                    )
                    for (cmd in cmds) {
                        Log.i(logTag, "Configuring port (attempt): $cmd")
                        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                        val exit = process.waitFor()
                        if (exit == 0) {
                            Log.i(logTag, "Port configured successfully with: $cmd")
                            return@Thread
                        } else {
                            val err = process.errorStream.bufferedReader().readText()
                            Log.w(logTag, "stty attempt failed (exit=$exit): $err")
                        }
                    }
                    Log.w(logTag, "All stty attempts failed; proceeding without explicit port config")
                } catch (e: Exception) {
                    Log.e(logTag, "Port config thread error", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(logTag, "Failed to start port config thread", e)
        }

        isRunning = true
        readerThread = thread(start = true, isDaemon = true, name = "RadarDebugLoggerThread") {
            Log.i(logTag, "Reader thread started - attempting to open $actualDevicePath")
            try {
                FileInputStream(deviceFile).use { inputStream ->
                    Log.i(logTag, "FileInputStream opened successfully, starting read loop")
                    val readBuffer = ByteArray(256)
                    var readCount = 0
                    while (isRunning) {
                        val bytesRead = inputStream.read(readBuffer)
                        readCount++
                        
                        if (readCount % 10 == 1) { // Log every 10th read attempt
                            Log.i(logTag, "Read attempt #$readCount: got $bytesRead bytes")
                        }
                        
                        if (bytesRead > 0) {
                            Log.i(logTag, "Read $bytesRead bytes: ${readBuffer.take(bytesRead).joinToString(" ") { "%02X".format(it) }}")
                            buffer.addAll(readBuffer.take(bytesRead))
                            processBuffer()
                        } else {
                            Thread.sleep(50)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "Error reading radar data", e)
            } finally {
                isRunning = false
                buffer.clear()
                Log.i(logTag, "Stopped debug radar read")
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        readerThread?.join(500)
        readerThread = null
    }

    private fun processBuffer() {
        while (buffer.size >= MIN_FRAME_SIZE) {
            val headerIndex = findHeader()
            if (headerIndex == -1) {
                // Keep the tail so split headers can be matched on next read.
                if (buffer.size > HEADER.size) {
                    val keep = HEADER.size - 1
                    val removeCount = buffer.size - keep
                    repeat(removeCount) { buffer.removeAt(0) }
                }
                return
            }

            if (headerIndex > 0) {
                repeat(headerIndex) { buffer.removeAt(0) }
            }

            if (buffer.size < 10) return

            val dataLength = ByteBuffer.wrap(buffer.subList(8, 10).toByteArray())
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and 0xFFFF

            val totalFrameLength = 10 + dataLength + 1
            if (buffer.size < totalFrameLength) {
                return
            }

            val frameBytes = buffer.subList(0, totalFrameLength).toByteArray()
            repeat(totalFrameLength) { buffer.removeAt(0) }

            if (validateChecksum(frameBytes)) {
                parseFrame(frameBytes)
            } else {
                Log.w(logTag, "Checksum mismatch on radar frame; discarding")
                Log.d(logTag, "Raw frame data: ${frameBytes.joinToString(" ") { "%02X".format(it) }}")
                Log.d(logTag, "Frame length: ${frameBytes.size}, Expected checksum: ${frameBytes.last()}, Calculated: ${calculateChecksum(frameBytes)}")
            }
        }
    }

    private fun parseFrame(frameData: ByteArray) {
        if (frameData.size < MIN_FRAME_SIZE) return
        val commandId = frameData[5]
        if (commandId != COMMAND_ID_TARGET_INFO) {
            return
        }

        val payload = frameData.sliceArray(10 until frameData.size - 1)
        val targets = mutableListOf<TargetInfo>()
        val numTargets = payload.size / TARGET_INFO_PACKET_LENGTH

        for (i in 0 until numTargets) {
            val targetData = payload.sliceArray(
                i * TARGET_INFO_PACKET_LENGTH until (i + 1) * TARGET_INFO_PACKET_LENGTH
            )
            val byteBuffer = ByteBuffer.wrap(targetData).order(ByteOrder.LITTLE_ENDIAN)
            val rawDistance = byteBuffer.getShort(0).toInt() and 0xFFFF
            val rawSpeed = byteBuffer.getShort(2).toInt()
            val rawAngle = byteBuffer.getShort(4).toInt()

            targets.add(
                TargetInfo(
                    distanceCm = rawDistance / 100.0f,
                    speedMs = rawSpeed / 100.0f,
                    angleDeg = rawAngle / 100.0f
                )
            )
        }

        latestTargets = targets
        maybeLogTargets()
    }

    private fun maybeLogTargets() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogTimestamp < LOG_INTERVAL_MS) {
            return
        }

        lastLogTimestamp = now
        if (latestTargets.isEmpty()) {
            Log.i(logTag, "═══ No radar targets detected ═══")
        } else {
            Log.i(logTag, "═══════════════════════════════════════════════")
            Log.i(logTag, "   RADAR DEBUG - ${latestTargets.size} Target(s) Detected")
            Log.i(logTag, "═══════════════════════════════════════════════")
            latestTargets.forEachIndexed { index, target ->
                val speedCategory = when {
                    kotlin.math.abs(target.speedMs) < 0.3f -> "STATIC"
                    kotlin.math.abs(target.speedMs) < 1.0f -> "SLOW"
                    kotlin.math.abs(target.speedMs) < 2.0f -> "MEDIUM"
                    else -> "FAST"
                }
                
                val distanceCategory = when {
                    target.distanceCm < 50f -> "VERY CLOSE"
                    target.distanceCm < 100f -> "CLOSE"
                    target.distanceCm < 200f -> "NEAR"
                    target.distanceCm < 500f -> "FAR"
                    else -> "VERY FAR"
                }
                
                val angleDirection = when {
                    target.angleDeg < -5f -> "LEFT"
                    target.angleDeg > 5f -> "RIGHT"
                    else -> "CENTER"
                }
                
                Log.i(logTag, "")
                Log.i(logTag, "Target #$index:")
                Log.i(logTag, "  Distance: ${String.format("%.2f", target.distanceCm)}cm ($distanceCategory)")
                Log.i(logTag, "  Speed:    ${String.format("%.2f", target.speedMs)}m/s ($speedCategory)")
                Log.i(logTag, "  Angle:    ${String.format("%.2f", target.angleDeg)}° ($angleDirection)")
            }
            Log.i(logTag, "═══════════════════════════════════════════════")
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
        val sum = frame.slice(4 until frame.size - 1)
            .sumOf { it.toInt() and 0xFF }
        val calculated = (sum and 0xFF).toByte()
        return calculated == frame.last()
    }
    
    private fun calculateChecksum(frame: ByteArray): Byte {
        val sum = frame.slice(4 until frame.size - 1)
            .sumOf { it.toInt() and 0xFF }
        return (sum and 0xFF).toByte()
    }
}


