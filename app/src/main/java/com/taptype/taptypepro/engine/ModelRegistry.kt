package com.taptype.taptypepro.engine

import java.io.File

object ModelRegistry {
    // Models are downloaded directly from Hugging Face (works on your network).
    // Point this to models.aziz.me later when the CDN is ready.
    const val WHISPER_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    const val PARAKEET_BASE_URL = "https://huggingface.co/istupakov/parakeet-ctc-0.6b-onnx/resolve/main"
    const val PUNCT_BASE_URL = "https://huggingface.co/olegphenomenon/bert-restore-punctuation-onnx/resolve/main"

    data class Model(
        val id: String,
        val engine: EngineType,
        val name: String,
        val sizeMB: Long,
        val description: String,
        val filename: String,
        val hfUrl: String,
        // Optional secondary file (e.g. vocab.txt) downloaded alongside the model.
        val auxFilename: String? = null,
        val auxUrl: String? = null
    ) {
        val url: String
            get() = hfUrl
    }

    val models = listOf(
        // Whisper (English quantized)
        Model(
            id = "tiny.en-q8_0",
            engine = EngineType.WHISPER,
            name = "Whisper Tiny EN Q8_0",
            sizeMB = 42,
            description = "Fastest Whisper, great for quick dictation",
            filename = "ggml-tiny.en-q8_0.bin",
            hfUrl = "$WHISPER_BASE_URL/ggml-tiny.en-q8_0.bin"
        ),
        Model(
            id = "base.en-q5_1",
            engine = EngineType.WHISPER,
            name = "Whisper Base EN Q5_1",
            sizeMB = 57,
            description = "Balanced Whisper, better punctuation",
            filename = "ggml-base.en-q5_1.bin",
            hfUrl = "$WHISPER_BASE_URL/ggml-base.en-q5_1.bin"
        ),
        Model(
            id = "small.en-q5_1",
            engine = EngineType.WHISPER,
            name = "Whisper Small EN Q5_1",
            sizeMB = 182,
            description = "Best Whisper accuracy, slower",
            filename = "ggml-small.en-q5_1.bin",
            hfUrl = "$WHISPER_BASE_URL/ggml-small.en-q5_1.bin"
        ),
        // Parakeet CTC (NVIDIA NeMo, self-contained int8 ONNX)
        Model(
            id = "parakeet-0.6b",
            engine = EngineType.PARAKEET,
            name = "Parakeet 0.6b ONNX",
            sizeMB = 653,
            description = "NVIDIA Parakeet CTC — fast, accurate",
            filename = "model.int8.onnx",
            hfUrl = "$PARAKEET_BASE_URL/model.int8.onnx",
            auxFilename = "vocab.txt",
            auxUrl = "$PARAKEET_BASE_URL/vocab.txt"
        )
    )

    // Support model — on-device AI punctuation restoration. Deliberately NOT in
    // `models` (it is not an ASR engine the user picks); loaded automatically by
    // PunctuationRestorer and downloaded with the same ModelDownloader. `engine` is
    // a placeholder and unused by the downloader/restorer.
    val punctuationModel = Model(
        id = "punct-restore",
        engine = EngineType.WHISPER,
        name = "AI Punctuation",
        sizeMB = 106,
        description = "BERT punctuation + capitalization restoration (on-device)",
        filename = "punct_restore.onnx",
        hfUrl = "$PUNCT_BASE_URL/model_quantized.onnx",
        auxFilename = "punct_vocab.txt",
        auxUrl = "$PUNCT_BASE_URL/vocab.txt"
    )

    fun forEngine(engine: EngineType): List<Model> = models.filter { it.engine == engine }
    fun byId(id: String): Model? = models.find { it.id == id }

    fun modelDir(context: android.content.Context): File = File(context.filesDir, "models").also { it.mkdirs() }

    fun modelFile(context: android.content.Context, model: Model): File {
        return File(modelDir(context), model.filename)
    }

    fun auxFile(context: android.content.Context, model: Model): File? {
        return model.auxFilename?.let { File(modelDir(context), it) }
    }
}
