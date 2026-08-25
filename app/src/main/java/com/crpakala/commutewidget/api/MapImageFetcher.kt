package com.crpakala.commutewidget.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class MapImageFetcher(private val client: OkHttpClient = HttpClients.default) {
    suspend fun fetch(url: String, destFile: File): ApiResult<File> {
        val request = Request.Builder().url(url).get().build()

        return try {
            client.executeSuspend(request).use { response ->
                if (!response.isSuccessful) {
                    return@use ApiResult.Failure("Network error")
                }

                val contentType = response.header("Content-Type")
                if (contentType == null || !contentType.startsWith("image/")) {
                    return@use ApiResult.Failure("Invalid map image response")
                }

                val body = response.body

                withContext(Dispatchers.IO) {
                    writeAtomically(destFile, body.byteStream())
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure("Network error", e)
        }
    }

    private fun writeAtomically(destFile: File, input: java.io.InputStream): ApiResult<File> {
        val parentDir = destFile.absoluteFile.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            return ApiResult.Failure("Failed to save map image")
        }

        val tempFile = File(parentDir, "${destFile.name}.tmp")
        return try {
            tempFile.outputStream().use { output -> input.use { it.copyTo(output) } }
            if (tempFile.renameTo(destFile)) {
                ApiResult.Success(destFile)
            } else {
                tempFile.delete()
                ApiResult.Failure("Failed to save map image")
            }
        } catch (e: IOException) {
            tempFile.delete()
            ApiResult.Failure("Network error", e)
        }
    }
}
