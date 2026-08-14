package com.taptype.taptypepro.ui

import android.content.Context
import com.taptype.taptypepro.engine.ModelRegistry
import com.taptype.taptypepro.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {
    private const val TAG = "ModelDownloader"

    suspend fun download(
        context: Context,
        model: ModelRegistry.Model,
        onProgress: suspend (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val outFile = ModelRegistry.modelFile(context, model)
        val partFile = File(outFile.absolutePath + ".part")
        if (outFile.exists() && outFile.length() > 0) {
            DebugLog.i(TAG, "Model ${model.id} already downloaded")
            return@withContext outFile
        }

        try {
            partFile.parentFile?.mkdirs()
            val url = URL(model.url)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "TapTypePro/1.0 (Android)")
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.doInput = true
            conn.instanceFollowRedirects = true
            conn.connect()

            val responseCode = conn.responseCode
            DebugLog.i(TAG, "Downloading ${model.url} → response=$responseCode")
            if (responseCode !in 200..299) {
                DebugLog.e(TAG, "HTTP error $responseCode for ${model.url}")
                return@withContext null
            }

            val total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            partFile.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var bytes = 0
                    while (isActive && input.read(buffer).also { bytes = it } != -1) {
                        if (bytes > 0) out.write(buffer, 0, bytes)
                        downloaded += bytes
                        if (total > 0) {
                            val pct = ((downloaded.toDouble() / total) * 100).toInt()
                            onProgress(pct)
                        }
                    }
                }
            }

            if (!isActive) {
                partFile.delete()
                return@withContext null
            }

            partFile.renameTo(outFile)
            if (!outFile.exists() || outFile.length() == 0L) {
                DebugLog.e(TAG, "Output file missing or empty after download")
                return@withContext null
            }
            DebugLog.i(TAG, "Download completed: ${outFile.absolutePath}, size=${outFile.length()}")
            outFile
        } catch (e: Exception) {
            DebugLog.e(TAG, "Download exception for ${model.id}", e)
            partFile.delete()
            null
        }
    }
}
