package com.laobai.demo

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

data class ModelChunk(
    val url: String,
    val sizeBytes: Long,
)

enum class GemmaModelVariant(
    val wireName: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val chunks: List<ModelChunk>,
) {
    E4B(
        wireName = "e4b",
        displayName = "Gemma 4 E4B（高质量）",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        chunks = listOf(
            ModelChunk(
                url = "https://github.com/th9rain/laobai-gemma-demo/releases/download/model-weights-v1/gemma-4-E4B-it.litertlm.part01",
                sizeBytes = 1_900_000_000L,
            ),
            ModelChunk(
                url = "https://github.com/th9rain/laobai-gemma-demo/releases/download/model-weights-v1/gemma-4-E4B-it.litertlm.part02",
                sizeBytes = 1_759_530_240L,
            ),
        ),
    ),
    E2B(
        wireName = "e2b",
        displayName = "Gemma 4 E2B（兼容版）",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        chunks = listOf(
            ModelChunk(
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
                sizeBytes = 2_588_147_712L,
            ),
        ),
    );

    companion object {
        fun fromWireName(value: String?): GemmaModelVariant? =
            entries.firstOrNull { it.wireName == value }
    }
}

enum class ModelInstallPhase {
    IDLE,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    PAUSED,
    ERROR,
}

