package com.laobai.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class GemmaRuntimeMode {
    MULTIMODAL_GPU,
    MULTIMODAL_CPU,
    TEXT_ONLY_CPU,
}

data class GemmaInferenceResult(
    val variant: GemmaModelVariant,
    val mode: GemmaRuntimeMode,
    val output: String,
    val elapsedMs: Long,
)

object GemmaRuntime {
    private const val TAG = "LaoBaiGemma"
    private const val MAX_NUM_TOKENS = 1_536
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var cachedVariant: GemmaModelVariant? = null
    private var cachedEngine: InitializedEngine? = null

    fun isRunning(): Boolean = running.get()

    fun runTextSelfTest(
        context: Context,
        callback: (Result<GemmaInferenceResult>) -> Unit,
    ) {
        val prompt = """
            你是运行在安卓手机端的老白 Gemma 模型。
            这是一次真实端侧推理自检。只回复一行：LAOBAI_GEMMA_READY
        """.trimIndent()
        runOnce(context, prompt, imageFile = null, callback)
    }

    fun runVisionOnce(
        context: Context,
        screenshot: File,
        prompt: String,
        callback: (Result<GemmaInferenceResult>) -> Unit,
    ) {
        require(screenshot.isFile) { "截图文件不存在：${screenshot.absolutePath}" }
        runOnce(context, prompt, screenshot, callback)
    }

    fun closeSession() {
        executor.execute { closeCachedEngine() }
    }

    private fun runOnce(
        context: Context,
        prompt: String,
        imageFile: File?,
        callback: (Result<GemmaInferenceResult>) -> Unit,
    ) {
        val appContext = context.applicationContext
        val variant = GemmaModelRepository.installedSelectedVariant(appContext)
        if (variant == null) {
            deliver(callback, Result.failure(IllegalStateException("请先下载并校验 Gemma 模型")))
            return
        }
        if (!running.compareAndSet(false, true)) {
            deliver(callback, Result.failure(IllegalStateException("模型正在执行另一项任务")))
            return
        }

        executor.execute {
            var result = runCatching {
                val modelFile = GemmaModelRepository.modelFile(appContext, variant)
                val startedAt = SystemClock.elapsedRealtime()
                Engine.setNativeMinLogSeverity(LogSeverity.WARNING)
                val initialized = acquireEngine(
                    modelFile = modelFile,
                    variant = variant,
                    requiresVision = imageFile != null,
                )
                val config = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = 1,
                        topP = 1.0,
                        temperature = 0.0,
                        seed = 7,
                    ),
                    channels = emptyList(),
                )
                val response = initialized.engine.createConversation(config).use { conversation ->
                    val contents = if (imageFile == null) {
                        Contents.of(Content.Text(prompt))
                    } else {
                        // LiteRT-LM expects text before media for multimodal requests.
                        Contents.of(
                            Content.Text(prompt),
                            Content.ImageFile(imageFile.absolutePath),
                        )
                    }
                    conversation.sendMessage(contents).toString().trim()
                }
                if (response.isBlank()) throw IllegalStateException("模型返回了空结果")
                GemmaInferenceResult(
                    variant = variant,
                    mode = initialized.mode,
                    output = response,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            val runtimeError = result.exceptionOrNull()
            if (runtimeError is OutOfMemoryError) {
                closeCachedEngine()
                result = Result.failure(memoryError(runtimeError))
            }
            running.set(false)
            deliver(callback, result)
        }
    }

    private fun acquireEngine(
        modelFile: File,
        variant: GemmaModelVariant,
        requiresVision: Boolean,
    ): InitializedEngine {
        val existing = cachedEngine
        if (
            existing != null &&
            cachedVariant == variant &&
            existing.engine.isInitialized() &&
            (!requiresVision || existing.mode != GemmaRuntimeMode.TEXT_ONLY_CPU)
        ) {
            return existing
        }
        closeCachedEngine()
        return initializeWithFallback(modelFile, requiresVision).also { initialized ->
            cachedVariant = variant
            cachedEngine = initialized
        }
    }

    private fun initializeWithFallback(
        modelFile: File,
        requiresVision: Boolean,
    ): InitializedEngine {
        val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val gpuConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            visionBackend = Backend.GPU(),
            audioBackend = Backend.CPU(threadCount = cpuThreads),
            maxNumTokens = MAX_NUM_TOKENS,
            maxNumImages = 1,
            cacheDir = ":nocache",
        )
        tryInitialize(gpuConfig)?.let {
            return InitializedEngine(it, GemmaRuntimeMode.MULTIMODAL_GPU)
        }

        val cpuConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(threadCount = cpuThreads),
            visionBackend = Backend.CPU(threadCount = cpuThreads),
            audioBackend = Backend.CPU(threadCount = cpuThreads),
            maxNumTokens = MAX_NUM_TOKENS,
            maxNumImages = 1,
            cacheDir = ":nocache",
        )
        tryInitialize(cpuConfig)?.let {
            return InitializedEngine(it, GemmaRuntimeMode.MULTIMODAL_CPU)
        }

        if (requiresVision) {
            throw IllegalStateException("GPU 与 CPU 多模态后端均无法读取当前模型或截图")
        }

        val textConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(threadCount = cpuThreads),
            maxNumTokens = MAX_NUM_TOKENS,
            cacheDir = ":nocache",
        )
        val textEngine = Engine(textConfig)
        try {
            textEngine.initialize()
        } catch (error: OutOfMemoryError) {
            if (textEngine.isInitialized()) runCatching(textEngine::close)
            throw memoryError(error)
        } catch (error: Exception) {
            if (textEngine.isInitialized()) runCatching(textEngine::close)
            throw IllegalStateException("GPU、CPU 多模态及文本后端均无法加载模型", error)
        } catch (error: LinkageError) {
            if (textEngine.isInitialized()) runCatching(textEngine::close)
            throw IllegalStateException("GPU、CPU 多模态及文本后端均无法加载模型", error)
        }
        return InitializedEngine(textEngine, GemmaRuntimeMode.TEXT_ONLY_CPU)
    }

    private fun tryInitialize(config: EngineConfig): Engine? {
        val engine = Engine(config)
        return try {
            engine.initialize()
            engine
        } catch (error: OutOfMemoryError) {
            if (engine.isInitialized()) runCatching(engine::close)
            throw memoryError(error)
        } catch (error: Exception) {
            Log.w(TAG, "Backend initialization failed: ${config.backend.name}", error)
            if (engine.isInitialized()) runCatching(engine::close)
            null
        } catch (error: LinkageError) {
            Log.w(TAG, "Backend initialization failed: ${config.backend.name}", error)
            if (engine.isInitialized()) runCatching(engine::close)
            null
        }
    }

    private fun closeCachedEngine() {
        val initialized = cachedEngine
        cachedEngine = null
        cachedVariant = null
        if (initialized != null) {
            runCatching {
                if (initialized.engine.isInitialized()) initialized.engine.close()
            }.onFailure { Log.w(TAG, "Failed to close cached engine", it) }
        }
    }

    private fun memoryError(error: OutOfMemoryError): IllegalStateException =
        IllegalStateException("端侧模型内存不足，请清理后台应用或切换到 E2B 兼容版", error)

    private fun deliver(
        callback: (Result<GemmaInferenceResult>) -> Unit,
        result: Result<GemmaInferenceResult>,
    ) {
        mainHandler.post { callback(result) }
    }

    private data class InitializedEngine(
        val engine: Engine,
        val mode: GemmaRuntimeMode,
    )
}
