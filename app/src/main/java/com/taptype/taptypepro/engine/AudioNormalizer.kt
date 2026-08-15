package com.taptype.taptypepro.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Rescues quiet microphone input before ASR featurization.
 * Ported 1:1 from Taptalk.Core.AudioNormalizer (C#).
 */
object AudioNormalizer {
    private const val SILENCE_FLOOR = 0.0005f // ~ -66 dBFS
    private const val TARGET_PEAK = 0.90f
    private const val MAX_GAIN_LIMIT = 30.0f

    /**
     * In-place DC-offset removal + peak normalization. Returns the gain applied.
     */
    fun normalizeInPlace(samples: FloatArray): Float {
        if (samples.isEmpty()) return 1.0f

        // 1. Remove DC offset (zero-mean)
        var sum = 0.0
        for (v in samples) sum += v
        val dcOffset = (sum / samples.size).toFloat()

        var peak = 0.0f
        for (i in samples.indices) {
            samples[i] -= dcOffset
            val a = abs(samples[i])
            if (a > peak) peak = a
        }

        // 2. Digital silence → do nothing
        if (peak < SILENCE_FLOOR) return 1.0f

        val idealGain = TARGET_PEAK / peak
        val appliedGain = minOf(idealGain, MAX_GAIN_LIMIT)

        // 3. Apply gain with hard clamp + noise gate
        if (abs(appliedGain - 1.0f) > 0.01f) {
            val gateThreshold = SILENCE_FLOOR * 8f
            for (i in samples.indices) {
                val v = samples[i] * appliedGain
                samples[i] = when {
                    abs(v) < gateThreshold -> 0f
                    v > 1.0f -> 1.0f
                    v < -1.0f -> -1.0f
                    else -> v
                }
            }
        }
        return appliedGain
    }

    /** Compute peak + RMS of a raw buffer (diagnostics). */
    fun measure(samples: FloatArray): Pair<Float, Double> {
        var peak = 0f
        var sumSq = 0.0
        for (v in samples) {
            val a = abs(v)
            if (a > peak) peak = a
            sumSq += v * v
        }
        val rms = if (samples.isNotEmpty()) sqrt(sumSq / samples.size) else 0.0
        return peak to rms
    }
}
