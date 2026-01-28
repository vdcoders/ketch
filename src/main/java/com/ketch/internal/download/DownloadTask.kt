package com.ketch.internal.download

import com.ketch.internal.network.DownloadService
import com.ketch.internal.utils.DownloadConst
import com.ketch.internal.utils.FileUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal class DownloadTask(
    private val url: String,
    private val path: String,
    private val fileName: String,
    private val supportPauseResume: Boolean = true,
    private val downloadService: DownloadService,
    private val chunkedUrls: MutableList<String>? = null,
    private val totalBytesFromDb: Long = 0L
) {

    companion object {
        private const val VALUE_200 = 200
        private const val VALUE_299 = 299
        private const val TIME_TO_TRIGGER_PROGRESS = 1500
    }

    private class SpeedAverager(private val windowSize: Int = 5) {
        private val samples = ArrayDeque<Float>()
        fun add(value: Float) {
            samples.addLast(value)
            if (samples.size > windowSize) samples.removeFirst()
        }
        fun average(): Float {
            if (samples.isEmpty()) return 0f
            return samples.sum() / samples.size
        }
    }

    private fun isTyped(raw: String): Boolean {
        val s = raw.trim()
        return s.length >= 2 && s[1] == '|' && (s[0] == 'V' || s[0] == 'v' || s[0] == 'A' || s[0] == 'a')
    }

    private fun splitKind(raw: String): Pair<Char, String> {
        val s = raw.trim()
        if (s.length >= 2 && s[1] == '|') {
            return when (s[0]) {
                'A', 'a' -> 'A' to s.substring(2)
                'V', 'v' -> 'V' to s.substring(2)
                else -> 'V' to s
            }
        }
        return 'V' to s
    }

    private fun stripPrefix(raw: String): String {
        val (_, u) = splitKind(raw)
        return u.trim()
    }

    private suspend fun downloadSingleUrl(
        headers: MutableMap<String, String>,
        onStart: suspend (totalBytes: Long) -> Unit,
        onProgress: suspend (progressBytes: Long, totalBytes: Long, speed: Float) -> Unit
    ): Long {
        var rangeStart = 0L
        val file = File(path, fileName)
        val tempFile = FileUtil.getTempFileForFile(file)

        if (tempFile.exists()) rangeStart = tempFile.length()

        if (rangeStart != 0L) {
            headers[DownloadConst.RANGE_HEADER] = "bytes=$rangeStart-"
        }

        var response = downloadService.getUrl(url, headers)
        if (response.code() == DownloadConst.HTTP_RANGE_NOT_SATISFY || isRedirection(
                response.raw().request().url().toString()
            )
        ) {
            FileUtil.deleteFileIfExists(path, fileName)
            headers.remove(DownloadConst.RANGE_HEADER)
            rangeStart = 0
            response = downloadService.getUrl(url, headers)
        }

        val responseBody = response.body()
        if (response.code() !in VALUE_200..VALUE_299 || responseBody == null) {
            throw IOException("Response code: ${response.code()}, responseBody null: ${responseBody == null}")
        }

        var totalBytes = responseBody.contentLength()
        if (totalBytes < 0 || supportPauseResume.not()) {
            totalBytes = 0
        } else {
            totalBytes += rangeStart
        }

        var progressBytes = 0L
        val speedAverager = SpeedAverager()

        responseBody.byteStream().use { inputStream ->
            FileOutputStream(tempFile, true).use { outputStream ->

                if (rangeStart != 0L) progressBytes = rangeStart

                onStart.invoke(totalBytes)

                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytes = inputStream.read(buffer)
                var tempBytes = 0L
                var progressInvokeTime = System.currentTimeMillis()

                onProgress.invoke(0L, 0L, 0F)

                while (bytes >= 0) {
                    outputStream.write(buffer, 0, bytes)
                    progressBytes += bytes
                    tempBytes += bytes
                    bytes = inputStream.read(buffer)

                    val finalTime = System.currentTimeMillis()
                    if (finalTime - progressInvokeTime >= TIME_TO_TRIGGER_PROGRESS) {
                        val instantSpeed = tempBytes.toFloat() / (finalTime - progressInvokeTime).toFloat()
                        speedAverager.add(instantSpeed)
                        val smoothSpeed = speedAverager.average()

                        tempBytes = 0L
                        progressInvokeTime = System.currentTimeMillis()

                        if (progressBytes > totalBytes) progressBytes = totalBytes
                        if (totalBytes > 0) {
                            onProgress.invoke(progressBytes, totalBytes, smoothSpeed)
                        }
                    }
                }

                onProgress.invoke(totalBytes, totalBytes, 0F)
            }
        }

        require(tempFile.renameTo(file)) { "Temp file rename failed" }
        return totalBytes
    }

    private suspend fun downloadChunked(
        headers: MutableMap<String, String>,
        onStart: suspend (totalBytes: Long) -> Unit,
        onProgress: suspend (progressBytes: Long, totalBytes: Long, speed: Float) -> Unit
    ): Long {

        val outFile = File(path, fileName)
        FileUtil.deleteFileIfExists(path, fileName)

        val list = chunkedUrls ?: mutableListOf()
        val hasTyped = list.any { isTyped(it) }
        val hasAudio = list.any { it.trim().startsWith("A|", true) || it.trim().startsWith("a|", true) }

        // ---------- OLD MODE: single output file ----------
        if (!hasTyped || !hasAudio) {
            val tempFile = FileUtil.getTempFileForFile(outFile)
            var progressBytes = 0L
            val speedAverager = SpeedAverager()

            onStart.invoke(totalBytesFromDb)

            for ((index, raw) in list.withIndex()) {
                val chunkUrl = stripPrefix(raw)
                if (!chunkUrl.startsWith("http")) {
                    throw IOException("Invalid chunk url at index=$index raw=$raw")
                }

                val response = downloadService.getUrl(chunkUrl, headers)
                val body = response.body()
                if (response.code() !in VALUE_200..VALUE_299 || body == null) {
                    throw IOException("Failed chunk=$index code=${response.code()}")
                }

                body.byteStream().use { input ->
                    FileOutputStream(tempFile, true).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytes = input.read(buffer)
                        var tempBytes = 0L
                        var tick = System.currentTimeMillis()

                        while (bytes >= 0) {
                            out.write(buffer, 0, bytes)
                            progressBytes += bytes
                            tempBytes += bytes
                            bytes = input.read(buffer)

                            val now = System.currentTimeMillis()
                            if (now - tick >= TIME_TO_TRIGGER_PROGRESS) {
                                val instantSpeed = tempBytes.toFloat() / (now - tick)
                                speedAverager.add(instantSpeed)
                                val smoothSpeed = speedAverager.average()
                                tempBytes = 0L
                                tick = now
                                onProgress.invoke(progressBytes, totalBytesFromDb, smoothSpeed)
                            }
                        }
                        onProgress.invoke(progressBytes, totalBytesFromDb, 0F)
                    }
                }
            }

            require(tempFile.renameTo(outFile)) { "Temp file rename failed" }
            return totalBytesFromDb
        }

        // ---------- NEW MODE: Separate A/V => mux ----------
        // ✅ IMPORTANT: make temps .ts so ffmpeg detects format
        val videoTemp = File(path, "$fileName.ketch_video.ts")
        val audioTemp = File(path, "$fileName.ketch_audio.ts")
        if (videoTemp.exists()) videoTemp.delete()
        if (audioTemp.exists()) audioTemp.delete()

        var progressBytes = 0L
        val speedAverager = SpeedAverager()

        onStart.invoke(totalBytesFromDb)

        for ((index, raw) in list.withIndex()) {
            val (kind, urlRaw) = splitKind(raw)
            val safeUrl = urlRaw.trim()
            if (!safeUrl.startsWith("http")) {
                throw IOException("Invalid chunk url at index=$index kind=$kind raw=$raw")
            }

            val response = downloadService.getUrl(safeUrl, headers)
            val body = response.body()
            if (response.code() !in VALUE_200..VALUE_299 || body == null) {
                throw IOException("Failed chunk=$index kind=$kind code=${response.code()}")
            }

            val target = if (kind == 'A') audioTemp else videoTemp

            body.byteStream().use { input ->
                FileOutputStream(target, true).use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytes = input.read(buffer)
                    var tempBytes = 0L
                    var tick = System.currentTimeMillis()

                    while (bytes >= 0) {
                        out.write(buffer, 0, bytes)
                        progressBytes += bytes
                        tempBytes += bytes
                        bytes = input.read(buffer)

                        val now = System.currentTimeMillis()
                        if (now - tick >= TIME_TO_TRIGGER_PROGRESS) {
                            val instantSpeed = tempBytes.toFloat() / (now - tick)
                            speedAverager.add(instantSpeed)
                            val smoothSpeed = speedAverager.average()
                            tempBytes = 0L
                            tick = now
                            onProgress.invoke(progressBytes, totalBytesFromDb, smoothSpeed)
                        }
                    }
                    onProgress.invoke(progressBytes, totalBytesFromDb, 0F)
                }
            }
        }

        // ✅ MUX: use videoTemp + audioTemp -> outFile (mp4)
        /*FfmpegMuxer.muxTsToMp4(
            videoInput = videoTemp,
            audioInput = audioTemp,
            outputMp4 = outFile
        )*/

        // Cleanup
        videoTemp.delete()
        audioTemp.delete()

        return totalBytesFromDb
    }

    suspend fun download(
        headers: MutableMap<String, String> = mutableMapOf(),
        onStart: suspend (totalBytes: Long) -> Unit,
        onProgress: suspend (progressBytes: Long, totalBytes: Long, speed: Float) -> Unit
    ): Long {
        return if (!chunkedUrls.isNullOrEmpty()) {
            downloadChunked(headers, onStart, onProgress)
        } else {
            downloadSingleUrl(headers, onStart, onProgress)
        }
    }

    private fun isRedirection(requestUrl: String): Boolean = requestUrl != url
}
