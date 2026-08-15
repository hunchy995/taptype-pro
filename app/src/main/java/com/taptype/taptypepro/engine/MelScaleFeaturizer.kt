package com.taptype.taptypepro.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Log-Mel spectrogram featurizer matching the official onnx-asr NeMo preprocessor
 * (https://github.com/istupakov/onnx-asr). This is the exact preprocessing the
 * exported Parakeet CTC model was trained with.
 *
 * Ported 1:1 from Taptalk.Engine.Parakeet.MelScaleFeaturizer (C#).
 *
 * Pipeline:
 * 1. Pre-emphasis (coefficient 0.97) on [-1,1] float waveform
 * 2. Constant zero pad by n_fft/2 on both sides
 * 3. 512-point FFT with a 400-point Hann window zero-padded to 512
 * 4. Slaney mel scale filterbank (80 bands) with Slaney bandwidth normalization
 * 5. log(mel + 2^-24)
 * 6. Per-feature instance normalization across valid frames
 *
 * Output layout: flattened [1, 80, frames]
 */
class MelScaleFeaturizer {

    companion object {
        const val SAMPLE_RATE = 16000
        const val WINDOW_SIZE = 400   // win_length
        const val HOP_LENGTH = 160
        const val FFT_SIZE = 512      // n_fft
        const val MEL_BANDS = 80
        const val PREEMPHASIS = 0.97f
        const val LOG_ZERO_GUARD = 5.96046448e-08f // 2^-24
    }

    private val window = FloatArray(FFT_SIZE)
    private val melFilterbank = Array(FFT_SIZE / 2 + 1) { FloatArray(MEL_BANDS) }

    init {
        // 400-point symmetric Hann window, then zero-pad to 512 (centered).
        val hann400 = createHannWindow(WINDOW_SIZE)
        val pad = (FFT_SIZE - WINDOW_SIZE) / 2 // 56
        for (i in 0 until WINDOW_SIZE) {
            window[pad + i] = hann400[i]
        }

        val fb = buildSlaneyMelFilterbank(
            nFreqs = FFT_SIZE / 2 + 1,
            fMin = 0f,
            fMax = SAMPLE_RATE / 2f,
            nMels = MEL_BANDS,
            sampleRate = SAMPLE_RATE,
            norm = true
        )
        for (i in 0 until FFT_SIZE / 2 + 1) {
            for (j in 0 until MEL_BANDS) {
                melFilterbank[i][j] = fb[i][j]
            }
        }
    }

    /** Convert mono 16kHz float PCM (range roughly [-1,1]) to NeMo log-mel features. */
    fun extract(waveform: FloatArray): FloatArray {
        if (waveform.isEmpty() || waveform.size < WINDOW_SIZE) return FloatArray(0)

        // 1. Pre-emphasis: x[t] - 0.97 * x[t-1], with x[-1] = 0
        val pcm = FloatArray(waveform.size)
        pcm[0] = waveform[0]
        for (i in 1 until waveform.size) {
            pcm[i] = waveform[i] - PREEMPHASIS * waveform[i - 1]
        }

        // 2. Constant zero pad by FFT_SIZE/2 on each side
        val pad = FFT_SIZE / 2
        val paddedLen = pcm.size + 2 * pad
        val padded = FloatArray(paddedLen)
        System.arraycopy(pcm, 0, padded, pad, pcm.size)

        // 3. Number of frames after padding
        val frames = (paddedLen - FFT_SIZE) / HOP_LENGTH + 1
        if (frames <= 0) return FloatArray(0)

        // 4. Compute log-mel spectrogram [frames, MEL_BANDS]
        val logMel = Array(frames) { FloatArray(MEL_BANDS) }
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)

        for (t in 0 until frames) {
            val start = t * HOP_LENGTH
            java.util.Arrays.fill(real, 0f)
            java.util.Arrays.fill(imag, 0f)
            for (i in 0 until FFT_SIZE) {
                real[i] = padded[start + i] * window[i]
            }

            fft(real, imag, FFT_SIZE)

            for (m in 0 until MEL_BANDS) {
                var melEnergy = 0.0
                for (b in 0 until FFT_SIZE / 2 + 1) {
                    val power = real[b] * real[b] + imag[b] * imag[b]
                    melEnergy += power * melFilterbank[b][m]
                }
                logMel[t][m] = ln(max(melEnergy, 0.0) + LOG_ZERO_GUARD).toFloat()
            }
        }

        // 5. Per-feature instance normalization across valid frames.
        var validFrames = waveform.size / HOP_LENGTH
        if (validFrames <= 0) validFrames = frames
        if (validFrames > frames) validFrames = frames

        val means = FloatArray(MEL_BANDS)
        val vars = FloatArray(MEL_BANDS)
        for (m in 0 until MEL_BANDS) {
            var sum = 0.0
            for (t in 0 until validFrames) sum += logMel[t][m]
            means[m] = (sum / validFrames).toFloat()

            var sq = 0.0
            for (t in 0 until validFrames) {
                val d = logMel[t][m] - means[m]
                sq += d * d
            }
            vars[m] = (sq / max(1, validFrames - 1)).toFloat()
        }

        // 6. Transpose to [1, MEL_BANDS, frames], normalize valid frames only.
        val features = FloatArray(1 * MEL_BANDS * frames)
        var idx = 0
        for (m in 0 until MEL_BANDS) {
            for (t in 0 until frames) {
                val v = if (t < validFrames) {
                    (logMel[t][m] - means[m]) / (sqrt(vars[m]) + 1e-5f)
                } else 0f
                features[idx++] = v
            }
        }
        return features
    }

    /** Number of valid frames this waveform produces. */
    fun frameCount(sampleCount: Int): Int = if (sampleCount > 0) sampleCount / HOP_LENGTH else 0

    private fun createHannWindow(size: Int): FloatArray {
        val w = FloatArray(size)
        for (i in 0 until size) {
            w[i] = 0.5f * (1f - cos(2f * PI.toFloat() * i / (size - 1)))
        }
        return w
    }

    private fun buildSlaneyMelFilterbank(
        nFreqs: Int, fMin: Float, fMax: Float, nMels: Int, sampleRate: Int, norm: Boolean
    ): Array<FloatArray> {
        val mMin = hzToMel(fMin)
        val mMax = hzToMel(fMax)
        val mPts = DoubleArray(nMels + 2)
        for (i in 0 until nMels + 2) mPts[i] = mMin + i * (mMax - mMin) / (nMels + 1)

        val hzPts = DoubleArray(nMels + 2)
        for (i in 0 until nMels + 2) hzPts[i] = melToHz(mPts[i])

        val bins = IntArray(nMels + 2)
        for (i in 0 until nMels + 2) bins[i] = floor((nFreqs - 1) * hzPts[i] / (sampleRate / 2.0)).toInt()

        val fb = Array(nFreqs) { FloatArray(nMels) }
        for (i in 0 until nMels) {
            val from = max(0, bins[i])
            val to = min(nFreqs, bins[i + 2] + 1)
            for (j in from until to) {
                val left = (j - bins[i]) / (bins[i + 1] - bins[i]).toDouble()
                val right = (bins[i + 2] - j) / (bins[i + 2] - bins[i + 1]).toDouble()
                val v = max(0.0, min(left, right))
                fb[j][i] = v.toFloat()
            }
        }

        if (norm) {
            for (i in 0 until nMels) {
                val width = hzPts[i + 2] - hzPts[i]
                val scale = if (width > 0) (2.0 / width).toFloat() else 1f
                for (j in 0 until nFreqs) fb[j][i] *= scale
            }
        }
        return fb
    }

    private fun hzToMel(hz: Float): Double {
        if (hz < 1000f) return 3.0 * hz / 200.0
        return 15.0 + 27.0 * ln(hz / 1000.0 + Double.MIN_VALUE) / ln(6.4)
    }

    private fun melToHz(mel: Double): Double {
        if (mel < 15.0) return 200.0 * mel / 3.0
        return 1000.0 * 6.4.pow((mel - 15.0) / 27.0)
    }

    /** Cooley-Tukey radix-2 iterative FFT (in-place). */
    private fun fft(real: FloatArray, imag: FloatArray, n: Int) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var k = n shr 1
            while (k <= j) { j -= k; k = k shr 1 }
            j += k
        }

        var len = 2
        while (len <= n) {
            val ang = -2f * PI.toFloat() / len
            val wlr = cos(ang)
            val wli = sin(ang)
            var i = 0
            while (i < n) {
                var ur = 1f
                var ui = 0f
                for (m in 0 until len / 2) {
                    val even = i + m
                    val odd = i + m + len / 2
                    val tr = ur * real[odd] - ui * imag[odd]
                    val ti = ur * imag[odd] + ui * real[odd]
                    real[odd] = real[even] - tr
                    imag[odd] = imag[even] - ti
                    real[even] += tr
                    imag[even] += ti
                    val t = ur * wlr - ui * wli
                    ui = ur * wli + ui * wlr
                    ur = t
                }
                i += len
            }
            len = len shl 1
        }
    }
}
