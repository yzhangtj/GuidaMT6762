package com.guidaco.guidaglassesapp

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Simple activity that starts the RadarDebugLogger so raw readings appear in logcat.
 * Launch via adb for quick bench debugging without affecting production flows.
 */
class RadarDebugActivity : Activity() {

    private val logTag = "RadarDebugActivity"
    private val radarLogger = RadarDebugLogger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(logTag, "RadarDebugActivity created - starting RadarDebugLogger")
        radarLogger.start()
    }

    override fun onResume() {
        super.onResume()
        Log.i(logTag, "RadarDebugActivity resumed - ensuring logger is running")
        radarLogger.start()
    }

    override fun onPause() {
        super.onPause()
        Log.i(logTag, "RadarDebugActivity paused - stopping logger")
        radarLogger.stop()
    }

    override fun onDestroy() {
        Log.i(logTag, "RadarDebugActivity destroyed - stopping logger")
        radarLogger.stop()
        super.onDestroy()
    }
}

