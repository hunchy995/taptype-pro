package com.taptype.taptypepro.engine

import java.io.File

object ModelRegistry {
    // Base URL for model files. Replace with https://models.aziz.me later.
    const val MODEL_BASE_URL = "https://github.com/hunchy995/taptype-pro/releases/download/v1.0.0"

    data class Model(
        val id: String,
        val engine: EngineType,
        val name: String,
        val sizeMB: Long,
        val description: String,
        val filename: String
    ) {
        val url: String
            get() = "$MODEL_BASE_URL/$filename"
    }

    val models = listOf(
        // Whisper (English quantized)
        Model(
            id = "tiny.en-q8_0",
            engine = EngineType.WHISPER,
            name = "Whisper Tiny EN Q8_0",
            sizeMB = 42,
            description = "Fastest Whisper, great for quick dictation",
            filename = "ggml-tiny.en-q8_0.bin"
        ),
        Model(
            id = "base.en-q5_1",
            engine = EngineType.WHISPER,
            name = "Whisper Base EN Q5_1",
            sizeMB = 60,
            description = "Balanced Whisper, better punctuation",
            filename = "ggml-base.en-q5_1.bin"
        ),
        Model(
            id = "small.en-q5_1",
            engine = EngineType.WHISPER,
            name = "Whisper Small EN Q5_1",
            sizeMB = 190,
            description = "Best Whisper accuracy, slower",
            filename = "ggml-small.en-q5_1.bin"
        )
    )

    fun forEngine(engine: EngineType): List<Model> = models.filter { it.engine == engine }
    fun byId(id: String): Model? = models.find { it.id == id }

    fun modelDir(context: android.content.Context): File = File(context.filesDir, "models").also { it.mkdirs() }

    fun modelFile(context: android.content.Context, model: Model): File {
        return File(modelDir(context), model.filename)
    }
}
