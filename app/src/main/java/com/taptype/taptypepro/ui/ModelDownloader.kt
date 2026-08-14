package com.taptype.taptypepro.ui

import android.content.Context
import android.content.pm.PackageManager
import com.taptype.taptypepro.engine.ModelRegistry
import com.taptype.taptypepro.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object ModelDownloader {
    private const val TAG = "ModelDownloader"

    suspend fun download(
        context: Context,
        model: ModelRegistry.Model,
        onProgress: suspend (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val outFile = ModelRegistry.modelFile(context, model)
        if (outFile.exists() && outFile.length() > 0) {
            DebugLog.i(TAG, "Model ${model.id} already downloaded")
            return@withContext outFile
        }

        val request = android.app.DownloadManager.Request(android.net.Uri.parse(model.url)).apply {
            setTitle("Downloading ${model.name}")
            setDescription("TapType Pro voice model")
            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(android.net.Uri.fromFile(outFile))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            addRequestHeader("User-Agent", "TapTypePro/1.0 (Android)")
        }

        val id = dm.enqueue(request)
        DebugLog.i(TAG, "Enqueued download ${model.url} as #$id")

        var complete = false
        var failedReason = -1
        while (!complete) {
            val q = android.app.DownloadManager.Query().setFilterById(id)
            dm.query(q)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                when (status) {
                    android.app.DownloadManager.STATUS_SUCCESSFUL -> complete = true
                    android.app.DownloadManager.STATUS_FAILED -> {
                        failedReason = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON))
                        complete = true
                    }
                    else -> {
                        if (total > 0) {
                            onProgress(((downloaded.toDouble() / total) * 100).toInt())
                        }
                    }
                }
            }
            if (!complete) delay(250)
        }

        if (failedReason != -1 || !outFile.exists() || outFile.length() == 0L) {
            DebugLog.e(TAG, "Download failed for ${model.id}, reason=$failedReason")
            outFile.delete()
            return@withContext null
        }

        DebugLog.i(TAG, "Download completed: ${outFile.absolutePath}, size=${outFile.length()}")
        outFile
    }
}
