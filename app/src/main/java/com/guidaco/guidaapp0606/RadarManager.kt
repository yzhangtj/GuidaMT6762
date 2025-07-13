package com.guidaco.guidaapp0606

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

    companion object {
        // Protocol Definition
        private val HEADER = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte())
        private const val DEVICE_ADDRESS = 0x01.toByte()
        private const val COMMAND_ID_TARGET_INFO = 0x02.toByte()

        private const val MIN_FRAME_SIZE = 11 // 4 header + 1 addr + 1 cmd + 2 rsv + 2 len + 1 chk
        private const val TARGET_INFO_PACKET_LENGTH = 6 // 6 bytes per target
    }

    fun start() {
        if (isReading) {
            Log.d(tag, "RadarManager is already running.")
            return
        }

        val deviceFile = File(devicePath)
        if (!deviceFile.exists() || !deviceFile.canRead()) {
            Log.e(tag, "Cannot read from device: $devicePath. Check path and permissions.")
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
            } finally {
                isReading = false
                Log.d(tag, "Stopped reading from $devicePath")
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
            }
        }
    }

    fun stop() {
        isReading = false
    }
} 