package com.guidaco.guidaglassesapp.detectors

import android.hardware.SensorManager
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fall & Collision detector tuned for head-worn glasses outdoors.
 *
 * Changes vs. previous version:
 *  - COLLISION: adds pre-motion gate (RMS), jerk+peak+duration "shape" gate, and a rotation-angle fallback.
 *  - FALL: prioritizes drop DISTANCE via free-fall duration; impact + posture/rest confirm; severity labels.
 *  - Wear-state heuristic & device-drop rejection when not worn.
 *  - Light EMA smoothing and jerk computed on |a_lin| magnitude (not raw xyz) to kill micro-taps.
 *
 * Feed the same events you already have:
 *   detector.onLinearAcceleration(t, ax, ay, az)       // TYPE_LINEAR_ACCELERATION
 *   detector.onGyroscope(t, gx, gy, gz)                // TYPE_GYROSCOPE
 *   detector.onAccelInclGrav(t, ax, ay, az)            // TYPE_ACCELEROMETER (incl. gravity) for free-fall
 *   detector.onRotationVector(t, rotVec)               // TYPE_(GAME_)ROTATION_VECTOR (optional; posture still uses angles)
 *   detector.onOrientation(t, azDeg, pitchDeg, rollDeg)// from rotation vector; used for posture delta
 *
 * Watch Logcat (INFO):
 *   adb logcat -v time SensorCliProbe:I *:S
 */
