package com.laobai.demo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs

class LaoBaiAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private lateinit var windowManager: WindowManager
    private lateinit var workflowEngine: WorkflowEngine

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var controlPanel: View? = null
    private var panelStatus: TextView? = null
    private var panelPhase: WorkflowPhase? = null
    private var statusChip: TextView? = null
    private var voiceReceiverRegistered = false
    private var autoPromptCheckScheduled = false
    private var alwaysOnPromptedForVisit = false

    private val autoPromptRunnable = Runnable {
        autoPromptCheckScheduled = false
        if (!::workflowEngine.isInitialized) return@Runnable
        val detected = workflowEngine.detectCurrentCase()
        if (detected != DemoCase.ALWAYS_ON) {
            alwaysOnPromptedForVisit = false
            return@Runnable
        }
        val phase = workflowEngine.latestUpdate.phase
        if (phase != WorkflowPhase.IDLE) {
            alwaysOnPromptedForVisit = true
            return@Runnable
        }
        if (
            !alwaysOnPromptedForVisit &&
            controlPanel == null &&
            phase == WorkflowPhase.IDLE
        ) {
            alwaysOnPromptedForVisit = true
            workflowEngine.prepare(DemoCase.ALWAYS_ON)
            showControlPanel()
        }
    }

    private val voiceCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = VoiceCommandProtocol.read(intent) ?: return
            val demoCase = when (command) {
                VoiceWorkflowCommand.TRIGGER -> DemoCase.TRIGGER
                VoiceWorkflowCommand.ALWAYS_ON -> DemoCase.ALWAYS_ON
            }
            mainHandler.postDelayed(
                {
                    startVoiceWorkflowWhenVisible(
                        demoCase,
                        retriesRemaining = VOICE_RETURN_RETRIES,
                        fallbackOpened = false,
                    )
                },
                VOICE_ACTIVITY_SETTLE_MS,
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (::workflowEngine.isInitialized) workflowEngine.shutdown()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        workflowEngine = WorkflowEngine(this, ::renderWorkflowUpdate)
        registerVoiceCommandReceiver()
        showBubble()
        renderWorkflowUpdate(workflowEngine.latestUpdate)
        scheduleAlwaysOnPromptCheck()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (::workflowEngine.isInitialized) {
            workflowEngine.onAccessibilityEvent(event)
            when (event?.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleAlwaysOnPromptCheck()
            }
        }
    }

    override fun onInterrupt() {
        if (::workflowEngine.isInitialized) {
            workflowEngine.cancel("无障碍服务被系统中断")
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        autoPromptCheckScheduled = false
        unregisterVoiceCommandReceiver()
        if (::workflowEngine.isInitialized) workflowEngine.shutdown()
        removeControlPanel(showChipAfter = false)
        removeStatusChip()
        removeBubble()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    fun captureScreenForVqa(
        sessionId: String,
        stage: String,
        windowId: Int?,
        callback: (Result<java.io.File>) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(Result.failure(IllegalStateException("端侧 VQA 截图需要 Android 11 或更高版本")))
            return
        }

        val overlays = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOfNotNull(bubble, statusChip, controlPanel).map { it to it.visibility }
        } else {
            emptyList()
        }
        overlays.forEach { (view, _) -> view.visibility = View.INVISIBLE }
        fun restoreOverlays() {
            overlays.forEach { (view, visibility) -> view.visibility = visibility }
        }
        mainHandler.postDelayed(
            {
                val destination = runCatching {
                    ModelTraceStore.newScreenshotFile(this, sessionId, stage)
                }.getOrElse { error ->
                    restoreOverlays()
                    callback(Result.failure(error))
                    return@postDelayed
                }
                val screenshotCallback = object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        screenshotExecutor.execute {
                            val result = saveScreenshot(screenshot, destination)
                            mainHandler.post {
                                restoreOverlays()
                                callback(result)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        restoreOverlays()
                        callback(Result.failure(IllegalStateException("系统截图失败，错误码 $errorCode")))
                    }
                }
                runCatching {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        windowId != null
                    ) {
                        takeScreenshotOfWindow(windowId, mainExecutor, screenshotCallback)
                    } else {
                        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, screenshotCallback)
                    }
                }.onFailure { error ->
                    restoreOverlays()
                    callback(Result.failure(error))
                }
            },
            SCREENSHOT_OVERLAY_SETTLE_MS,
        )
    }

    private fun saveScreenshot(
        screenshot: ScreenshotResult,
        destination: java.io.File,
    ): Result<java.io.File> = runCatching {
        val buffer = screenshot.hardwareBuffer
        try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                ?: throw IllegalStateException("系统截图无法转换为位图")
            try {
                val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("系统截图无法复制到本地")
                val longestSide = maxOf(softwareBitmap.width, softwareBitmap.height)
                val savedBitmap = if (longestSide > MAX_VQA_SCREENSHOT_EDGE) {
                    val scale = MAX_VQA_SCREENSHOT_EDGE.toFloat() / longestSide
                    Bitmap.createScaledBitmap(
                        softwareBitmap,
                        (softwareBitmap.width * scale).toInt().coerceAtLeast(1),
                        (softwareBitmap.height * scale).toInt().coerceAtLeast(1),
                        true,
                    ).also { softwareBitmap.recycle() }
                } else {
                    softwareBitmap
                }
                try {
                    destination.outputStream().buffered().use { output ->
                        if (!savedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            throw IllegalStateException("无法保存端侧 VQA 截图")
                        }
                    }
                } finally {
                    savedBitmap.recycle()
                }
            } finally {
                hardwareBitmap.recycle()
            }
        } finally {
            buffer.close()
        }
        destination
    }

    private fun showBubble() {
        if (bubble != null) return

        val diameter = dp(56)
        val params = WindowManager.LayoutParams(
            diameter,
            diameter,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(180)
        }

        val view = TextView(this).apply {
            text = "白"
            contentDescription = "打开老白"
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
            elevation = dp(8).toFloat()
            background = roundBackground(COLOR_IDLE, circular = true, strokeColor = 0x33FFFFFF)
            setOnClickListener { toggleControlPanel() }
        }
        attachDragAndClick(view, params)

        bubbleParams = params
        bubble = view
        runCatching { windowManager.addView(view, params) }
            .onFailure { bubble = null }
    }

    private fun attachDragAndClick(
        view: TextView,
        params: WindowManager.LayoutParams,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // END gravity reverses the horizontal WindowManager offset.
                    params.x = startX - (event.rawX - downRawX).toInt()
                    params.y = startY + (event.rawY - downRawY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downRawX) + abs(event.rawY - downRawY)
                    if (moved < dp(10)) view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun toggleControlPanel() {
        if (controlPanel != null) {
            removeControlPanel(showChipAfter = true)
        } else {
            showControlPanel()
        }
    }

    private fun showControlPanel() {
        if (!::workflowEngine.isInitialized) return
        removeStatusChip()

        val prior = workflowEngine.latestUpdate
        val detected = workflowEngine.detectCurrentCase()
        if (
            prior.phase == WorkflowPhase.IDLE &&
            detected != null
        ) {
            workflowEngine.prepare(detected)
        }
        val update = workflowEngine.latestUpdate
        val demoCase = update.demoCase ?: detected

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            elevation = dp(12).toFloat()
            background = roundBackground(Color.WHITE, radiusDp = 18, strokeColor = 0x22000000)
        }

        val title = TextView(this).apply {
            text = panelTitle(update.phase, demoCase)
            textSize = 18f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(title, matchWrap())

        val message = TextView(this).apply {
            text = panelMessage(update, demoCase)
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(8), 0, dp(12))
        }
        panelStatus = message
        container.addView(message, matchWrap())

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        addPanelActions(actions, update, demoCase)
        container.addView(actions, matchWrap())

        val params = WindowManager.LayoutParams(
            dp(310),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = dp(108)
        }

        controlPanel = container
        panelPhase = update.phase
        runCatching { windowManager.addView(container, params) }
            .onFailure {
                controlPanel = null
                panelStatus = null
                panelPhase = null
                renderWorkflowUpdate(workflowEngine.latestUpdate)
            }
    }

    private fun addPanelActions(
        actions: LinearLayout,
        update: WorkflowUpdate,
        demoCase: DemoCase?,
    ) {
        when (update.phase) {
            WorkflowPhase.RUNNING -> {
                actions.addView(actionButton("取消操作", primary = false) {
                    removeControlPanel(showChipAfter = false)
                    workflowEngine.cancel()
                })
            }

            WorkflowPhase.HUMAN_CONFIRMATION -> {
                actions.addView(actionButton("模型记录", primary = false) {
                    launchModelTraces()
                })
                actions.addView(actionButton("知道了", primary = true) {
                    removeControlPanel(showChipAfter = false)
                    workflowEngine.acknowledgeHumanConfirmation()
                })
            }

            WorkflowPhase.AWAITING_CONFIRMATION -> {
                actions.addView(actionButton("语音", primary = false) {
                    launchVoiceCapture()
                })
                actions.addView(actionButton("暂不", primary = false) {
                    removeControlPanel(showChipAfter = false)
                    workflowEngine.dismissConfirmation()
                })
                actions.addView(actionButton("开始", primary = true) {
                    val selected = demoCase ?: return@actionButton
                    startWorkflowAfterOverlay(selected)
                })
            }

            WorkflowPhase.ERROR,
            WorkflowPhase.CANCELLED -> {
                if (ModelTraceStore.hasEntries(this)) {
                    actions.addView(actionButton("模型记录", primary = false) {
                        launchModelTraces()
                    })
                }
                actions.addView(actionButton("关闭", primary = false) {
                    removeControlPanel(showChipAfter = false)
                })
                if (demoCase != null) {
                    actions.addView(actionButton("重新开始", primary = true) {
                        startWorkflowAfterOverlay(demoCase)
                    })
                }
            }

            WorkflowPhase.IDLE -> {
                actions.addView(actionButton("语音", primary = false) {
                    launchVoiceCapture()
                })
                actions.addView(actionButton("关闭", primary = false) {
                    removeControlPanel(showChipAfter = false)
                })
                if (demoCase != null) {
                    actions.addView(actionButton("重新开始", primary = true) {
                        startWorkflowAfterOverlay(demoCase)
                    })
                }
            }
        }
    }

    private fun actionButton(
        label: String,
        primary: Boolean,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(if (primary) Color.WHITE else COLOR_TEXT)
        background = roundBackground(
            if (primary) COLOR_IDLE else COLOR_BUTTON_SECONDARY,
            radiusDp = 10,
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(42),
        ).apply { marginStart = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun renderWorkflowUpdate(update: WorkflowUpdate) {
        bubble?.apply {
            text = when (update.phase) {
                WorkflowPhase.IDLE -> "白"
                WorkflowPhase.AWAITING_CONFIRMATION -> "?"
                WorkflowPhase.RUNNING -> "…"
                WorkflowPhase.HUMAN_CONFIRMATION -> "✓"
                WorkflowPhase.ERROR -> "!"
                WorkflowPhase.CANCELLED -> "×"
            }
            contentDescription = when (update.phase) {
                WorkflowPhase.RUNNING -> "老白运行中，点击查看或取消"
                WorkflowPhase.HUMAN_CONFIRMATION -> "需要人工确认"
                WorkflowPhase.ERROR -> "老白操作出错，点击查看"
                else -> "打开老白"
            }
            background = roundBackground(statusColor(update.phase), circular = true, strokeColor = 0x33FFFFFF)
        }

        if (controlPanel != null && panelPhase != update.phase) {
            removeControlPanel(showChipAfter = false)
            showControlPanel()
            return
        }
        panelStatus?.text = panelMessage(update, update.demoCase)
        if (controlPanel == null) {
            when (update.phase) {
                WorkflowPhase.RUNNING,
                WorkflowPhase.HUMAN_CONFIRMATION,
                WorkflowPhase.ERROR,
                WorkflowPhase.CANCELLED -> showStatusChip(update)

                WorkflowPhase.IDLE,
                WorkflowPhase.AWAITING_CONFIRMATION -> removeStatusChip()
            }
        }
    }

    private fun showStatusChip(update: WorkflowUpdate) {
        val progress = if (update.phase == WorkflowPhase.RUNNING && update.totalSteps > 0) {
            " ${update.currentStep}/${update.totalSteps}"
        } else {
            ""
        }
        val label = when (update.phase) {
            WorkflowPhase.RUNNING -> "运行中$progress：${update.message}"
            WorkflowPhase.HUMAN_CONFIRMATION -> "请人工确认：${update.message}"
            WorkflowPhase.ERROR -> "操作出错：${update.message}"
            WorkflowPhase.CANCELLED -> update.message
            else -> update.message
        }

        statusChip?.let {
            it.text = label
            it.background = roundBackground(statusColor(update.phase), radiusDp = 12)
            return
        }

        val view = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            elevation = dp(8).toFloat()
            background = roundBackground(statusColor(update.phase), radiusDp = 12)
        }
        val params = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = dp(112)
        }
        statusChip = view
        runCatching { windowManager.addView(view, params) }
            .onFailure { statusChip = null }
    }

    private fun panelTitle(phase: WorkflowPhase, demoCase: DemoCase?): String = when (phase) {
        WorkflowPhase.RUNNING -> "老白正在操作"
        WorkflowPhase.HUMAN_CONFIRMATION -> "请您检查并确认"
        WorkflowPhase.ERROR -> "操作未完成"
        WorkflowPhase.CANCELLED -> "操作已取消"
        WorkflowPhase.AWAITING_CONFIRMATION -> when (demoCase) {
            DemoCase.ALWAYS_ON -> "是否帮您填写表单？"
            DemoCase.TRIGGER -> "是否帮您完成挂号选择？"
            null -> "老白"
        }

        WorkflowPhase.IDLE -> if (demoCase == null) "未识别到演示页面" else "老白"
    }

    private fun panelMessage(update: WorkflowUpdate, demoCase: DemoCase?): String {
        if (update.phase == WorkflowPhase.IDLE && demoCase == null) {
            return "请先打开 Always On 报名表或京医通页面，再点击悬浮球。"
        }
        if (update.phase == WorkflowPhase.AWAITING_CONFIRMATION) {
            return when (demoCase) {
                DemoCase.ALWAYS_ON -> "确认后将用真实端侧 Gemma VQA 理解页面，由安全执行器填表，并停在“提交报名”之前。"
                DemoCase.TRIGGER -> "确认后先回放云端规划，再用真实端侧 Gemma VQA 理解页面，并停在“确认挂号”之前。"
                null -> update.message
            }
        }
        return update.message
    }

    private fun removeControlPanel(showChipAfter: Boolean) {
        controlPanel?.let { view -> runCatching { windowManager.removeView(view) } }
        controlPanel = null
        panelStatus = null
        panelPhase = null
        if (showChipAfter && ::workflowEngine.isInitialized) {
            renderWorkflowUpdate(workflowEngine.latestUpdate)
        }
    }

    private fun removeStatusChip() {
        statusChip?.let { view -> runCatching { windowManager.removeView(view) } }
        statusChip = null
    }

    private fun removeBubble() {
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        bubbleParams = null
    }

    private fun startWorkflowAfterOverlay(demoCase: DemoCase) {
        removeControlPanel(showChipAfter = false)
        mainHandler.postDelayed(
            { if (::workflowEngine.isInitialized) workflowEngine.start(demoCase) },
            OVERLAY_SETTLE_MS,
        )
    }

    private fun launchVoiceCapture() {
        if (::workflowEngine.isInitialized && workflowEngine.detectCurrentCase() == DemoCase.ALWAYS_ON) {
            alwaysOnPromptedForVisit = true
        }
        removeControlPanel(showChipAfter = false)
        startActivity(
            Intent(this, VoiceCaptureActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            ),
        )
    }

    private fun launchModelTraces() {
        removeControlPanel(showChipAfter = false)
        startActivity(
            ModelTraceActivity.createIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun startVoiceWorkflowWhenVisible(
        demoCase: DemoCase,
        retriesRemaining: Int,
        fallbackOpened: Boolean,
    ) {
        if (!::workflowEngine.isInitialized) return
        if (workflowEngine.detectCurrentCase() == demoCase) {
            removeControlPanel(showChipAfter = false)
            if (demoCase == DemoCase.ALWAYS_ON) alwaysOnPromptedForVisit = true
            workflowEngine.start(demoCase)
        } else if (retriesRemaining > 0) {
            mainHandler.postDelayed(
                {
                    startVoiceWorkflowWhenVisible(
                        demoCase,
                        retriesRemaining - 1,
                        fallbackOpened,
                    )
                },
                VOICE_PAGE_RETRY_MS,
            )
        } else if (!fallbackOpened) {
            val caseName = when (demoCase) {
                DemoCase.ALWAYS_ON -> CaseActivity.CASE_ALWAYS_ON
                DemoCase.TRIGGER -> CaseActivity.CASE_TRIGGER
            }
            startActivity(
                CaseActivity.createIntent(this, caseName).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
            mainHandler.postDelayed(
                {
                    startVoiceWorkflowWhenVisible(
                        demoCase,
                        retriesRemaining = CASE_LAUNCH_RETRIES,
                        fallbackOpened = true,
                    )
                },
                CASE_LAUNCH_SETTLE_MS,
            )
        } else {
            workflowEngine.start(demoCase)
        }
    }

    private fun scheduleAlwaysOnPromptCheck() {
        if (autoPromptCheckScheduled) return
        autoPromptCheckScheduled = true
        mainHandler.postDelayed(autoPromptRunnable, AUTO_PROMPT_DELAY_MS)
    }

    private fun registerVoiceCommandReceiver() {
        if (voiceReceiverRegistered) return
        val filter = IntentFilter(VoiceCommandProtocol.ACTION_WORKFLOW_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                voiceCommandReceiver,
                filter,
                VoiceCommandProtocol.INTERNAL_PERMISSION,
                null,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                voiceCommandReceiver,
                filter,
                VoiceCommandProtocol.INTERNAL_PERMISSION,
                null,
            )
        }
        voiceReceiverRegistered = true
    }

    private fun unregisterVoiceCommandReceiver() {
        if (!voiceReceiverRegistered) return
        runCatching { unregisterReceiver(voiceCommandReceiver) }
        voiceReceiverRegistered = false
    }

    private fun statusColor(phase: WorkflowPhase): Int = when (phase) {
        WorkflowPhase.IDLE -> COLOR_IDLE
        WorkflowPhase.AWAITING_CONFIRMATION -> COLOR_AWAITING
        WorkflowPhase.RUNNING -> COLOR_RUNNING
        WorkflowPhase.HUMAN_CONFIRMATION -> COLOR_HUMAN
        WorkflowPhase.ERROR -> COLOR_ERROR
        WorkflowPhase.CANCELLED -> COLOR_CANCELLED
    }

    private fun roundBackground(
        color: Int,
        radiusDp: Int = 16,
        circular: Boolean = false,
        strokeColor: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        shape = if (circular) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        setColor(color)
        if (!circular) cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val OVERLAY_SETTLE_MS = 220L
        private const val AUTO_PROMPT_DELAY_MS = 400L
        private const val VOICE_ACTIVITY_SETTLE_MS = 1_050L
        private const val VOICE_PAGE_RETRY_MS = 250L
        private const val VOICE_RETURN_RETRIES = 4
        private const val CASE_LAUNCH_SETTLE_MS = 650L
        private const val CASE_LAUNCH_RETRIES = 8
        private const val SCREENSHOT_OVERLAY_SETTLE_MS = 140L
        private const val MAX_VQA_SCREENSHOT_EDGE = 1_280
        private val COLOR_IDLE = Color.rgb(180, 35, 42)
        private val COLOR_AWAITING = Color.rgb(217, 119, 6)
        private val COLOR_RUNNING = Color.rgb(8, 116, 67)
        private val COLOR_HUMAN = Color.rgb(37, 99, 235)
        private val COLOR_ERROR = Color.rgb(180, 35, 42)
        private val COLOR_CANCELLED = Color.rgb(102, 112, 133)
        private val COLOR_TEXT = Color.rgb(29, 41, 57)
        private val COLOR_TEXT_SECONDARY = Color.rgb(102, 112, 133)
        private val COLOR_BUTTON_SECONDARY = Color.rgb(234, 236, 240)
    }
}
