package com.ketch.internal.worker

import android.content.Context
import android.media.MediaScannerConnection
import android.webkit.MimeTypeMap
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ketch.Status
import com.ketch.internal.database.DatabaseInstance
import com.ketch.internal.download.ApiResponseHeaderChecker
import com.ketch.internal.download.DownloadTask
import com.ketch.internal.network.RetrofitInstance
import com.ketch.internal.notification.DownloadNotificationManager
import com.ketch.internal.utils.DownloadConst
import com.ketch.internal.utils.ExceptionConst
import com.ketch.internal.utils.FileUtil
import com.ketch.internal.utils.UserAction
import com.ketch.internal.utils.WorkUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

internal class DownloadWorker(
    private val context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    companion object {
        private const val MAX_PERCENT = 100
    }

    private var downloadNotificationManager: DownloadNotificationManager? = null
    private val downloadDao = DatabaseInstance.getInstance(context).downloadDao()

    override suspend fun doWork(): Result {
        val id = inputData.getInt(DownloadConst.KEY_DOWNLOAD_ID, -1)
        if (id == -1) {
            return Result.failure(
                workDataOf(
                    ExceptionConst.KEY_EXCEPTION to "Invalid download ID"
                )
            )
        }

        var downloadEntity = downloadDao.find(id)
            ?: return Result.failure(
                workDataOf(
                    ExceptionConst.KEY_EXCEPTION to "Download not found in database"
                )
            )

        val notificationConfig = WorkUtil.jsonToNotificationConfig(
            inputData.getString(DownloadConst.KEY_NOTIFICATION_CONFIG) ?: ""
        )

        val url = downloadEntity.url
        val dirPath = downloadEntity.path
        val fileName = downloadEntity.fileName
        val headers = WorkUtil.jsonToHashMap(downloadEntity.headersJson)
        val chunkedUrls = WorkUtil.jsonToList(downloadEntity.chunkedUrlsJson)
        val supportPauseResume = chunkedUrls.isNullOrEmpty()

        if (notificationConfig.enabled) {
            downloadNotificationManager = DownloadNotificationManager(
                context = context,
                notificationConfig = notificationConfig,
                requestId = id,
                fileName = fileName
            )
        }

        val downloadService = RetrofitInstance.getDownloadService()

        return try {
            downloadNotificationManager?.sendUpdateNotification()?.let { setForeground(it) }

            // ---- STEP 1: ETag + File handling ----
            if (chunkedUrls.isNullOrEmpty()) {
                val latestETag = ApiResponseHeaderChecker(url, downloadService, headers)
                    .getHeaderValue(DownloadConst.ETAG_HEADER) ?: ""

                if (latestETag != downloadEntity.eTag) {
                    FileUtil.deleteFileIfExists(dirPath, fileName)
                    FileUtil.createTempFileIfNotExists(dirPath, fileName)
                    downloadDao.update(
                        downloadEntity.copy(
                            eTag = latestETag,
                            lastModified = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                FileUtil.deleteFileIfExists(dirPath, fileName)
                FileUtil.createTempFileIfNotExists(dirPath, fileName)
            }

            // ---- STEP 2: Initialize Task ----
            val downloadTask = DownloadTask(
                url = url,
                path = dirPath,
                fileName = fileName,
                supportPauseResume = supportPauseResume,
                downloadService = downloadService,
                chunkedUrls = chunkedUrls,
                totalBytesFromDb = downloadEntity.totalBytes
            )

            // ---- STEP 3: Start Download ----
            var progressPercentage = -1

            val totalLength = downloadTask.download(
                headers = headers,
                onStart = { _ ->
                    downloadEntity = downloadDao.find(id) ?: return@download
                    downloadDao.update(
                        downloadEntity.copy(
                            totalBytes = downloadEntity.totalBytes,
                            status = Status.STARTED.toString(),
                            lastModified = System.currentTimeMillis()
                        )
                    )
                    setProgress(
                        workDataOf(
                            DownloadConst.KEY_STATE to DownloadConst.STARTED
                        )
                    )
                },
                onProgress = { downloadedBytes, totalBytes, speed ->
                    downloadEntity = downloadDao.find(id) ?: return@download
                    val effectiveTotalBytes =
                        if (totalBytes > 0) totalBytes else downloadEntity.totalBytes

                    val progress = if (effectiveTotalBytes > 0) {
                        ((downloadedBytes * 100) / effectiveTotalBytes)
                            .toInt()
                            .coerceIn(0, MAX_PERCENT)
                    } else {
                        0
                    }

                    if (progressPercentage != progress) {
                        progressPercentage = progress
                        downloadDao.update(
                            downloadEntity.copy(
                                downloadedBytes = downloadedBytes,
                                speedInBytePerMs = speed,
                                status = Status.PROGRESS.toString(),
                                lastModified = System.currentTimeMillis()
                            )
                        )
                    }

                    setProgress(
                        workDataOf(
                            DownloadConst.KEY_STATE to DownloadConst.PROGRESS,
                            DownloadConst.KEY_PROGRESS to progress
                        )
                    )

                    downloadNotificationManager?.sendUpdateNotification(
                        progress = progress,
                        speedInBPerMs = speed,
                        length = effectiveTotalBytes,
                        update = true
                    )?.let { setForeground(it) }
                }
            )

            // ---- STEP 4: Success ----
            val finalEntity = downloadDao.find(id) ?: return Result.failure()
            val finalTotal = if (totalLength > 0) totalLength else finalEntity.totalBytes

            downloadDao.update(
                finalEntity.copy(
                    totalBytes = finalTotal,
                    downloadedBytes = finalTotal,
                    status = Status.SUCCESS.toString(),
                    lastModified = System.currentTimeMillis()
                )
            )

            val file = File(dirPath, fileName)
            val totalForNotification =
                if (finalTotal > 0) finalTotal else file.length()

            downloadNotificationManager?.sendDownloadSuccessNotification(
                totalLength = totalForNotification
            )

            // 👉 Scan file so it appears in MediaStore (Gallery / playlists)
            scanMediaFile(context, file)

            Result.success()

        } catch (e: Exception) {

            withContext(NonCancellable) {
                val entity = downloadDao.find(id)
                    ?: return@withContext

                val isChunked = !chunkedUrls.isNullOrEmpty()
                val userPaused = entity.userAction == UserAction.PAUSE.toString()

                when (e) {
                    is CancellationException -> {
                        if (userPaused) {
                            if (isChunked) {
                                // Pause → cancel for chunked downloads
                                downloadDao.update(
                                    entity.copy(
                                        status = Status.CANCELLED.toString(),
                                        lastModified = System.currentTimeMillis()
                                    )
                                )
                                FileUtil.deleteFileIfExists(entity.path, entity.fileName)

                                // ✅ cleanup split audio/video artifacts too
                                deleteSplitAvArtifacts(entity.path, entity.fileName)

                                downloadNotificationManager?.sendDownloadCancelledNotification()
                            } else {
                                // Proper pause for single file
                                downloadDao.update(
                                    entity.copy(
                                        status = Status.PAUSED.toString(),
                                        lastModified = System.currentTimeMillis()
                                    )
                                )

                                val currentProgress = if (entity.totalBytes > 0) {
                                    ((entity.downloadedBytes * MAX_PERCENT) / entity.totalBytes)
                                        .toInt()
                                } else {
                                    0
                                }

                                downloadNotificationManager
                                    ?.sendDownloadPausedNotification(currentProgress)
                            }
                        } else {
                            // User cancelled or system killed
                            downloadDao.update(
                                entity.copy(
                                    status = Status.CANCELLED.toString(),
                                    lastModified = System.currentTimeMillis()
                                )
                            )
                            FileUtil.deleteFileIfExists(entity.path, entity.fileName)

                            // ✅ cleanup split audio/video artifacts too
                            deleteSplitAvArtifacts(entity.path, entity.fileName)

                            downloadNotificationManager?.sendDownloadCancelledNotification()
                        }
                    }

                    else -> {
                        downloadDao.update(
                            entity.copy(
                                status = Status.FAILED.toString(),
                                failureReason = e.message ?: "",
                                lastModified = System.currentTimeMillis()
                            )
                        )

                        val currentProgress = if (entity.totalBytes > 0) {
                            ((entity.downloadedBytes * MAX_PERCENT) / entity.totalBytes)
                                .toInt()
                        } else {
                            0
                        }

                        downloadNotificationManager
                            ?.sendDownloadFailedNotification(currentProgress)
                    }
                }
            }

            Result.failure(
                workDataOf(ExceptionConst.KEY_EXCEPTION to (e.message ?: "Unknown error"))
            )
        }
    }

    /**
     * ✅ FIXED:
     * DownloadTask creates:
     * - <fileName>.ketch_video.tmp
     * - <fileName>.ketch_audio.tmp
     *
     * So cleanup must delete THESE exact files.
     */
    private fun deleteSplitAvArtifacts(dirPath: String, fileName: String) {
        // DownloadTask now creates:
        // "<fileName>.ketch_video.ts"
        // "<fileName>.ketch_audio.ts"
        val videoTs = File(dirPath, "$fileName.ketch_video.ts")
        val audioTs = File(dirPath, "$fileName.ketch_audio.ts")

        if (videoTs.exists()) videoTs.delete()
        if (audioTs.exists()) audioTs.delete()
    }


    /**
     * Scan the downloaded media file so it appears in MediaStore
     * (Gallery, video players, your playlist query, etc).
     */
    private fun scanMediaFile(context: Context, file: File) {
        if (!file.exists()) return

        val path = file.absolutePath
        val mimeType = getMimeTypeFromPath(path)

        MediaScannerConnection.scanFile(
            context,
            arrayOf(path),
            arrayOf(mimeType),
            null
        )
    }

    private fun getMimeTypeFromPath(path: String): String {
        val extension = path.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}