data class ModelInstallSnapshot(
    val variant: GemmaModelVariant,
    val phase: ModelInstallPhase,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val message: String,
    val installed: Boolean,
) {
    val progressPercent: Int
        get() = if (totalBytes <= 0L) 0 else {
            ((downloadedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
        }
}

object GemmaModelRepository {
    private const val PREFS = "gemma_model_state"
    private const val KEY_SELECTED = "selected_variant"
    private const val KEY_ACTIVE = "active_variant"
    private const val KEY_PHASE = "install_phase"
    private const val KEY_DOWNLOADED = "downloaded_bytes"
    private const val KEY_MESSAGE = "install_message"
    private const val KEY_VERIFIED_PREFIX = "verified_sha256_"

    fun modelDirectory(context: Context): File {
        val external = context.getExternalFilesDir("models")
        val directory = external ?: File(context.filesDir, "models")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建模型目录：${directory.absolutePath}")
        }
        return directory
    }

    fun modelFile(context: Context, variant: GemmaModelVariant): File =
        File(modelDirectory(context), variant.fileName)

    fun partialFile(context: Context, variant: GemmaModelVariant): File =
        File(modelDirectory(context), "${variant.fileName}.download")

    fun isInstalled(context: Context, variant: GemmaModelVariant): Boolean {
        val file = modelFile(context, variant)
        if (!file.isFile || file.length() != variant.sizeBytes) return false
        val verified = preferences(context)
            .getString(KEY_VERIFIED_PREFIX + variant.wireName, null)
        return verified.equals(variant.sha256, ignoreCase = true)
    }

    fun selectedVariant(context: Context): GemmaModelVariant {
        val selected = GemmaModelVariant.fromWireName(
            preferences(context).getString(KEY_SELECTED, null),
        )
        return selected ?: recommendedVariant(context)
    }

    fun selectVariant(context: Context, variant: GemmaModelVariant) {
        preferences(context).edit()
            .putString(KEY_SELECTED, variant.wireName)
            .putString(KEY_ACTIVE, variant.wireName)
            .apply()
    }

    fun installedSelectedVariant(context: Context): GemmaModelVariant? {
        val selected = selectedVariant(context)
        if (isInstalled(context, selected)) return selected
        return GemmaModelVariant.entries.firstOrNull { isInstalled(context, it) }
    }

    fun recommendedVariant(context: Context): GemmaModelVariant =
        if (totalMemoryBytes(context) >= 11_000_000_000L) {
            GemmaModelVariant.E4B
        } else {
            GemmaModelVariant.E2B
        }

    fun snapshot(context: Context): ModelInstallSnapshot {
        val prefs = preferences(context)
        val active = GemmaModelVariant.fromWireName(prefs.getString(KEY_ACTIVE, null))
        val selected = active ?: selectedVariant(context)
        val installed = isInstalled(context, selected)
        val storedPhase = runCatching {
            ModelInstallPhase.valueOf(prefs.getString(KEY_PHASE, ModelInstallPhase.IDLE.name).orEmpty())
        }.getOrDefault(ModelInstallPhase.IDLE)
        val phase = when {
            installed && storedPhase !in setOf(
                ModelInstallPhase.DOWNLOADING,
                ModelInstallPhase.VERIFYING,
            ) -> ModelInstallPhase.INSTALLED
            storedPhase in setOf(
                ModelInstallPhase.DOWNLOADING,
                ModelInstallPhase.VERIFYING,
            ) && !ModelDownloadService.isActive() -> ModelInstallPhase.PAUSED
            else -> storedPhase
        }
        val partialBytes = partialFile(context, selected).length().coerceAtMost(selected.sizeBytes)
        val downloaded = when {
            installed -> selected.sizeBytes
            phase == ModelInstallPhase.DOWNLOADING -> maxOf(
                prefs.getLong(KEY_DOWNLOADED, 0L),
                partialBytes,
            )
            else -> partialBytes
        }
        return ModelInstallSnapshot(
            variant = selected,
            phase = phase,
            downloadedBytes = downloaded,
            totalBytes = selected.sizeBytes,
            message = if (
                phase == ModelInstallPhase.PAUSED &&
                storedPhase in setOf(ModelInstallPhase.DOWNLOADING, ModelInstallPhase.VERIFYING)
            ) {
                "上次任务已中断，点击下载可继续"
            } else {
                prefs.getString(KEY_MESSAGE, "尚未安装端侧模型").orEmpty()
            },
            installed = installed,
        )
    }

    fun markProgress(
        context: Context,
        variant: GemmaModelVariant,
        phase: ModelInstallPhase,
        downloadedBytes: Long,
        message: String,
    ) {
        preferences(context).edit()
            .putString(KEY_ACTIVE, variant.wireName)
            .putString(KEY_PHASE, phase.name)
            .putLong(KEY_DOWNLOADED, downloadedBytes)
            .putString(KEY_MESSAGE, message)
            .apply()
    }

    fun markInstalled(context: Context, variant: GemmaModelVariant) {
        preferences(context).edit()
            .putString(KEY_SELECTED, variant.wireName)
            .putString(KEY_ACTIVE, variant.wireName)
            .putString(KEY_PHASE, ModelInstallPhase.INSTALLED.name)
            .putLong(KEY_DOWNLOADED, variant.sizeBytes)
            .putString(KEY_MESSAGE, "模型文件已完成 SHA-256 校验")
            .putString(KEY_VERIFIED_PREFIX + variant.wireName, variant.sha256)
            .apply()
    }

    fun totalMemoryBytes(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem
    }

    fun availableStorageBytes(context: Context): Long =
        modelDirectory(context).usableSpace

    fun deviceRecommendation(context: Context): String {
        val total = totalMemoryBytes(context)
        val memoryLabel = formatBytes(total)
        val recommendation = when {
            total >= 14_000_000_000L -> "适合优先使用 E4B"
            total >= 11_000_000_000L -> "可以运行 E4B；建议清理后台应用"
            else -> "建议使用 E2B 兼容版"
        }
        return "检测到约 $memoryLabel 内存，$recommendation"
    }

    fun formatBytes(bytes: Long): String {
        val gigabytes = bytes.toDouble() / 1_000_000_000.0
        return if (gigabytes >= 1.0) {
            String.format(Locale.US, "%.2f GB", gigabytes)
        } else {
            String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        }
    }

    fun isExternalStorageAvailable(): Boolean =
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
