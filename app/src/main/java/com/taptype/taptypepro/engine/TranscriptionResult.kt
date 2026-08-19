package com.taptype.taptypepro.engine

/**
 * A single word of an ASR transcription and its acoustic confidence (0..1).
 * Lower = the model is less sure it heard this word correctly.
 */
data class WordConfidence(val word: String, val probability: Float)

/**
 * Full transcription result: the raw text plus per-word confidence, aligned
 * to the words in [text] in order. Engines that can't report confidence
 * (e.g. Whisper in the current config) return an empty [words] list.
 */
data class TranscriptionResult(
    val text: String,
    val words: List<WordConfidence>
)

/**
 * Implemented by engines that can expose per-word confidence alongside the
 * transcript (currently only Parakeet CTC, where the softmax gives it for free).
 */
interface ConfidenceEngine {
    fun transcribeWithConfidence(audioData: FloatArray): TranscriptionResult
}