class FallCollisionDetector(
    private val tag: String = "SensorCliProbe",
    private val cfg: Config = Config(),
    private val onEvent: ((Event) -> Unit)? = null
) {
    // ----------------- CONFIG -----------------

    data class Config(
        // Ring buffer horizon (kept for feature windows)
        val bufferHorizonMs: Long = 5000,

        // ---------- COLLISION (shape-only, no pre-motion/wear gates) ----------
        // Jerk + peak + duration (shape)
        val jerkCollision: Float = 400f,         // m/s^3 (raised threshold)
        val minAPeakForJerk: Float = 14f,        // m/s^2 peak alongside jerk
        val minWPeakForJerk: Float = 9.5f,       // rad/s peak alongside jerk
        val eventDurationMinMs: Long = 90,       // >= duration with |a_lin| >= durationAThresh
        val durationAThresh: Float = 8f,         // m/s^2

        // Strong rotation fallback
        val rotAngleWindowMs: Long = 150,        // integrate |ω| over this window
        val rotAngleMinDeg: Float = 18f,         // deg

        // Backup impact path (without jerk) - requires BOTH peaks
        val aCollision: Float = 22f,             // m/s^2
        val wCollision: Float = 12f,             // rad/s (raised to require both)

        val collisionRefractoryMs: Long = 1000,

        // Wear-state detection (kept for fall/device-drop logic only)
        val wearWinMs: Long = 2000,              // for wear-state heuristic
        val wearARmsMin: Float = 0.3f,           // m/s^2
        val wearWRmsMin: Float = 0.6f,           // rad/s

        // ----------- FALL (distance-first via free-fall) ----------
        // Free-fall (using ACC including gravity)
        val freeFallG: Float = 0.40f,            // |ACC| < 0.40 g counts as near-0g
        val freeFallMinMsModerate: Long = 450,   // ~1.0–1.5 m drop band
        val freeFallMinMsSerious: Long = 550,    // ~1.5 m drop (serious)
        val freeFallLookbackMs: Long = 900,      // must precede impact within this time

        // Impact + confirmation (posture or rest)
        val impactWindowMs: Long = 150,          // to search peaks
        val postWindowMs: Long = 3000,           // time allowed after impact to confirm fall
        val aImpact: Float = 30f,                // m/s^2 (~3g)
        val wImpact: Float = 6.5f,               // rad/s
        val postureDeg: Float = 70f,             // deg (head-worn → raise a bit)
        val restWindowMs: Long = 2000,
        val restARms: Float = 1.2f,              // m/s^2
        val restWRms: Float = 1.0f,              // rad/s

        val fallRefractoryMs: Long = 5000
    )

    // ----------------- EVENTS -----------------

    sealed class Event {
        data class Collision(
            val tNanos: Long,
            val jerk: Float,
            val aPeak: Float,
            val wPeak: Float,
            val angleDeg150ms: Float,
            val durationMs: Long
        ) : Event()

        enum class FallSeverity { MODERATE, SERIOUS }

        data class Fall(
            val tNanos: Long,
            val severity: FallSeverity,
            val freeFallMs: Long,
            val aPeak: Float,
            val wPeak: Float,
            val dPitch: Float,
            val dRoll: Float,
            val aRms: Float,
            val wRms: Float,
            val wear: Boolean
        ) : Event()

        /** A long free-fall + impact while not worn (reject human fall). */
        data class DeviceDrop(
            val tNanos: Long,
            val freeFallMs: Long,
            val aPeak: Float
        ) : Event()
    }

    // ----------------- INTERNAL STATE -----------------

    private data class Sample(
        val t: Long,
        val aMag: Float,      // |a_lin| (m/s^2)
        val wMag: Float,      // |gyro|  (rad/s)
        val pitch: Float,     // deg
        val roll: Float       // deg
    )

    private val buf = ArrayDeque<Sample>()
    private var latestAMag = 0f
    private var latestWMag = 0f
    private var latestPitch = 0f
    private var latestRoll  = 0f
    private var latestAzDeg = 0f

    // Light EMA smoothing of linear accel xyz → use |a| for jerk
    private var emaLin = floatArrayOf(0f, 0f, 0f)
    private var emaInit = false
    private val emaAlpha = 0.3f

    // jerk on |a|
    private var prevAMag: Float? = null
    private var prevAMagT: Long = 0L

    // posture baseline (updated slowly when quiet)
    private var baselinePitch = 0f
    private var baselineRoll  = 0f

    // Cooldowns & timestamps
    private var collCooldownUntil: Long = 0L
    private var fallCooldownUntil: Long = 0L
    private var lastImpactT: Long = 0L

    // Free-fall tracking (accelerometer including gravity)
    private var ffActiveSince: Long? = null
    private var haveAccMagG = false

    // Wear-state (updated continuously)
    private var wearState: Boolean = true

    // Optional: last rotation matrix from rotation vector (if you want earth-frame feats later)
    private var lastR: FloatArray? = null

    // ----------------- PUBLIC FEEDS -----------------

    /** Linear acceleration (m/s^2), gravity removed. */
    fun onLinearAcceleration(tsNanos: Long, ax: Float, ay: Float, az: Float) {
        // EMA smoothing to kill micro-taps
        val v = if (!emaInit) {
            emaInit = true; emaLin[0] = ax; emaLin[1] = ay; emaLin[2] = az; emaLin
        } else {
            emaLin[0] = emaAlpha * ax + (1 - emaAlpha) * emaLin[0]
            emaLin[1] = emaAlpha * ay + (1 - emaAlpha) * emaLin[1]
            emaLin[2] = emaAlpha * az + (1 - emaAlpha) * emaLin[2]
            emaLin
        }

        val aMag = mag(v[0], v[1], v[2])
        val jerk = computeJerkFromAMag(aMag, tsNanos)

        latestAMag = aMag
        insert(tsNanos)
        // Collision path runs every lin-acc sample
        checkCollision(tsNanos, jerk)

        // Fall checks can run on any stream update
        wearState = isWorn(tsNanos)
        checkFall(tsNanos)
    }

    /** Gyroscope (rad/s). */
    fun onGyroscope(tsNanos: Long, gx: Float, gy: Float, gz: Float) {
        latestWMag = mag(gx, gy, gz)
        insert(tsNanos)
        checkFall(tsNanos)
    }

    /** ACC including gravity, used to detect free-fall by |ACC| < freeFallG * g. */
    fun onAccelInclGrav(tsNanos: Long, ax: Float, ay: Float, az: Float) {
        val gMag = mag(ax, ay, az) / 9.80665f
        haveAccMagG = true
        if (gMag < cfg.freeFallG) {
            if (ffActiveSince == null) ffActiveSince = tsNanos
        } else {
            ffActiveSince = null
        }
    }

    /** Rotation vector raw values; stores rotation matrix if needed later. */
    fun onRotationVector(tsNanos: Long, rotVec: FloatArray) {
        val R = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(R, rotVec)
        lastR = R
        // Orientation angles still come from onOrientation()
    }

    /** Orientation angles (deg) from rotation vector. */
    fun onOrientation(tsNanos: Long, azDeg: Float, pitchDeg: Float, rollDeg: Float) {
        latestAzDeg = azDeg
        latestPitch = pitchDeg
        latestRoll = rollDeg

        // Slowly update baseline when quiet & far from impacts
        val sinceImpactMs = (tsNanos - lastImpactT) / 1_000_000
        if (sinceImpactMs > 1500 && latestAMag < 2f && latestWMag < 1.5f) {
            baselinePitch = lerp(baselinePitch, latestPitch, 0.02f)
            baselineRoll  = lerp(baselineRoll,  latestRoll,  0.02f)
        }

        insert(tsNanos)
        wearState = isWorn(tsNanos)
        checkFall(tsNanos)
    }

    // ----------------- COLLISION LOGIC -----------------

    private fun checkCollision(ts: Long, jerk: Float) {
        if (ts < collCooldownUntil) return

        // Peaks & duration around the candidate
        val peakWin = 120_000_000L
        val aPeak = peakA(ts - peakWin, ts + peakWin)
        val wPeak = peakW(ts - peakWin, ts + peakWin)
        val dur   = durAboveA(ts, cfg.durationAThresh, 100_000_000L) // measure around ±100ms

        val rotDeg = integOmegaDeg(ts - cfg.rotAngleWindowMs * 1_000_000, ts)

        val jerkHit  = (jerk >= cfg.jerkCollision)
        val peakGate = (aPeak >= cfg.minAPeakForJerk) || (wPeak >= cfg.minWPeakForJerk)
        val durGate  = (dur >= cfg.eventDurationMinMs)

        val backupHit = (aPeak >= cfg.aCollision) && (wPeak >= cfg.wCollision) && durGate

        val pass = (jerkHit && peakGate && durGate) || (backupHit && rotDeg >= cfg.rotAngleMinDeg)

        if (pass) {
            Log.i(tag, "⚡ COLLISION: jerk=%.1f m/s^3, aPeak=%.1f m/s^2, wPeak=%.1f rad/s, rot=%.1f°, dur=%d ms (cooldown %d ms)".format(
                jerk, aPeak, wPeak, rotDeg, dur, cfg.collisionRefractoryMs
            ))
            onEvent?.invoke(
                Event.Collision(ts, jerk, aPeak, wPeak, rotDeg, dur)
            )
            collCooldownUntil = ts + cfg.collisionRefractoryMs * 1_000_000
        }
    }

    // ----------------- FALL LOGIC -----------------

    private fun checkFall(ts: Long) {
        if (ts < fallCooldownUntil) return

        // 1) Impact candidate near now?
        val winNanos = cfg.impactWindowMs * 1_000_000
        val impact = hasImpact(ts - winNanos, ts)
        if (!impact) return

        // 2) Free-fall just before the impact? (distance proxy)
        val ffMs = freeFallDurationEndingBefore(ts, cfg.freeFallLookbackMs)
        val severity = when {
            ffMs >= cfg.freeFallMinMsSerious -> Event.FallSeverity.SERIOUS
            ffMs >= cfg.freeFallMinMsModerate -> Event.FallSeverity.MODERATE
            else -> null
        }

        // 3) Posture or rest confirm within postWindow
        val base = baselineAt(ts - winNanos)
        val confirmed = postureOrRestConfirm(ts + cfg.postWindowMs * 1_000_000, base.first, base.second)
        if (!confirmed) return

        // Peaks & rest RMS for reporting
        val aPeak = peakA(ts - winNanos, ts + cfg.postWindowMs * 1_000_000)
        val wPeak = peakW(ts - winNanos, ts + cfg.postWindowMs * 1_000_000)
        val (aRms, wRms) = rmsIn(ts, ts + cfg.restWindowMs * 1_000_000)
        val dPitch = abs(latestPitch - base.first)
        val dRoll  = abs(latestRoll  - base.second)

        val wear = wearState

        // 4) If not worn but long free-fall + impact → device drop (do not alert as human fall)
        if (!wear && ffMs >= cfg.freeFallMinMsModerate) {
            Log.i(tag, "📦 DEVICE_DROP: freeFall=${ffMs}ms, aPeak=%.1f (wear=false)".format(aPeak))
            onEvent?.invoke(Event.DeviceDrop(ts, ffMs, aPeak))
            lastImpactT = ts
            fallCooldownUntil = ts + cfg.fallRefractoryMs * 1_000_000
            return
        }

        // 5) If free-fall not long enough, we still allow a FALL when posture/rest is strong (rare sit-to-ground)
        val sev = severity ?: Event.FallSeverity.MODERATE

        Log.i(tag, "🛑 FALL: sev=$sev, freeFall=${ffMs}ms, aPeak=%.1f m/s^2, wPeak=%.1f rad/s, Δpitch=%.0f°, Δroll=%.0f°, restRMS(a=%.2f,w=%.2f), wear=$wear".format(
            aPeak, wPeak, dPitch, dRoll, aRms, wRms
        ))
        onEvent?.invoke(
            Event.Fall(
                tNanos = ts,
                severity = sev,
                freeFallMs = ffMs,
                aPeak = aPeak,
                wPeak = wPeak,
                dPitch = dPitch,
                dRoll = dRoll,
                aRms = aRms,
                wRms = wRms,
                wear = wear
            )
        )
        lastImpactT = ts
        fallCooldownUntil = ts + cfg.fallRefractoryMs * 1_000_000
    }

    // ----------------- HELPERS -----------------

    private fun insert(ts: Long) {
        val s = Sample(ts, latestAMag, latestWMag, latestPitch, latestRoll)
        buf.addLast(s)
        val cutoff = ts - cfg.bufferHorizonMs * 1_000_000
        while (buf.isNotEmpty() && buf.first().t < cutoff) buf.removeFirst()
    }

    private fun computeJerkFromAMag(aMag: Float, ts: Long): Float {
        val prev = prevAMag ?: run { prevAMag = aMag; prevAMagT = ts; return 0f }
        val dt = ((ts - prevAMagT).coerceAtLeast(1)) / 1e9f
        val jerk = abs(aMag - prev) / dt
        prevAMag = aMag
        prevAMagT = ts
        return jerk
    }

    private fun hasImpact(from: Long, to: Long): Boolean {
        for (s in buf) if (s.t in from..to) {
            if (s.aMag >= cfg.aImpact || s.wMag >= cfg.wImpact) return true
        }
        return false
    }

    private fun peakA(from: Long, to: Long): Float {
        var p = 0f
        for (s in buf) if (s.t in from..to) p = max(p, s.aMag)
        return p
    }

    private fun peakW(from: Long, to: Long): Float {
        var p = 0f
        for (s in buf) if (s.t in from..to) p = max(p, s.wMag)
        return p
    }

    private fun rmsA(from: Long, to: Long): Float {
        var sum = 0.0; var n = 0
        for (s in buf) if (s.t in from..to) { sum += (s.aMag * s.aMag); n++ }
        return if (n > 0) sqrt(sum / n).toFloat() else 0f
    }
    private fun rmsW(from: Long, to: Long): Float {
        var sum = 0.0; var n = 0
        for (s in buf) if (s.t in from..to) { sum += (s.wMag * s.wMag); n++ }
        return if (n > 0) sqrt(sum / n).toFloat() else 0f
    }
    private fun rmsIn(from: Long, to: Long): Pair<Float, Float> = rmsA(from, to) to rmsW(from, to)

    /** Continuous duration (ms) around ts where |a| >= threshold (within ±win each side). */
    private fun durAboveA(ts: Long, thresh: Float, halfWin: Long): Long {
        val start = ts - halfWin
        val end   = ts + halfWin
        var durNs = 0L
        var last: Sample? = null
        for (s in buf) {
            if (s.t < start || s.t > end) continue
            if (last != null) {
                val dt = s.t - last!!.t
                if (s.aMag >= thresh && last!!.aMag >= thresh) durNs += dt
            }
            last = s
        }
        return durNs / 1_000_000
    }

    /** Integrate |ω| over [from,to] and return angle in degrees. */
    private fun integOmegaDeg(from: Long, to: Long): Float {
        if (from >= to) return 0f
        var ang = 0.0
        var last: Sample? = null
        for (s in buf) {
            if (s.t < from) { last = s; continue }
            if (s.t > to) break
            if (last != null) {
                val dt = (s.t - last!!.t) / 1e9
                // trapezoid on |ω|
                ang += 0.5 * (s.wMag + last!!.wMag) * dt
            }
            last = s
        }
        return (ang * 180.0 / PI).toFloat()
    }

    /** Free-fall duration ending ≤ lookbackMs before impact time ts (ms). */
    private fun freeFallDurationEndingBefore(ts: Long, lookbackMs: Long): Long {
        if (!haveAccMagG) return 0L
        val start = ffActiveSince ?: return 0L
        val end = ts // conservative: treat impact time as end
        val lastedMs = (end - start) / 1_000_000
        return if (lastedMs in 1..lookbackMs) lastedMs else 0L
    }

    private fun baselineAt(t: Long): Pair<Float, Float> {
        var bp = baselinePitch
        var br = baselineRoll
        for (i in buf.size - 1 downTo 0) {
            val s = buf.elementAt(i)
            if (s.t <= t) { bp = s.pitch; br = s.roll; break }
        }
        return bp to br
    }

    private fun postureOrRestConfirm(until: Long, basePitch: Float, baseRoll: Float): Boolean {
        var postureOk = false
        val restFrom = until - cfg.restWindowMs * 1_000_000
        var sumA = 0.0; var sumW = 0.0; var n = 0

        for (s in buf) {
            if (s.t > until) break
            // posture
            if (!postureOk) {
                val dP = abs(s.pitch - basePitch)
                val dR = abs(s.roll  - baseRoll)
                if (dP >= cfg.postureDeg || dR >= cfg.postureDeg) postureOk = true
            }
            // rest RMS
            if (s.t >= restFrom) {
                sumA += s.aMag * s.aMag
                sumW += s.wMag * s.wMag
                n++
            }
        }
        val restOk = if (n > 0) {
            val aR = sqrt(sumA / n).toFloat()
            val wR = sqrt(sumW / n).toFloat()
            (aR <= cfg.restARms && wR <= cfg.restWRms)
        } else false
        return postureOk || restOk
    }

    /** Heuristic: worn if 2s RMS shows natural head micromotions. */
    private fun isWorn(now: Long): Boolean {
        val aR = rmsA(now - cfg.wearWinMs * 1_000_000, now)
        val wR = rmsW(now - cfg.wearWinMs * 1_000_000, now)
        return (aR >= cfg.wearARmsMin) || (wR >= cfg.wearWRmsMin)
    }

    private fun mag(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
    private fun lerp(a: Float, b: Float, f: Float) = a + f * (b - a)
}