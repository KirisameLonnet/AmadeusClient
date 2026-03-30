package com.astramadeus.client.ocr

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Base class for downloading OCR model files.
 *
 * Subclasses provide the model file URLs, directory name, timeouts,
 * and estimated size. All download, status, and deletion logic is shared.
 */
abstract class BaseModelDownloader {

    enum class Status { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, ERROR }

    protected abstract val tag: String
    protected abstract val modelDirName: String
    protected abstract val readyMarker: String
    protected abstract val connectTimeoutMs: Int
    protected abstract val readTimeoutMs: Int
    protected abstract val estimatedTotalSizeMb: Int
    protected abstract val modelFiles: Map<String, String>

    private val currentStatus = AtomicReference(Status.NOT_DOWNLOADED)

    @Volatile
    private var downloadProgress: Float = 0f

    @Volatile
    private var lastError: String? = null

    fun getStatus(context: Context): Status {
        val status = currentStatus.get()
        if (status == Status.NOT_DOWNLOADED || status == Status.ERROR) {
            if (allModelsPresent(context)) {
                currentStatus.set(Status.DOWNLOADED)
                return Status.DOWNLOADED
            }
        }
        return status
    }

    fun getDownloadProgress(): Float = downloadProgress
    fun getLastError(): String? = lastError

    /**
     * Download all required model files asynchronously.
     */
    fun download(
        context: Context,
        onProgress: (Float) -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        if (!currentStatus.compareAndSet(Status.NOT_DOWNLOADED, Status.DOWNLOADING) &&
            !currentStatus.compareAndSet(Status.ERROR, Status.DOWNLOADING)
        ) {
            Log.w(tag, "Download already in progress or completed")
            return
        }

        lastError = null
        downloadProgress = 0f

        Thread(Runnable {
            try {
                val modelDir = getModelDir(context)
                modelDir.mkdirs()

                val files = modelFiles
                val totalFiles = files.size
                var completedFiles = 0

                for ((filename, url) in files) {
                    val targetFile = File(modelDir, filename)
                    val tempFile = File(modelDir, "$filename.tmp")

                    if (targetFile.exists() && targetFile.length() > 0) {
                        completedFiles++
                        downloadProgress = completedFiles.toFloat() / totalFiles
                        onProgress(downloadProgress)
                        continue
                    }

                    downloadFile(url, tempFile) { fileProgress ->
                        downloadProgress = (completedFiles + fileProgress) / totalFiles
                        onProgress(downloadProgress)
                    }

                    tempFile.renameTo(targetFile)
                    completedFiles++
                    downloadProgress = completedFiles.toFloat() / totalFiles
                    onProgress(downloadProgress)
                }

                File(modelDir, readyMarker).writeText("ok")
                currentStatus.set(Status.DOWNLOADED)
                onComplete(true)
                Log.i(tag, "All models downloaded successfully")
            } catch (e: Exception) {
                Log.e(tag, "Model download failed: ${e.message}", e)
                lastError = e.message
                currentStatus.set(Status.ERROR)
                onComplete(false)
            }
        }, tag).start()
    }

    fun deleteModels(context: Context) {
        val modelDir = getModelDir(context)
        modelDir.deleteRecursively()
        currentStatus.set(Status.NOT_DOWNLOADED)
        downloadProgress = 0f
        Log.i(tag, "Models deleted")
    }

    fun getModelDir(context: Context): File {
        return File(context.filesDir, modelDirName)
    }

    fun allModelsPresent(context: Context): Boolean {
        val modelDir = getModelDir(context)
        if (!modelDir.exists()) return false
        return modelFiles.keys.all { filename ->
            val file = File(modelDir, filename)
            file.exists() && file.length() > 0
        }
    }

    fun estimatedSizeMb(): Int = estimatedTotalSizeMb

    private fun downloadFile(
        urlStr: String,
        targetFile: File,
        onFileProgress: (Float) -> Unit,
    ) {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP $responseCode for $urlStr")
            }

            val contentLength = connection.contentLength.toLong()
            var totalRead = 0L
            val buffer = ByteArray(8192)

            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            onFileProgress((totalRead.toFloat() / contentLength).coerceAtMost(1f))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
