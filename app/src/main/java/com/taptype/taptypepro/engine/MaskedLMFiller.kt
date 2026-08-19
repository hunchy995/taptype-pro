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
 * On-device masked-language-model (DistilBERT) used to suggest contextually-correct
 * replacements for low-confidence ASR words. Masks a word in the sentence, runs the
 * fill-mask model, and returns the top candidate words that complete the sentence.
 */
object MaskedLMFiller {
    private const val TAG = "MaskedLMFiller"

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private val lock = Any()

    val isReady: Boolean
        get() = synchronized(lock) { session != null && tokenizer != null }

    /** Load the fill-mask model + vocab if present on disk. Returns true when ready. */
    fun ensureLoaded(context: Context): Boolean = synchronized(lock) {
        if (session != null && tokenizer != null) return@synchronized true
        val model = ModelRegistry.maskedLmModel
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
            DebugLog.i(TAG, "MLM filler model loaded")
            true
        } catch (ex: Exception) {
            DebugLog.e(TAG, "MLM filler load failed", ex)
            false
        }
    }

    /**
     * Contextual suggestions for the word at [wordIndex] in [text]. Masks that word,
     * runs the fill-mask model, and returns up to [topK] candidate words (no special
     * tokens or "##" continuation pieces). Returns empty on any failure.
     */
    fun suggest(text: String, wordIndex: Int, topK: Int = 5): List<String> {
        val e = synchronized(lock) { env } ?: return emptyList()
        val s = synchronized(lock) { session } ?: return emptyList()
        val t = synchronized(lock) { tokenizer } ?: return emptyList()
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (wordIndex < 0 || wordIndex >= words.size) return emptyList()

        return try {
            val tok = t.tokenizeMasked(text, wordIndex)
            if (tok.maskPositions.isEmpty()) return emptyList()
            val maskPos = tok.maskPositions[0]

            val inputIds = OnnxTensor.createTensor(e, LongBuffer.wrap(tok.ids), longArrayOf(1L, tok.ids.size.toLong()))
            val attentionMask = OnnxTensor.createTensor(e, LongBuffer.wrap(tok.mask), longArrayOf(1L, tok.mask.size.toLong()))
            try {
                s.run(mapOf("input_ids" to inputIds, "attention_mask" to attentionMask)).use { results ->
                    val logits = results[0] as OnnxTensor
                    val shape = logits.info.shape
                    val vocabSize = if (shape.isNotEmpty()) shape.last().toInt() else 0
                    if (vocabSize <= 0) return emptyList()
                    val buf = logits.floatBuffer
                    val flat = FloatArray(buf.remaining())
                    buf.get(flat)

                    val base = maskPos * vocabSize
                    val scored = ArrayList<Pair<Int, Float>>(vocabSize)
                    for (k in 0 until vocabSize) {
                        scored.add(k to flat[base + k])
                    }
                    scored.sortByDescending { it.second }

                    val out = ArrayList<String>(topK)
                    for ((id, _) in scored) {
                        if (out.size >= topK) break
                        val tokStr = t.tokenForId(id) ?: continue
                        if (!isUsableWord(tokStr)) continue
                        out.add(tokStr)
                    }
                    DebugLog.d(TAG, "suggest #$wordIndex \"${words[wordIndex]}\" -> $out")
                    out
                }
            } finally {
                inputIds.close()
                attentionMask.close()
            }
        } catch (ex: Exception) {
            DebugLog.e(TAG, "suggest failed", ex)
            emptyList()
        }
    }

    /** Best single suggestion for the word at [wordIndex], or null. */
    fun suggestBest(text: String, wordIndex: Int): String? = suggest(text, wordIndex, 1).firstOrNull()

    /** A candidate token is a real word: not special, not a "##" continuation, has letters. */
    private fun isUsableWord(token: String): Boolean {
        if (token.isEmpty()) return false
        if (token.startsWith("[") || token.startsWith("##")) return false
        return token.any { it.isLetter() }
    }

    fun release() = synchronized(lock) {
        try { session?.close() } catch (_: Exception) {}
        try { env?.close() } catch (_: Exception) {}
        session = null
        env = null
        tokenizer = null
    }
}
