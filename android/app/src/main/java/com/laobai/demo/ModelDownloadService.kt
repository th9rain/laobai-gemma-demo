package com.laobai.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private lateinit var notificationManager: NotificationManager
    @Volatile private var activeVariant: GemmaModelVariant? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "端侧模型下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "下载并校验老白使用的 Gemma 端侧模型"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested.set(true)
            val variant = activeVariant ?: GemmaModelRepository.selectedVariant(this)
            GemmaModelRepository.markProgress(
                this,
                variant,
                ModelInstallPhase.PAUSED,
                GemmaModelRepository.partialFile(this, variant).length(),
                "下载已暂停，点击下载可继续",
            )
            if (running.get()) {
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(variant, "正在安全暂停…", 0, true),
                )
            } else {
                activeDownload.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val variant = GemmaModelVariant.fromWireName(intent?.getStringExtra(EXTRA_VARIANT))
            ?: GemmaModelVariant.E4B
        startForeground(
            NOTIFICATION_ID,
            buildNotification(variant, "准备下载…", progress = 0, indeterminate = true),
        )
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY

        cancelRequested.set(false)
        activeVariant = variant
        activeDownload.set(true)
        GemmaModelRepository.markProgress(
            this,
            variant,
            ModelInstallPhase.DOWNLOADING,
            GemmaModelRepository.partialFile(this, variant).length(),
            "正在准备模型文件…",
        )
        executor.execute {
            var keepFinalNotification = false
            try {
                installModel(variant)
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(variant, "模型已下载并校验完成", 100, false),
                )
                keepFinalNotification = true
            } catch (_: DownloadCancelledException) {
                val downloaded = GemmaModelRepository.partialFile(this, variant).length()
                GemmaModelRepository.markProgress(
                    this,
                    variant,
                    ModelInstallPhase.PAUSED,
                    downloaded,
                    "下载已暂停，点击下载可继续",
                )
            } catch (error: Throwable) {
                val downloaded = GemmaModelRepository.partialFile(this, variant).length()
                GemmaModelRepository.markProgress(
                    this,
                    variant,
                    ModelInstallPhase.ERROR,
                    downloaded,
                    "模型安装失败：${readableError(error)}",
                )
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        variant,
                        "安装失败：${readableError(error)}",
                        progress = 0,
                        indeterminate = false,
                    ),
                )
                keepFinalNotification = true
            } finally {
                running.set(false)
                activeDownload.set(false)
                activeVariant = null
                stopForeground(
                    if (keepFinalNotification) {
                        STOP_FOREGROUND_DETACH
                    } else {
                        STOP_FOREGROUND_REMOVE
                    },
                )
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        cancelRequested.set(true)
        activeDownload.set(false)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelRequested.set(true)
        activeVariant?.let { variant ->
            GemmaModelRepository.markProgress(
                this,
                variant,
                ModelInstallPhase.PAUSED,
                GemmaModelRepository.partialFile(this, variant).length(),
                "系统结束了长时间下载，点击下载可继续",
            )
        }
        activeDownload.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun installModel(variant: GemmaModelVariant) {
        GemmaModelRepository.selectVariant(this, variant)
        val finalFile = GemmaModelRepository.modelFile(this, variant)
        val partialFile = GemmaModelRepository.partialFile(this, variant)

        if (GemmaModelRepository.isInstalled(this, variant)) {
            GemmaModelRepository.markInstalled(this, variant)
            return
        }

        if (partialFile.length() > variant.sizeBytes) {
            RandomAccessFile(partialFile, "rw").use { it.setLength(0L) }
        }
        val completeFinalFile = finalFile.isFile && finalFile.length() == variant.sizeBytes
        if (!completeFinalFile) {
            if (finalFile.exists() && !finalFile.delete()) {
                throw IllegalStateException("无法清理不完整的旧模型")
            }
            val remaining = (variant.sizeBytes - partialFile.length()).coerceAtLeast(0L)
            val safetyReserve = 512L * 1024L * 1024L
            if (GemmaModelRepository.availableStorageBytes(this) < remaining + safetyReserve) {
                throw IllegalStateException(
                    "存储空间不足，至少还需 ${GemmaModelRepository.formatBytes(remaining + safetyReserve)}",
                )
            }
            downloadChunks(variant, partialFile)
        }

        checkCancelled()
        val candidate = if (finalFile.isFile) finalFile else partialFile
        if (candidate.length() != variant.sizeBytes) {
            throw IllegalStateException(
                "模型大小不正确：${candidate.length()} / ${variant.sizeBytes}",
            )
        }

        GemmaModelRepository.markProgress(
            this,
            variant,
            ModelInstallPhase.VERIFYING,
            variant.sizeBytes,
            "正在校验 SHA-256…",
        )
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(variant, "正在校验 SHA-256…", 100, true),
        )
        val actualHash = sha256(candidate)
        if (!actualHash.equals(variant.sha256, ignoreCase = true)) {
            if (!candidate.delete()) {
                throw IllegalStateException("SHA-256 不匹配，且无法删除损坏文件")
            }
            throw IllegalStateException("SHA-256 不匹配，损坏文件已删除")
        }

        if (candidate != finalFile) {
            moveReplacing(candidate, finalFile)
        }
        GemmaModelRepository.markInstalled(this, variant)
    }

    private fun downloadChunks(variant: GemmaModelVariant, destination: File) {
        if (!destination.exists()) destination.createNewFile()
        var chunkBase = 0L
        for ((index, chunk) in variant.chunks.withIndex()) {
            checkCancelled()
            val currentLength = destination.length()
            val existingInChunk = (currentLength - chunkBase).coerceIn(0L, chunk.sizeBytes)
            if (existingInChunk < chunk.sizeBytes) {
                appendChunk(
                    variant = variant,
                    chunk = chunk,
                    chunkIndex = index,
                    chunkBase = chunkBase,
                    existingBytes = existingInChunk,
                    destination = destination,
                )
            }
            chunkBase += chunk.sizeBytes
        }
        if (destination.length() != variant.sizeBytes) {
            throw IllegalStateException("下载长度不完整：${destination.length()} / ${variant.sizeBytes}")
        }
    }

    private fun appendChunk(
        variant: GemmaModelVariant,
        chunk: ModelChunk,
        chunkIndex: Int,
        chunkBase: Long,
        existingBytes: Long,
        destination: File,
    ) {
        var offset = existingBytes
        var restartedAfterIgnoredRange = false
        while (offset < chunk.sizeBytes) {
            checkCancelled()
            val connection = openFollowingRedirects(chunk.url, offset)
            try {
                val code = connection.responseCode
                if (offset > 0L && code == HttpURLConnection.HTTP_OK) {
                    if (restartedAfterIgnoredRange) {
                        throw IllegalStateException("服务器不支持断点续传")
                    }
                    RandomAccessFile(destination, "rw").use { it.setLength(chunkBase) }
                    offset = 0L
                    restartedAfterIgnoredRange = true
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IllegalStateException("下载服务器返回 HTTP $code")
                }
                if (offset > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IllegalStateException("断点续传响应无效")
                }
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    validateContentRange(connection, offset, chunk.sizeBytes)
                }

                RandomAccessFile(destination, "rw").use { output ->
                    output.seek(chunkBase + offset)
                    BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var lastPublishAt = 0L
                        while (offset < chunk.sizeBytes) {
                            checkCancelled()
                            val wanted = minOf(buffer.size.toLong(), chunk.sizeBytes - offset).toInt()
                            val count = input.read(buffer, 0, wanted)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            offset += count

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastPublishAt >= PROGRESS_INTERVAL_MS) {
                                publishProgress(
                                    variant,
                                    chunkBase + offset,
                                    "正在下载第 ${chunkIndex + 1}/${variant.chunks.size} 个分片",
                                )
                                lastPublishAt = now
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (offset < chunk.sizeBytes) {
                throw IllegalStateException("网络连接提前结束，可再次点击继续下载")
            }
        }
        publishProgress(
            variant,
            chunkBase + chunk.sizeBytes,
            "第 ${chunkIndex + 1}/${variant.chunks.size} 个分片已完成",
        )
    }

    private fun openFollowingRedirects(sourceUrl: String, offset: Long): HttpURLConnection {
        var current = URL(sourceUrl)
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LaoBai-Android/0.2")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            if (code !in REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location")
                ?: throw IllegalStateException("下载重定向缺少地址")
            connection.disconnect()
            current = URL(current, location)
        }
        throw IllegalStateException("下载重定向次数过多")
    }

    private fun validateContentRange(
        connection: HttpURLConnection,
        expectedStart: Long,
        expectedTotal: Long,
    ) {
        val value = connection.getHeaderField("Content-Range")
            ?: throw IllegalStateException("断点响应缺少 Content-Range")
        val match = CONTENT_RANGE.matchEntire(value.trim())
            ?: throw IllegalStateException("Content-Range 格式无效")
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        if (
            start != expectedStart ||
            end == null || end < expectedStart || end >= expectedTotal ||
            total != expectedTotal
        ) {
            throw IllegalStateException("Content-Range 与请求位置不一致")
        }
    }

    private fun publishProgress(
        variant: GemmaModelVariant,
        downloadedBytes: Long,
        message: String,
    ) {
        GemmaModelRepository.markProgress(
            this,
            variant,
            ModelInstallPhase.DOWNLOADING,
            downloadedBytes,
            message,
        )
        val percent = ((downloadedBytes.coerceIn(0L, variant.sizeBytes) * 100L) /
            variant.sizeBytes).toInt()
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(variant, "$message · $percent%", percent, false),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrThrow()
    }

    private fun buildNotification(
        variant: GemmaModelVariant,
        message: String,
        progress: Int,
        indeterminate: Boolean,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_laobai)
            .setContentTitle("${variant.displayName} 模型")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(progress in 0..99 || indeterminate)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .addAction(Notification.Action.Builder(null, "暂停", cancelIntent).build())
            .build()
    }

    private fun checkCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted) {
            throw DownloadCancelledException()
        }
    }

    private fun readableError(error: Throwable): String =
        error.message?.take(160) ?: error.javaClass.simpleName

    companion object {
        private const val ACTION_DOWNLOAD = "com.laobai.demo.action.DOWNLOAD_MODEL"
        private const val ACTION_CANCEL = "com.laobai.demo.action.CANCEL_MODEL_DOWNLOAD"
        private const val EXTRA_VARIANT = "com.laobai.demo.extra.MODEL_VARIANT"
        private const val CHANNEL_ID = "gemma_model_download"
        private const val NOTIFICATION_ID = 4201
        private const val BUFFER_SIZE = 1024 * 1024
        private const val HASH_BUFFER_SIZE = 4 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MAX_REDIRECTS = 8
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        private val activeDownload = AtomicBoolean(false)

        fun isActive(): Boolean = activeDownload.get()

        fun start(context: Context, variant: GemmaModelVariant) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_VARIANT, variant.wireName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}

private class DownloadCancelledException : RuntimeException()
