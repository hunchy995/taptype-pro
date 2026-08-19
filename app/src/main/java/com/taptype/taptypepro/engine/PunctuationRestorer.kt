package com.taptype.taptypepro.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.taptype.taptypepro.util.DebugLog
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * BERT punctuation-restoration model (felflare/bert-restore-punctuation, int8 ONNX).
 *
 * Takes raw, lowercased ASR text and returns punctuated + capitalized text, running
 * entirely on-device via ONNX Runtime (CPU). Used on the FINAL transcript only, not
 * live partials.
 */
object PunctuationRestorer {
    private const val TAG = "PunctuationRestorer"

    // Label scheme: {punct}{case}. First char = punctuation to append ('O' = none),
    // second char 'U' = capitalize the word, 'O' = keep lowercase.
    private val LABELS = arrayOf(
        "OU", "OO", ".O", "!O", ",O", ".U", "!U", ",U",
        ":O", ";O", ":U", "'O", "-O", "?O", "?U"
    )

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private val lock = Any()

    val isReady: Boolean
        get() = synchronized(lock) { session != null && tokenizer != null }

    /** Load the model + vocab if present on disk. Returns true when ready. */
    fun ensureLoaded(context: Context): Boolean = synchronized(lock) {
        if (session != null && tokenizer != null) return@synchronized true
        val model = ModelRegistry.punctuationModel
        val dir = ModelRegistry.modelDir(context)
        val modelFile = File(dir, model.filename)
        val vocabFile = File(dir, model.auxFilename ?: return@synchronized false)
        if (!modelFile.exists() || !vocabFile.exists()) {
            return@synchronized false
        }
        try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = e.createSession(modelFile.absolutePath, opts)
            val t = WordPieceTokenizer(vocabFile)
            env = e
            session = s
            tokenizer = t
            DebugLog.i(TAG, "Punctuation model loaded")
            true
        } catch (ex: Exception) {
            DebugLog.e(TAG, "Punctuation model load failed", ex)
            false
        }
    }

    /** Punctuate + capitalize raw text. Returns input unchanged on any failure. */
    fun punctuate(text: String): String {
        val e = synchronized(lock) { env }
        val s = synchronized(lock) { session }
        val t = synchronized(lock) { tokenizer }
        if (e == null || s == null || t == null) return text
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return text
        return try {
            if (words.size <= 140) {
                punctuateChunk(e, s, t, words)
            } else {
                val sb = StringBuilder()
                var i = 0
                while (i < words.size) {
                    val end = minOf(i + 140, words.size)
                    sb.append(punctuateChunk(e, s, t, words.subList(i, end))).append(' ')
                    i = end
                }
                sb.toString().trim()
            }
        } catch (ex: Exception) {
            DebugLog.e(TAG, "punctuate failed", ex)
            text
        }
    }

    private fun punctuateChunk(
        e: OrtEnvironment,
        s: OrtSession,
        t: WordPieceTokenizer,
        words: List<String>
    ): String {
        val tok = t.tokenize(words.joinToString(" "))
        val inputIds = OnnxTensor.createTensor(e, LongBuffer.wrap(tok.ids), longArrayOf(1L, tok.ids.size.toLong()))
        val attentionMask = OnnxTensor.createTensor(e, LongBuffer.wrap(tok.mask), longArrayOf(1L, tok.mask.size.toLong()))
        val tokenTypeIds = OnnxTensor.createTensor(e, LongBuffer.wrap(tok.typeIds), longArrayOf(1L, tok.typeIds.size.toLong()))
        try {
            s.run(
                mapOf(
                    "input_ids" to inputIds,
                    "attention_mask" to attentionMask,
                    "token_type_ids" to tokenTypeIds
                )
            ).use { results ->
                val logits = results[0] as OnnxTensor
                val buf = logits.floatBuffer
                val flat = FloatArray(buf.remaining())
                buf.get(flat)

                val numLabels = LABELS.size
                val out = StringBuilder()
                for (wi in 0 until tok.wordCount) {
                    val base = tok.wordStarts[wi] * numLabels
                    var best = 0
                    var bestVal = Float.NEGATIVE_INFINITY
                    for (k in 0 until numLabels) {
                        val v = flat[base + k]
                        if (v > bestVal) { bestVal = v; best = k }
                    }
                    val label = LABELS[best]
                    val word = words[wi]
                    out.append(if (label[1] == 'U') word.lowercase().replaceFirstChar { it.uppercaseChar() } else word)
                    if (label[0] != 'O') out.append(label[0])
                    out.append(' ')
                }
                var result = out.toString().trim()
                if (result.isNotEmpty() && result.last().isLetterOrDigit()) result += "."
                return result
            }
        } finally {
            inputIds.close()
            attentionMask.close()
            tokenTypeIds.close()
        }
    }

    fun release() = synchronized(lock) {
        try { session?.close() } catch (_: Exception) {}
        try { env?.close() } catch (_: Exception) {}
        session = null
        env = null
        tokenizer = null
    }
}
