package com.taptype.taptypepro.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.taptype.taptypepro.util.DebugLog
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * NVIDIA Parakeet ASR (CTC) via ONNX Runtime.
 * Model: istupakov/parakeet-ctc-0.6b-onnx (self-contained model.int8.onnx).
 *
 * Ported from Taptalk.Engine.Parakeet (C#), adapted for Android: NNAPI EP with
 * CPU fallback (no DirectML on Android).
 */
class ParakeetEngine : SpeechEngine, ConfidenceEngine {
    private val TAG = "ParakeetEngine"

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val featurizer = MelScaleFeaturizer()

    private val runLock = Any()

    private var inputName = "audio_signal"
    private var hasLengthInput = false
    private var outputNames = emptyArray<String>()

    private var vocab: Array<String>? = null

    override var isLoaded = false
        private set
    override var loadedModelId = ""
        private set

    override val type: EngineType = EngineType.PARAKEET

    override fun load(modelDir: File, modelId: String): Boolean {
        val model = ModelRegistry.byId(modelId) ?: return false
        val modelFile = File(modelDir, model.filename)
        if (!modelFile.exists()) return false

        return try {
            env?.close()
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                try {
                    addNnapi()
                    DebugLog.i(TAG, "NNAPI EP enabled")
                } catch (e: Exception) {
                    DebugLog.w(TAG, "NNAPI unavailable, using CPU: ${e.message}")
                }
            }
            session = env?.createSession(modelFile.absolutePath, opts)
            readModelMetadata()
            loadVocabulary(modelDir)
            isLoaded = true
            loadedModelId = modelId
            DebugLog.i(TAG, "Loaded $modelId (input='$inputName', hasLength=$hasLengthInput, outputs=${outputNames.joinToString()})")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to load $modelId", e)
            false
        }
    }

    private fun readModelMetadata() {
        val sess = session ?: return
        outputNames = sess.outputNames.toTypedArray()
        inputName = "audio_signal"
        hasLengthInput = false
        for (name in sess.inputNames) {
            if (name.equals("length", ignoreCase = true)) {
                hasLengthInput = true
                continue
            }
            if (inputName == "audio_signal") inputName = name
        }
    }

    private fun loadVocabulary(modelDir: File) {
        try {
            val vocabFile = File(modelDir, "vocab.txt")
            if (!vocabFile.exists()) {
                DebugLog.w(TAG, "vocab.txt not found in $modelDir")
                return
            }
            val lines = vocabFile.readLines()
            var maxIndex = -1
            val entries = mutableListOf<Pair<String, Int>>()

            for (line in lines) {
                if (line.isBlank()) continue
                val lastSpace = line.lastIndexOf(' ')
                if (lastSpace > 0 && line.substring(lastSpace + 1).toIntOrNull() != null) {
                    val idx = line.substring(lastSpace + 1).toInt()
                    entries.add(line.substring(0, lastSpace) to idx)
                    if (idx > maxIndex) maxIndex = idx
                } else {
                    val fallback = entries.size
                    entries.add(line.trim() to fallback)
                    if (fallback > maxIndex) maxIndex = fallback
                }
            }

            val vocabSize = max(maxIndex + 1, 1025)
            val arr = arrayOfNulls<String>(vocabSize)
            for ((token, index) in entries) {
                if (index in arr.indices) arr[index] = token
            }
            vocab = arr.map { it ?: "" }.toTypedArray()
            DebugLog.i(TAG, "Vocabulary loaded: ${vocabSize} tokens")
        } catch (e: Exception) {
            DebugLog.e(TAG, "Vocabulary load failed", e)
        }
    }

    override fun transcribe(audioData: FloatArray): String =
        transcribeWithConfidence(audioData).text

    override fun transcribeWithConfidence(audioData: FloatArray): TranscriptionResult {
        val sess = session ?: return TranscriptionResult("", emptyList())
        if (audioData.size < MelScaleFeaturizer.WINDOW_SIZE) return TranscriptionResult("", emptyList())

        return try {
            val normalized = normalizeForInference(audioData)
            val features = featurizer.extract(normalized)
            if (features.isEmpty()) TranscriptionResult("", emptyList())
            else runInference(sess, features, normalized.size)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Transcription failed", e)
            TranscriptionResult("", emptyList())
        }
    }

    private fun normalizeForInference(audio: FloatArray): FloatArray {
        if (audio.isEmpty()) return audio
        val copy = audio.copyOf()
        AudioNormalizer.normalizeInPlace(copy)
        return copy
    }

    private fun runInference(sess: OrtSession, features: FloatArray, rawSamples: Int): TranscriptionResult {
        val total = features.size
        if (total == 0 || total % MelScaleFeaturizer.MEL_BANDS != 0) {
            DebugLog.e(TAG, "Invalid feature tensor length $total")
            return TranscriptionResult("", emptyList())
        }

        val frames = total / MelScaleFeaturizer.MEL_BANDS
        val melBins = MelScaleFeaturizer.MEL_BANDS

        synchronized(runLock) {
            val inputTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(features), longArrayOf(1L, melBins.toLong(), frames.toLong())
            )

            val inputs = HashMap<String, OnnxTensor>()
            inputs[inputName] = inputTensor

            var lenTensor: OnnxTensor? = null
            val length = max(1L, (rawSamples / MelScaleFeaturizer.HOP_LENGTH).toLong())
            if (hasLengthInput) {
                lenTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(length)), longArrayOf(1L)
                )
                inputs["length"] = lenTensor
            }

            try {
                val results = sess.run(inputs)
                results.use {
                    val output = results.get(0) as OnnxTensor
                    val shape = output.info.shape
                    val t = if (shape.size >= 2) shape[shape.size - 2].toInt() else 1
                    val v = if (shape.size >= 1) shape[shape.size - 1].toInt() else 1

                    val buf = output.floatBuffer
                    val logits = FloatArray(buf.remaining())
                    buf.get(logits)
                    return decodeCtc(logits, t, v)
                }
            } finally {
                lenTensor?.close()
                inputTensor.close()
            }
        }
    }

    /**
     * Greedy CTC decode with per-frame softmax confidence. Returns the decoded
     * text plus per-word confidence (min token probability within each word).
     */
    private fun decodeCtc(logits: FloatArray, t: Int, v: Int): TranscriptionResult {
        val blank = v - 1

        // Frame-level argmax + numerically-stable softmax probability of the pick.
        val frameTokens = ArrayList<Int>(t)
        val frameProbs = ArrayList<Float>(t)
        for (frame in 0 until t) {
            val base = frame * v
            var best = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (k in 0 until v) {
                val x = logits[base + k]
                if (x > bestVal) { bestVal = x; best = k }
            }
            var sumExp = 0.0
            for (k in 0 until v) {
                sumExp += exp((logits[base + k] - bestVal).toDouble())
            }
            val prob = (1.0 / sumExp).toFloat()  // exp(bestVal - bestVal) == 1
            frameTokens.add(best)
            frameProbs.add(prob)
        }

        // CTC collapse: drop blanks, merge consecutive repeats, average the
        // confidence over the frames that produced each kept token.
        val collapsed = ArrayList<Int>()
        val collapsedProbs = ArrayList<Float>()
        var prev = -1
        var runSum = 0f
        var runCount = 0
        for (i in frameTokens.indices) {
            val tok = frameTokens[i]
            if (tok == blank) continue
            if (tok != prev) {
                if (prev != -1 && runCount > 0) {
                    collapsed.add(prev)
                    collapsedProbs.add(runSum / runCount)
                }
                prev = tok
                runSum = frameProbs[i]
                runCount = 1
            } else {
                runSum += frameProbs[i]
                runCount++
            }
        }
        if (prev != -1 && runCount > 0) {
            collapsed.add(prev)
            collapsedProbs.add(runSum / runCount)
        }

        return decodeWithConfidence(collapsed, collapsedProbs)
    }

    /** Decode collapsed tokens into text + per-word confidence (SentencePiece ▁ boundaries). */
    private fun decodeWithConfidence(tokens: List<Int>, probs: List<Float>): TranscriptionResult {
        val v = vocab
        if (v == null || v.isEmpty()) {
            return TranscriptionResult("[No vocab: ${tokens.joinToString(" ")}]", emptyList())
        }

        val sb = StringBuilder()
        val words = ArrayList<WordConfidence>()
        var curWord = StringBuilder()
        var curMinProb = 1f
        var inWord = false

        for (i in tokens.indices) {
            val t = tokens[i]
            if (t !in v.indices) continue
            val token = v[t]
            if (token.isNullOrEmpty()) continue
            if (token.startsWith("<") && token.endsWith(">")) continue
            val p = if (i < probs.size) probs[i] else 1f
            val isBoundary = token.startsWith("\u2581")
            val piece = token.replace("\u2581", "")
            if (isBoundary) {
                if (inWord && curWord.isNotBlank()) {
                    words.add(WordConfidence(curWord.toString(), curMinProb))
                }
                curWord = StringBuilder(piece)
                curMinProb = p
                inWord = true
            } else {
                curWord.append(piece)
                if (p < curMinProb) curMinProb = p
            }
            sb.append(token)
        }
        if (inWord && curWord.isNotBlank()) {
            words.add(WordConfidence(curWord.toString(), curMinProb))
        }

        var result = sb.toString().replace("\u2581", " ")
        result = Regex("\\s+").replace(result, " ").trim()
        DebugLog.d(TAG, "Decoded: \"$result\" (${words.size} words, min conf ${words.minOfOrNull { it.probability } ?: -1f})")
        return TranscriptionResult(result, words)
    }

    override fun release() {
        try {
            session?.close()
            env?.close()
        } catch (e: Exception) {
            DebugLog.e(TAG, "Release error", e)
        }
        session = null
        env = null
        isLoaded = false
        loadedModelId = ""
    }
}
