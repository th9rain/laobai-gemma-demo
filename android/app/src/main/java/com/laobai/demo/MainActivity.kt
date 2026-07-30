package com.laobai.demo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var serviceStatus: TextView
    private lateinit var modelDeviceInfo: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var modelOutput: TextView
    private lateinit var downloadE4b: Button
    private lateinit var downloadE2b: Button
    private lateinit var cancelModelDownload: Button
    private lateinit var runModelTest: Button

    private val modelPoll = object : Runnable {
        override fun run() {
            renderModelState()
            mainHandler.postDelayed(this, MODEL_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        serviceStatus = findViewById(R.id.serviceStatus)
        modelDeviceInfo = findViewById(R.id.modelDeviceInfo)
        modelStatus = findViewById(R.id.modelStatus)
        modelProgress = findViewById(R.id.modelProgress)
        modelOutput = findViewById(R.id.modelOutput)
        downloadE4b = findViewById(R.id.downloadE4b)
        downloadE2b = findViewById(R.id.downloadE2b)
        cancelModelDownload = findViewById(R.id.cancelModelDownload)
        runModelTest = findViewById(R.id.runModelTest)

        modelDeviceInfo.text = GemmaModelRepository.deviceRecommendation(this)

        findViewById<Button>(R.id.openAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.openAlwaysOn).setOnClickListener {
            startActivity(CaseActivity.createIntent(this, CaseActivity.CASE_ALWAYS_ON))
        }
        findViewById<Button>(R.id.openTrigger).setOnClickListener {
            startActivity(CaseActivity.createIntent(this, CaseActivity.CASE_TRIGGER))
        }
        downloadE4b.setOnClickListener { chooseOrDownload(GemmaModelVariant.E4B) }
        downloadE2b.setOnClickListener { chooseOrDownload(GemmaModelVariant.E2B) }
        cancelModelDownload.setOnClickListener {
            ModelDownloadService.cancel(this)
            modelStatus.text = "正在暂停下载…"
        }
        runModelTest.setOnClickListener { runRealModelTest() }
        findViewById<Button>(R.id.openModelTraces).setOnClickListener {
            startActivity(ModelTraceActivity.createIntent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        renderServiceStatus()
        mainHandler.removeCallbacks(modelPoll)
        mainHandler.post(modelPoll)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(modelPoll)
        super.onPause()
    }

    private fun chooseOrDownload(variant: GemmaModelVariant) {
        if (GemmaModelRepository.isInstalled(this, variant)) {
            GemmaModelRepository.selectVariant(this, variant)
            modelOutput.text = "已切换到 ${variant.displayName}。可运行端侧自检。"
            renderModelState()
            return
        }

        val partialBytes = GemmaModelRepository.partialFile(this, variant).length()
        val action = if (partialBytes > 0L) "继续下载" else "开始下载"
        val memoryWarning = if (
            variant == GemmaModelVariant.E4B &&
            GemmaModelRepository.totalMemoryBytes(this) < 14_000_000_000L
        ) {
            "\n\n当前内存版本可以尝试 E4B，但运行前建议清理其他后台应用。"
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("$action ${variant.displayName}")
            .setMessage(
                "模型大小 ${GemmaModelRepository.formatBytes(variant.sizeBytes)}。" +
                    "建议使用 Wi-Fi，并至少保留 8GB 可用空间。下载完成后会自动校验 SHA-256，" +
                    "之后可以完全离线运行。$memoryWarning",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton(action) { _, _ ->
                requestNotificationPermissionIfNeeded()
                GemmaModelRepository.selectVariant(this, variant)
                ModelDownloadService.start(this, variant)
                renderModelState()
            }
            .show()
    }

    private fun runRealModelTest() {
        val variant = GemmaModelRepository.installedSelectedVariant(this) ?: return
        modelOutput.text = "正在加载 ${variant.displayName} 并执行真实端侧推理…首次加载可能需要几十秒。"
        runModelTest.isEnabled = false
        GemmaRuntime.runTextSelfTest(this) { result ->
            result.onSuccess { inference ->
                modelOutput.text = buildString {
                    append("真实端侧推理成功\n")
                    append("模型：${inference.variant.displayName}\n")
                    append("后端：${runtimeModeLabel(inference.mode)}\n")
                    append("耗时：${inference.elapsedMs / 1000.0} 秒\n")
                    append("原始输出：${inference.output}")
                }
            }.onFailure { error ->
                modelOutput.text = "端侧推理失败：${error.message ?: error.javaClass.simpleName}\n" +
                    "如果 E4B 无法加载，可以下载并切换到 E2B 兼容版。"
            }
            renderModelState()
        }
    }

    private fun renderModelState() {
        val snapshot = GemmaModelRepository.snapshot(this)
        val busy = snapshot.phase == ModelInstallPhase.DOWNLOADING ||
            snapshot.phase == ModelInstallPhase.VERIFYING
        val selectedInstalled = GemmaModelRepository.isInstalled(this, snapshot.variant)

        modelStatus.text = when (snapshot.phase) {
            ModelInstallPhase.DOWNLOADING ->
                "${snapshot.variant.displayName} · ${snapshot.progressPercent}% · ${snapshot.message}"
            ModelInstallPhase.VERIFYING -> "${snapshot.variant.displayName} · ${snapshot.message}"
            ModelInstallPhase.INSTALLED -> "${snapshot.variant.displayName} 已安装并校验"
            ModelInstallPhase.PAUSED -> "${snapshot.variant.displayName} · ${snapshot.message}"
            ModelInstallPhase.ERROR -> snapshot.message
            ModelInstallPhase.IDLE -> "尚未安装模型，推荐 ${snapshot.variant.displayName}"
        }
        modelStatus.setTextColor(
            getColor(
                if (snapshot.phase == ModelInstallPhase.INSTALLED) {
                    R.color.status_enabled
                } else {
                    R.color.status_disabled
                },
            ),
        )

        modelProgress.visibility = if (busy || snapshot.downloadedBytes > 0L && !snapshot.installed) {
            View.VISIBLE
        } else {
            View.GONE
        }
        modelProgress.isIndeterminate = snapshot.phase == ModelInstallPhase.VERIFYING
        modelProgress.progress = snapshot.progressPercent
        cancelModelDownload.visibility = if (busy) View.VISIBLE else View.GONE

        downloadE4b.text = if (GemmaModelRepository.isInstalled(this, GemmaModelVariant.E4B)) {
            "使用 E4B（已安装）"
        } else {
            getString(R.string.download_e4b)
        }
        downloadE2b.text = if (GemmaModelRepository.isInstalled(this, GemmaModelVariant.E2B)) {
            "使用 E2B（已安装）"
        } else {
            getString(R.string.download_e2b)
        }
        downloadE4b.isEnabled = !busy && !GemmaRuntime.isRunning()
        downloadE2b.isEnabled = !busy && !GemmaRuntime.isRunning()
        runModelTest.isEnabled = selectedInstalled && !busy && !GemmaRuntime.isRunning()
    }

    private fun renderServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        serviceStatus.text = getString(
            if (enabled) R.string.accessibility_enabled else R.string.accessibility_disabled,
        )
        serviceStatus.setTextColor(
            getColor(if (enabled) R.color.status_enabled else R.color.status_disabled),
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, LaoBaiAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        return enabled.split(':').any { component ->
            component.equals(expected, ignoreCase = true)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
        }
    }

    private fun runtimeModeLabel(mode: GemmaRuntimeMode): String = when (mode) {
        GemmaRuntimeMode.MULTIMODAL_GPU -> "GPU 多模态"
        GemmaRuntimeMode.MULTIMODAL_CPU -> "CPU 多模态"
        GemmaRuntimeMode.TEXT_ONLY_CPU -> "CPU 文本兼容模式"
    }

    companion object {
        private const val MODEL_POLL_MS = 700L
        private const val REQUEST_NOTIFICATIONS = 4202
    }
}
