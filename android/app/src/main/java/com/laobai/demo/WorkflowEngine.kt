package com.laobai.demo

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject

class WorkflowEngine(
    private val service: LaoBaiAccessibilityService,
    private val onUpdate: (WorkflowUpdate) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var advanceScheduled = false
    private val advanceRunnable = Runnable {
        advanceScheduled = false
        advance()
    }

    private var phase = WorkflowPhase.IDLE
    private var activeCase: DemoCase? = null
    private var steps: List<ReplayStep> = emptyList()
    private var stepIndex = 0
    private var misses = 0
    private var pendingOption: String? = null
    private var pendingOptionRetries = 0
    private var pendingVerification: Verification? = null
    private var verificationRetries = 0
    private var targetScope: TargetScope? = null
    private var startedAtElapsedMs = 0L
    private var runGeneration = 0L
    private var inferencePending = false
    private var gesturePending = false
    private var lastScrollFingerprint: Int? = null
    private var scrollIssued = false
    private var traceSessionId: String? = null
    private val completedVqaStages = mutableSetOf<String>()
    private val vqaAttempts = mutableMapOf<String, Int>()
    private var pendingModelScrollStage: String? = null

    val latestUpdate: WorkflowUpdate
        get() = lastUpdate

    private var lastUpdate = WorkflowUpdate(
        phase = WorkflowPhase.IDLE,
        message = "待命",
    )

    fun detectCurrentCase(): DemoCase? {
        val root = findCaseRoot() ?: return null
        return try {
            AccessibilityNodeOps.detectCase(root)
        } finally {
            AccessibilityNodeOps.recycle(root)
        }
    }

    fun prepare(demoCase: DemoCase) {
        if (phase == WorkflowPhase.RUNNING) return
        activeCase = demoCase
        targetScope = null
        emit(
            phase = WorkflowPhase.AWAITING_CONFIRMATION,
            message = when (demoCase) {
                DemoCase.ALWAYS_ON -> "检测到报名表，等待确认是否自动填表"
                DemoCase.TRIGGER -> "检测到京医通，等待确认是否自动挂号"
            },
        )
    }

    fun dismissConfirmation() {
        if (phase == WorkflowPhase.AWAITING_CONFIRMATION) {
            activeCase = null
            targetScope = null
            emit(WorkflowPhase.IDLE, "待命")
        }
    }

    fun acknowledgeHumanConfirmation() {
        if (phase == WorkflowPhase.HUMAN_CONFIRMATION) {
            activeCase = null
            steps = emptyList()
            stepIndex = 0
            targetScope = null
            GemmaRuntime.closeSession()
            emit(WorkflowPhase.IDLE, "待命")
        }
    }

    fun start(demoCase: DemoCase) {
        runGeneration += 1L
        clearScheduledAdvance()
        inferencePending = false
        gesturePending = false
        resetSearchState()
        completedVqaStages.clear()
        vqaAttempts.clear()
        pendingModelScrollStage = null
        pendingOption = null
        pendingOptionRetries = 0
        pendingVerification = null
        verificationRetries = 0
        val root = findCaseRoot(demoCase)
        if (root == null) {
            activeCase = demoCase
            targetScope = null
            return emit(
                WorkflowPhase.ERROR,
                "请先打开${demoCase.displayName}页面，再启动操作",
            )
        }

        activeCase = demoCase
        if (GemmaModelRepository.installedSelectedVariant(service) == null) {
            AccessibilityNodeOps.recycle(root)
            targetScope = null
            return emit(
                WorkflowPhase.ERROR,
                "请先在老白主界面下载并校验 E4B 或 E2B 端侧模型",
            )
        }
        traceSessionId = runCatching {
            ModelTraceStore.startSession(service, demoCase)
        }.getOrElse { error ->
            AccessibilityNodeOps.recycle(root)
            targetScope = null
            return emit(WorkflowPhase.ERROR, "无法创建模型调用记录：${error.message}")
        }
        if (demoCase == DemoCase.TRIGGER) {
            TriggerPlannerReplay.record(service, traceSessionId.orEmpty())
        }
        steps = replayFor(demoCase)
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName.isBlank()) {
            AccessibilityNodeOps.recycle(root)
            targetScope = null
            return emit(WorkflowPhase.ERROR, "无法确认当前页面所属应用")
        }
        targetScope = TargetScope(packageName, root.windowId)
        stepIndex = try {
            inferStartingStep(demoCase, root)
        } finally {
            AccessibilityNodeOps.recycle(root)
        }
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        emit(
            WorkflowPhase.RUNNING,
            if (demoCase == DemoCase.TRIGGER) {
                "云端规划已回放，准备运行端侧 VQA"
            } else {
                "准备运行端侧 VQA"
            },
            stepIndex.coerceAtMost(steps.size),
            steps.size,
        )
        scheduleAdvance(250)
    }

    fun cancel(reason: String = "用户已取消自动操作") {
        runGeneration += 1L
        clearScheduledAdvance()
        inferencePending = false
        gesturePending = false
        pendingOption = null
        pendingVerification = null
        pendingModelScrollStage = null
        targetScope = null
        if (phase == WorkflowPhase.RUNNING || phase == WorkflowPhase.AWAITING_CONFIRMATION) {
            emit(WorkflowPhase.CANCELLED, reason, stepIndex, steps.size)
        }
        GemmaRuntime.closeSession()
    }

    fun shutdown() {
        runGeneration += 1L
        handler.removeCallbacksAndMessages(null)
        advanceScheduled = false
        inferencePending = false
        gesturePending = false
        pendingOption = null
        pendingVerification = null
        pendingModelScrollStage = null
        targetScope = null
        phase = WorkflowPhase.IDLE
        GemmaRuntime.closeSession()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (phase != WorkflowPhase.RUNNING || inferencePending || gesturePending || event == null) return
        val expectedPackage = targetScope?.packageName
        val eventPackage = event.packageName?.toString()
        if (expectedPackage != null && eventPackage != null && eventPackage != expectedPackage) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> scheduleAdvance(220)
        }
    }

    private fun advance() {
        if (phase != WorkflowPhase.RUNNING || inferencePending || gesturePending) return
        if (SystemClock.elapsedRealtime() - startedAtElapsedMs > MAX_WORKFLOW_DURATION_MS) {
            return fail("操作超时，已安全停止")
        }
        val demoCase = activeCase ?: return fail("工作流类型丢失")
        if (pendingOption != null) {
            val optionRoot = findOptionRoot(pendingOption.orEmpty())
                ?: return retryPendingOption()
            return try {
                if (stepIndex >= steps.size) fail("下拉选择步骤状态异常")
                else completePendingSelect(optionRoot, steps[stepIndex])
            } finally {
                AccessibilityNodeOps.recycle(optionRoot)
            }
        }

        val root = findTrustedRoot(demoCase)
            ?: return retryWithoutScroll("目标页面已离开前台，操作已暂停")
        try {
            advanceInTrustedRoot(root, demoCase)
        } finally {
            AccessibilityNodeOps.recycle(root)
        }
    }

    private fun advanceInTrustedRoot(
        root: AccessibilityNodeInfo,
        demoCase: DemoCase,
    ) {
        pendingVerification?.let { verification ->
            return verifyLastAction(root, verification)
        }

        if (finalConfirmationVisible(root, demoCase)) {
            if (stepIndex != steps.size) {
                return fail("在工作流完成前意外进入最终确认页")
            }
            val missing = missingSummaryValues(root, demoCase)
            if (missing.isNotEmpty()) {
                return fail("确认页信息校验失败，缺少：${missing.joinToString("、")}")
            }
            return finishBeforeCommit(demoCase)
        }

        if (stepIndex >= steps.size) {
            return fail("回放已执行完，但未检测到最终人工确认页")
        }

        val step = steps[stepIndex]
        val vqaStage = vqaStageFor(demoCase, stepIndex)
        if (vqaStage != null && pendingModelScrollStage == vqaStage) {
            pendingModelScrollStage = null
            findByScrolling(root, step)
            return
        }
        if (vqaStage != null && completedVqaStages.add(vqaStage)) {
            requestVqaObservation(demoCase, step, vqaStage)
            return
        }
        emit(
            WorkflowPhase.RUNNING,
            "正在${step.progressLabel}",
            stepIndex + 1,
            steps.size,
        )

        when (val result = execute(step, root)) {
            StepResult.DONE -> {
                stepIndex += 1
                resetSearchState()
                scheduleAdvance(if (step is ClickStep) 700 else 300)
            }

            StepResult.OPENED_SELECT -> {
                pendingOptionRetries = 0
                scheduleAdvance(350)
            }

            StepResult.AWAITING_VERIFICATION -> {
                verificationRetries = 0
                scheduleAdvance(if (step is ClickStep) 700 else 280)
            }

            StepResult.NOT_FOUND -> {
                vqaStage?.let(completedVqaStages::remove)
                findByScrolling(root, step)
            }
            is StepResult.FAILED -> fail(result.reason)
        }
    }

    private fun requestVqaObservation(
        demoCase: DemoCase,
        step: ReplayStep,
        stage: String,
    ) {
        val sessionId = traceSessionId ?: return fail("模型调用记录会话丢失")
        val scope = targetScope ?: return fail("目标窗口已失效")
        val attempt = (vqaAttempts[stage] ?: 0) + 1
        vqaAttempts[stage] = attempt
        if (attempt > MAX_VQA_ATTEMPTS_PER_STAGE) {
            return fail("端侧 VQA 多次仍未找到“${step.progressLabel}”，已安全停止")
        }
        val traceTitle = "$stage / 第 $attempt 次观察"
        val generation = runGeneration
        val expectedStepIndex = stepIndex
        inferencePending = true
        emit(
            WorkflowPhase.RUNNING,
            "端侧 Gemma 正在理解屏幕：${step.progressLabel}",
            stepIndex + 1,
            steps.size,
        )
        service.captureScreenForVqa(
            sessionId = sessionId,
            stage = stage,
            windowId = scope.windowId,
        ) { screenshotResult ->
            if (!isCurrentVqa(generation, expectedStepIndex, scope.windowId)) return@captureScreenForVqa
            screenshotResult.onFailure { error ->
                appendVqaTrace(
                    sessionId = sessionId,
                    title = traceTitle,
                    prompt = buildVqaPrompt(demoCase, step, stage),
                    output = "SCREENSHOT_ERROR: ${error.message ?: error.javaClass.simpleName}",
                    screenshotPath = null,
                    elapsedMs = 0L,
                    backend = "未调用模型",
                    status = "截图失败，工作流已安全停止",
                )
                inferencePending = false
                fail("端侧 VQA 无法取得当前窗口截图；请查看模型记录")
            }.onSuccess { screenshot ->
                val prompt = buildVqaPrompt(demoCase, step, stage)
                val inferenceStarted = SystemClock.elapsedRealtime()
                GemmaRuntime.runVisionOnce(service, screenshot, prompt) { inferenceResult ->
                    if (!isCurrentVqa(generation, expectedStepIndex, scope.windowId)) {
                        return@runVisionOnce
                    }
                    inferenceResult.onSuccess { inference ->
                        val validation = validateVqaOutput(inference.output)
                        val assessment = validation.assessment
                        val traceStatus = when {
                            assessment == null -> validation.status
                            assessment.action == "guard" -> "VQA 要求 guard，未授权执行"
                            !assessment.targetVisible || assessment.action == "scroll" ->
                                "VQA 未看到目标，授权一次受限滚动后重试"
                            assessment.action == "wait" -> "VQA 建议等待后重试"
                            !step.matchesVqaTarget(assessment.target) ->
                                "VQA 目标与当前安全步骤不匹配，未授权执行"
                            !step.acceptsVqaAction(assessment.action) ->
                                "VQA 动作类型与当前安全步骤不匹配，未授权执行"
                            else -> "真实 VQA 已授权当前低风险步骤；由语义执行器复核"
                        }
                        appendVqaTrace(
                            sessionId = sessionId,
                            title = traceTitle,
                            prompt = prompt,
                            output = inference.output,
                            screenshotPath = screenshot.absolutePath,
                            elapsedMs = inference.elapsedMs,
                            backend = runtimeModeLabel(inference.mode),
                            status = traceStatus,
                        )
                        inferencePending = false
                        when {
                            assessment == null ->
                                fail("端侧 VQA 输出未通过校验；请查看模型记录")

                            assessment.action == "guard" ->
                                fail("端侧 VQA 要求在“${assessment.target}”前停止")

                            !assessment.targetVisible || assessment.action == "scroll" -> {
                                completedVqaStages.remove(stage)
                                pendingModelScrollStage = stage
                                emit(
                                    WorkflowPhase.RUNNING,
                                    "端侧 VQA 未看到目标，滚动一次后重新观察",
                                    stepIndex + 1,
                                    steps.size,
                                )
                                scheduleAdvance(120)
                            }

                            assessment.action == "wait" -> {
                                completedVqaStages.remove(stage)
                                emit(
                                    WorkflowPhase.RUNNING,
                                    "端侧 VQA 建议等待，稍后重新观察",
                                    stepIndex + 1,
                                    steps.size,
                                )
                                scheduleAdvance(650)
                            }

                            !step.matchesVqaTarget(assessment.target) ->
                                fail("端侧 VQA 识别目标与当前安全步骤不匹配；请查看模型记录")

                            !step.acceptsVqaAction(assessment.action) ->
                                fail("端侧 VQA 建议动作与安全步骤不匹配；请查看模型记录")

                            else -> {
                                emit(
                                    WorkflowPhase.RUNNING,
                                    "端侧 VQA 已授权低风险操作：${step.progressLabel}",
                                    stepIndex + 1,
                                    steps.size,
                                )
                                scheduleAdvance(120)
                            }
                        }
                    }.onFailure { error ->
                        appendVqaTrace(
                            sessionId = sessionId,
                            title = traceTitle,
                            prompt = prompt,
                            output = "INFERENCE_ERROR: ${error.message ?: error.javaClass.simpleName}",
                            screenshotPath = screenshot.absolutePath,
                            elapsedMs = SystemClock.elapsedRealtime() - inferenceStarted,
                            backend = "LiteRT-LM 初始化或推理失败",
                            status = "端侧 VQA 失败，工作流已安全停止",
                        )
                        inferencePending = false
                        fail("端侧 Gemma 推理失败；请查看模型记录或切换 E2B")
                    }
                }
            }
        }
    }

    private fun isCurrentVqa(
        generation: Long,
        expectedStepIndex: Int,
        expectedWindowId: Int,
    ): Boolean = phase == WorkflowPhase.RUNNING &&
        runGeneration == generation &&
        stepIndex == expectedStepIndex &&
        targetScope?.windowId == expectedWindowId

    private fun appendVqaTrace(
        sessionId: String,
        title: String,
        prompt: String,
        output: String,
        screenshotPath: String?,
        elapsedMs: Long,
        backend: String,
        status: String,
    ) {
        val variant = GemmaModelRepository.installedSelectedVariant(service)
        runCatching {
            ModelTraceStore.append(
                service,
                sessionId,
                ModelTraceEntry(
                    source = ModelTraceSource.EDGE_VQA,
                    title = title,
                    modelName = variant?.displayName ?: "Gemma 端侧模型",
                    inputText = prompt,
                    outputText = output.take(MAX_TRACE_OUTPUT_CHARS),
                    screenshotPath = screenshotPath,
                    elapsedMs = elapsedMs,
                    backend = backend,
                    status = status,
                ),
            )
        }
    }

    private fun buildVqaPrompt(
        demoCase: DemoCase,
        step: ReplayStep,
        stage: String,
    ): String {
        val cloudContext = if (demoCase == DemoCase.TRIGGER) {
            """
                云端 Planner 历史回放结果（只作为任务上下文，不代表当前联网）：
                ${TriggerPlannerReplay.output}
            """.trimIndent()
        } else {
            "本任务完全在端侧执行，不调用云端 Planner。"
        }
        return """
            你是运行在安卓手机本地的老白 Gemma 屏幕 VQA 模型。
            只观察随消息提供的当前应用窗口截图，不使用 DOM、HTML id 或无障碍节点。
            场景：${demoCase.displayName}
            当前阶段：$stage
            受约束执行器准备进行的下一项低风险操作：${step.vqaContract()}
            $cloudContext

            请判断截图属于哪个页面、目标是否在当前画面可见，并给出建议动作类型。
            只返回一个严格 JSON 对象，不要 Markdown，不要额外文字：
            {"screen":"中文页面描述","targetVisible":true,"target":"中文目标","recommendedAction":"tap|type_at|select|scroll|wait|guard","reason":"中文理由"}
            不得建议点击“提交报名”“确认挂号”、支付、验证码、授权或删除；遇到这些目标必须 recommendedAction=guard。
            你的回答会被本地安全执行器再次验证，模型不会直接执行高风险操作。
        """.trimIndent()
    }

    private fun ReplayStep.vqaContract(): String = when (this) {
        is SetTextStep -> "在“${target.label}”中填写本地资料“$value”"
        is ChoiceStep -> "选择“$text”"
        is ClickStep -> "点击“$text”"
        is SelectStep -> "在“${target.label}”中选择“$option”"
    }

    private fun ReplayStep.acceptsVqaAction(action: String): Boolean = when (this) {
        is SetTextStep -> action == "type_at"
        is ChoiceStep -> action == "tap" || action == "click" || action == "select"
        is ClickStep -> action == "tap" || action == "click"
        is SelectStep -> action == "tap" || action == "click" || action == "select"
    }

    private fun ReplayStep.matchesVqaTarget(modelTarget: String): Boolean {
        val expected = when (this) {
            is SetTextStep -> target.label
            is ChoiceStep -> text
            is ClickStep -> text
            is SelectStep -> "$option ${target.label}"
        }
        val normalizedExpected = normalizeVqaLabel(expected)
        val normalizedActual = normalizeVqaLabel(modelTarget)
        if (normalizedExpected.isBlank() || normalizedActual.isBlank()) return false
        return normalizedActual.contains(normalizedExpected) ||
            normalizedExpected.contains(normalizedActual)
    }

    private fun normalizeVqaLabel(value: String): String =
        value.replace(Regex("[\\s，。！？、,.!?；;：:\\-_/（）()]"), "")

    private fun validateVqaOutput(output: String): VqaValidation {
        if (output.length > MAX_TRACE_OUTPUT_CHARS) {
            return VqaValidation(null, "VQA 输出过长，未授权执行")
        }
        val jsonText = firstJsonObject(output)
            ?: return VqaValidation(null, "VQA 输出不是严格 JSON，未授权执行")
        if (output.trim() != jsonText) {
            return VqaValidation(null, "VQA 输出包含 JSON 之外的文字，未授权执行")
        }
        return runCatching {
            val json = JSONObject(jsonText)
            val action = json.optString("recommendedAction")
            check(action in VQA_ACTIONS) { "动作类型不在白名单" }
            val screen = json.getString("screen").also { check(it.isNotBlank()) }
            val targetVisible = json.getBoolean("targetVisible")
            val target = json.getString("target").also { check(it.isNotBlank()) }
            val reason = json.getString("reason").also { check(it.isNotBlank()) }
            VqaValidation(
                assessment = VqaAssessment(
                    screen = screen,
                    targetVisible = targetVisible,
                    target = target,
                    action = action,
                    reason = reason,
                ),
                status = "真实 VQA JSON 已校验；等待安全步骤匹配",
            )
        }.getOrElse { error ->
            VqaValidation(null, "VQA JSON 校验失败（${error.message}），未授权执行")
        }
    }

    private fun firstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
                continue
            }
            when (char) {
                '"' -> quoted = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun runtimeModeLabel(mode: GemmaRuntimeMode): String = when (mode) {
        GemmaRuntimeMode.MULTIMODAL_GPU -> "LiteRT-LM · GPU 多模态"
        GemmaRuntimeMode.MULTIMODAL_CPU -> "LiteRT-LM · CPU 多模态"
        GemmaRuntimeMode.TEXT_ONLY_CPU -> "LiteRT-LM · CPU 文本模式"
    }

    private fun vqaStageFor(demoCase: DemoCase, index: Int): String? {
        val step = replayFor(demoCase).getOrNull(index) ?: return null
        val prefix = if (demoCase == DemoCase.ALWAYS_ON) "Always On" else "Trigger"
        return "$prefix / 步骤 ${index + 1} · ${step.progressLabel}"
    }

    private data class VqaAssessment(
        val screen: String,
        val targetVisible: Boolean,
        val target: String,
        val action: String,
        val reason: String,
    )

    private data class VqaValidation(
        val assessment: VqaAssessment?,
        val status: String,
    )

    private fun execute(
        step: ReplayStep,
        root: AccessibilityNodeInfo,
    ): StepResult {
        return when (step) {
            is SetTextStep -> {
                val node = AccessibilityNodeOps.findControl(root, step.target)
                    ?: return StepResult.NOT_FOUND
                try {
                    if (AccessibilityNodeOps.currentValue(node) == step.value) {
                        StepResult.DONE
                    } else if (AccessibilityNodeOps.setText(node, step.value)) {
                        pendingVerification = Verification.Text(step.target, step.value)
                        StepResult.AWAITING_VERIFICATION
                    } else {
                        StepResult.FAILED("无法填写“${step.target.label}”")
                    }
                } finally {
                    AccessibilityNodeOps.recycle(node)
                }
            }

            is ChoiceStep -> {
                val node = AccessibilityNodeOps.findActionText(root, step.text, step.exact)
                    ?: return StepResult.NOT_FOUND
                try {
                    if (AccessibilityNodeOps.isChoiceChecked(node)) {
                        StepResult.DONE
                    } else if (AccessibilityNodeOps.safeClick(node, step.text)) {
                        pendingVerification = Verification.Choice(step.text, step.exact)
                        StepResult.AWAITING_VERIFICATION
                    } else {
                        blockedOrFailed(step.text)
                    }
                } finally {
                    AccessibilityNodeOps.recycle(node)
                }
            }

            is ClickStep -> {
                val node = AccessibilityNodeOps.findActionText(root, step.text, step.exact)
                    ?: return StepResult.NOT_FOUND
                try {
                    if (AccessibilityNodeOps.safeClick(node, step.text)) {
                        pendingVerification = Verification.Click(step.text, step.postconditionTokens)
                        StepResult.AWAITING_VERIFICATION
                    } else {
                        blockedOrFailed(step.text)
                    }
                } finally {
                    AccessibilityNodeOps.recycle(node)
                }
            }

            is SelectStep -> {
                val node = AccessibilityNodeOps.findControl(root, step.target)
                    ?: return StepResult.NOT_FOUND
                try {
                    if (AccessibilityNodeOps.currentValue(node).contains(step.option)) {
                        StepResult.DONE
                    } else if (AccessibilityNodeOps.safeClick(node, step.target.label)) {
                        pendingOption = step.option
                        StepResult.OPENED_SELECT
                    } else {
                        blockedOrFailed(step.target.label)
                    }
                } finally {
                    AccessibilityNodeOps.recycle(node)
                }
            }
        }
    }

    private fun completePendingSelect(
        root: AccessibilityNodeInfo,
        step: ReplayStep,
    ) {
        val option = pendingOption ?: return
        val node = AccessibilityNodeOps.findActionText(root, option, exact = true)
        val clicked = if (node == null) false else try {
            AccessibilityNodeOps.safeClick(node, option)
        } finally {
            AccessibilityNodeOps.recycle(node)
        }
        if (clicked) {
            pendingOption = null
            pendingOptionRetries = 0
            resetSearchState()
            val selectStep = step as? SelectStep
                ?: return fail("下拉选择步骤状态异常")
            pendingVerification = Verification.Select(selectStep.target, option)
            verificationRetries = 0
            scheduleAdvance(450)
            return
        }

        pendingOptionRetries += 1
        if (pendingOptionRetries > MAX_SELECT_RETRIES) {
            pendingOption = null
            fail("无法在“${step.progressLabel}”中选择“$option”")
        } else {
            scheduleAdvance(300)
        }
    }

    private fun verifyLastAction(
        root: AccessibilityNodeInfo,
        verification: Verification,
    ) {
        val verified = when (verification) {
            is Verification.Text -> {
                val node = AccessibilityNodeOps.findControl(root, verification.target)
                if (node == null) false else try {
                    AccessibilityNodeOps.currentValue(node) == verification.value
                } finally {
                    AccessibilityNodeOps.recycle(node)
                }
            }

            is Verification.Choice -> {
                val node = AccessibilityNodeOps.findActionText(
                    root,
                    verification.text,
                    verification.exact,
                )
                if (node == null) false else try {
                    AccessibilityNodeOps.choiceCheckedState(node) == true
                } finally {
                    AccessibilityNodeOps.recycle(node)
                }
            }

            is Verification.Select -> {
                val node = AccessibilityNodeOps.findControl(root, verification.target)
                if (node == null) false else try {
                    AccessibilityNodeOps.currentValue(node).contains(verification.option)
                } finally {
                    AccessibilityNodeOps.recycle(node)
                }
            }

            is Verification.Click -> {
                val text = AccessibilityNodeOps.treeText(root)
                verification.postconditionTokens.all(text::contains)
            }
        }

        if (verified) {
            pendingVerification = null
            verificationRetries = 0
            resetSearchState()
            stepIndex += 1
            scheduleAdvance(280)
            return
        }

        verificationRetries += 1
        if (verificationRetries > MAX_VERIFICATION_RETRIES) {
            pendingVerification = null
            fail("操作后校验失败：${verification.label}")
        } else {
            scheduleAdvance(280)
        }
    }

    private fun findByScrolling(root: AccessibilityNodeInfo, step: ReplayStep) {
        val fingerprint = AccessibilityNodeOps.visibleText(root).hashCode()
        if (scrollIssued && lastScrollFingerprint == fingerprint) {
            return fail("页面没有继续移动，已停止查找“${step.progressLabel}”")
        }

        if (misses == 0 && showStepOnScreen(root, step)) {
            misses = 1
            scrollIssued = false
            scheduleAdvance(420)
            return
        }

        misses += 1
        if (misses > MAX_SCROLL_ATTEMPTS) {
            return fail("在当前页面找不到“${step.progressLabel}”")
        }

        val forward = step.scrollForward
        lastScrollFingerprint = fingerprint
        scrollIssued = true
        val semanticScrollStarted = AccessibilityNodeOps.scroll(root, forward)
        if (semanticScrollStarted) {
            scheduleAdvance(550)
        } else {
            dispatchVerticalScroll(forward)
        }
    }

    private fun showStepOnScreen(root: AccessibilityNodeInfo, step: ReplayStep): Boolean =
        when (step) {
            is SetTextStep -> AccessibilityNodeOps.showControlOnScreen(root, step.target)
            is SelectStep -> AccessibilityNodeOps.showControlOnScreen(root, step.target)
            is ChoiceStep -> AccessibilityNodeOps.showTextOnScreen(root, step.text, step.exact)
            is ClickStep -> AccessibilityNodeOps.showTextOnScreen(root, step.text, step.exact)
        }

    private fun retryWithoutScroll(message: String) {
        misses += 1
        if (misses > MAX_ROOT_RETRIES) {
            fail(message)
        } else {
            scheduleAdvance(350)
        }
    }

    private fun retryPendingOption() {
        pendingOptionRetries += 1
        if (pendingOptionRetries > MAX_SELECT_RETRIES) {
            pendingOption = null
            fail("无法读取下拉选项窗口")
        } else {
            scheduleAdvance(300)
        }
    }

    private fun dispatchVerticalScroll(forward: Boolean) {
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val upper = metrics.heightPixels * 0.30f
        val lower = metrics.heightPixels * 0.78f
        val path = Path().apply {
            moveTo(x, if (forward) lower else upper)
            lineTo(x, if (forward) upper else lower)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 480))
            .build()
        val generation = runGeneration
        gesturePending = true
        val started = service.dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (generation != runGeneration || phase != WorkflowPhase.RUNNING) return
                    gesturePending = false
                    scheduleAdvance(160)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (generation != runGeneration || phase != WorkflowPhase.RUNNING) return
                    gesturePending = false
                    fail("页面滚动手势被系统取消")
                }
            },
            handler,
        )
        if (!started) {
            gesturePending = false
            fail("系统未能启动页面滚动手势")
        }
    }

    private fun blockedOrFailed(label: String): StepResult.FAILED {
        val blocked = AutomationSafetyPolicy.blockedTerm(label)
        return if (blocked != null) {
            StepResult.FAILED("安全策略阻止点击“$blocked”")
        } else {
            StepResult.FAILED("无法点击“$label”")
        }
    }

    private fun finalConfirmationVisible(
        root: AccessibilityNodeInfo,
        demoCase: DemoCase,
    ): Boolean {
        val label = when (demoCase) {
            DemoCase.ALWAYS_ON -> "提交报名"
            DemoCase.TRIGGER -> "确认挂号"
        }
        val node = AccessibilityNodeOps.findActionText(root, label, exact = true)
            ?: return false
        return try {
            AccessibilityNodeOps.isActionable(node)
        } finally {
            AccessibilityNodeOps.recycle(node)
        }
    }

    private fun missingSummaryValues(
        root: AccessibilityNodeInfo,
        demoCase: DemoCase,
    ): List<String> {
        val text = AccessibilityNodeOps.treeText(root)
        val expected = when (demoCase) {
            DemoCase.ALWAYS_ON -> listOf(
                "李桂兰",
                "智能手机基础班",
                "周三上午",
                "¥360.00",
            )

            DemoCase.TRIGGER -> listOf(
                "北京协和医院",
                "消化内科门诊",
                "李明",
                "10:00",
                "李桂兰",
            )
        }
        return expected.filterNot { expectedValue -> text.contains(expectedValue) }
    }

    private fun finishBeforeCommit(demoCase: DemoCase) {
        clearScheduledAdvance()
        targetScope = null
        val message = when (demoCase) {
            DemoCase.ALWAYS_ON -> "表单已填写并进入确认页；提交报名必须由您人工完成"
            DemoCase.TRIGGER -> "挂号信息已选择完毕；确认挂号和支付必须由您人工完成"
        }
        emit(WorkflowPhase.HUMAN_CONFIRMATION, message, stepIndex, steps.size)
        GemmaRuntime.closeSession()
    }

    private fun fail(message: String) {
        runGeneration += 1L
        clearScheduledAdvance()
        inferencePending = false
        gesturePending = false
        pendingOption = null
        pendingVerification = null
        pendingModelScrollStage = null
        targetScope = null
        emit(WorkflowPhase.ERROR, message, stepIndex, steps.size)
        GemmaRuntime.closeSession()
    }

    private fun scheduleAdvance(delayMs: Long) {
        if (advanceScheduled || inferencePending || gesturePending) return
        advanceScheduled = true
        handler.postDelayed(advanceRunnable, delayMs)
    }

    private fun clearScheduledAdvance() {
        handler.removeCallbacks(advanceRunnable)
        advanceScheduled = false
    }

    private fun resetSearchState() {
        misses = 0
        lastScrollFingerprint = null
        scrollIssued = false
    }

    private fun emit(
        phase: WorkflowPhase,
        message: String,
        currentStep: Int = 0,
        totalSteps: Int = 0,
    ) {
        this.phase = phase
        lastUpdate = WorkflowUpdate(
            phase = phase,
            demoCase = activeCase,
            message = message,
            currentStep = currentStep,
            totalSteps = totalSteps,
        )
        onUpdate(lastUpdate)
    }

    private fun findCaseRoot(expected: DemoCase? = null): AccessibilityNodeInfo? {
        var match: AccessibilityNodeInfo? = null
        service.windows.forEach { window ->
            try {
                if (
                    match == null &&
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (window.isActive || window.isFocused)
                ) {
                    val root = window.root
                    if (root != null) {
                        val detected = AccessibilityNodeOps.detectCase(root)
                        if (detected != null && (expected == null || detected == expected)) {
                            match = root
                        } else {
                            AccessibilityNodeOps.recycle(root)
                        }
                    }
                }
            } finally {
                recycleWindow(window)
            }
        }
        if (match != null) return match

        val fallback = service.rootInActiveWindow ?: return null
        val detected = AccessibilityNodeOps.detectCase(fallback)
        return if (detected != null && (expected == null || detected == expected)) {
            fallback
        } else {
            AccessibilityNodeOps.recycle(fallback)
            null
        }
    }

    private fun findTrustedRoot(demoCase: DemoCase): AccessibilityNodeInfo? {
        val scope = targetScope ?: return null
        var match: AccessibilityNodeInfo? = null
        service.windows.forEach { window ->
            try {
                if (
                    match == null &&
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.id == scope.windowId &&
                    (window.isActive || window.isFocused)
                ) {
                    val root = window.root
                    if (root != null) {
                        val correctPackage = root.packageName?.toString() == scope.packageName
                        val correctCase = AccessibilityNodeOps.detectCase(root) == demoCase
                        if (correctPackage && correctCase) {
                            match = root
                        } else {
                            AccessibilityNodeOps.recycle(root)
                        }
                    }
                }
            } finally {
                recycleWindow(window)
            }
        }
        if (match != null) return match

        val fallback = service.rootInActiveWindow ?: return null
        val valid = fallback.windowId == scope.windowId &&
            fallback.packageName?.toString() == scope.packageName &&
            AccessibilityNodeOps.detectCase(fallback) == demoCase
        return if (valid) fallback else {
            AccessibilityNodeOps.recycle(fallback)
            null
        }
    }

    private fun findOptionRoot(option: String): AccessibilityNodeInfo? {
        val scope = targetScope ?: return null
        var match: AccessibilityNodeInfo? = null
        service.windows.forEach { window ->
            try {
                if (
                    match == null &&
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (window.isActive || window.isFocused) &&
                    isRelatedToTarget(window, scope)
                ) {
                    val root = window.root
                    if (root != null) {
                        val samePackage = root.packageName?.toString() == scope.packageName
                        val optionNode = if (samePackage) {
                            AccessibilityNodeOps.findActionText(root, option, exact = true)
                        } else {
                            null
                        }
                        if (optionNode != null) {
                            AccessibilityNodeOps.recycle(optionNode)
                            match = root
                        } else {
                            AccessibilityNodeOps.recycle(root)
                        }
                    }
                }
            } finally {
                recycleWindow(window)
            }
        }
        if (match != null) return match

        val fallback = service.rootInActiveWindow ?: return null
        val samePackage = fallback.packageName?.toString() == scope.packageName &&
            fallback.windowId == scope.windowId
        val optionNode = if (samePackage) {
            AccessibilityNodeOps.findActionText(fallback, option, exact = true)
        } else {
            null
        }
        return if (optionNode != null) {
            AccessibilityNodeOps.recycle(optionNode)
            fallback
        } else {
            AccessibilityNodeOps.recycle(fallback)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleWindow(window: AccessibilityWindowInfo) {
        runCatching { window.recycle() }
    }

    private fun isRelatedToTarget(
        window: AccessibilityWindowInfo,
        scope: TargetScope,
    ): Boolean {
        if (window.id == scope.windowId) return true

        val parent = window.parent
        val parentMatches = parent?.id == scope.windowId
        parent?.let(::recycleWindow)
        if (parentMatches) return true

        val anchor = window.anchor
        val anchorMatches = anchor?.windowId == scope.windowId
        anchor?.let(AccessibilityNodeOps::recycle)
        return anchorMatches
    }

    private fun inferStartingStep(
        demoCase: DemoCase,
        root: AccessibilityNodeInfo?,
    ): Int {
        if (root == null) return 0
        val text = AccessibilityNodeOps.treeText(root)
        return when (demoCase) {
            DemoCase.ALWAYS_ON -> when {
                text.contains("报名信息确认") -> ALWAYS_STEPS.size
                text.contains("选择报名课程") -> ALWAYS_PAGE_TWO_INDEX
                else -> 0
            }

            DemoCase.TRIGGER -> when {
                text.contains("确认预约") && text.contains("当前就诊人") -> TRIGGER_STEPS.size
                text.contains("选择号源") -> 4
                text.contains("选择医生") -> 3
                text.contains("选择科室") -> 2
                text.contains("选择医院") -> 1
                else -> 0
            }
        }
    }

    private fun replayFor(demoCase: DemoCase): List<ReplayStep> = when (demoCase) {
        DemoCase.ALWAYS_ON -> ALWAYS_STEPS
        DemoCase.TRIGGER -> TRIGGER_STEPS
    }

    private data class TargetScope(
        val packageName: String,
        val windowId: Int,
    )

    private sealed interface StepResult {
        data object DONE : StepResult
        data object OPENED_SELECT : StepResult
        data object AWAITING_VERIFICATION : StepResult
        data object NOT_FOUND : StepResult
        data class FAILED(val reason: String) : StepResult
    }

    private sealed interface Verification {
        val label: String

        data class Text(
            val target: SemanticTarget,
            val value: String,
        ) : Verification {
            override val label: String = target.label
        }

        data class Choice(
            val text: String,
            val exact: Boolean,
        ) : Verification {
            override val label: String = text
        }

        data class Select(
            val target: SemanticTarget,
            val option: String,
        ) : Verification {
            override val label: String = target.label
        }

        data class Click(
            val text: String,
            val postconditionTokens: List<String>,
        ) : Verification {
            override val label: String = text
        }
    }

    private sealed interface ReplayStep {
        val progressLabel: String
        val scrollForward: Boolean
    }

    private data class SetTextStep(
        val target: SemanticTarget,
        val value: String,
        override val scrollForward: Boolean = true,
    ) : ReplayStep {
        override val progressLabel: String = "填写${target.label}"
    }

    private data class ChoiceStep(
        val text: String,
        val exact: Boolean = true,
        override val progressLabel: String = "选择$text",
        override val scrollForward: Boolean = true,
    ) : ReplayStep

    private data class ClickStep(
        val text: String,
        val exact: Boolean = true,
        val postconditionTokens: List<String>,
        override val progressLabel: String = "点击$text",
        override val scrollForward: Boolean = true,
    ) : ReplayStep

    private data class SelectStep(
        val target: SemanticTarget,
        val option: String,
        override val scrollForward: Boolean = true,
    ) : ReplayStep {
        override val progressLabel: String = "选择${target.label}"
    }

    companion object {
        private const val MAX_SCROLL_ATTEMPTS = 3
        private const val MAX_SELECT_RETRIES = 8
        private const val MAX_VERIFICATION_RETRIES = 6
        private const val MAX_ROOT_RETRIES = 8

        private val ALWAYS_STEPS = listOf(
            SetTextStep(
                SemanticTarget("name", "姓名", listOf("请输入真实姓名"), ControlKind.EDITABLE),
                "李桂兰",
                scrollForward = false,
            ),
            ChoiceStep("女"),
            SetTextStep(
                SemanticTarget("birth", "出生年月", listOf("格式：1955-03"), ControlKind.EDITABLE),
                "1953-03",
            ),
            SetTextStep(
                SemanticTarget("idcard", "身份证号", listOf("请输入18位身份证号"), ControlKind.EDITABLE),
                "110108195303086526",
            ),
            SetTextStep(
                SemanticTarget("phone", "联系电话", listOf("请输入手机号码"), ControlKind.EDITABLE),
                "13812342675",
            ),
            SelectStep(
                SemanticTarget("education", "文化程度", listOf("请选择"), ControlKind.SELECT),
                "高中/中专",
            ),
            SelectStep(
                SemanticTarget("district", "居住区", listOf("请选择所在区"), ControlKind.SELECT),
                "海淀区",
            ),
            SetTextStep(
                SemanticTarget("address", "详细地址", listOf("请输入现居住地址"), ControlKind.EDITABLE),
                "中关村街道科育社区18号楼",
            ),
            ChoiceStep("良好"),
            ChoiceStep("无"),
            SetTextStep(
                SemanticTarget("diseaseDetail", "其他说明", listOf("无则填“无”"), ControlKind.EDITABLE),
                "无",
            ),
            SetTextStep(
                SemanticTarget("emergencyName", "联系人", listOf("请输入紧急联系人姓名"), ControlKind.EDITABLE),
                "王敏",
            ),
            SelectStep(
                SemanticTarget("emergencyRelation", "与本人关系", listOf("请选择"), ControlKind.SELECT),
                "子女",
            ),
            SetTextStep(
                SemanticTarget("emergencyPhone", "联系电话", listOf("请输入联系人电话"), ControlKind.EDITABLE),
                "13912345678",
            ),
            ClickStep(
                "下一步",
                postconditionTokens = listOf("选择报名课程", "选择上课时间"),
            ),
            ChoiceStep("智能手机基础班", exact = false),
            ChoiceStep("周三上午 09:00-11:00", exact = false),
            ClickStep(
                "下一步",
                postconditionTokens = listOf("报名信息确认", "提交报名"),
            ),
        )
        private const val ALWAYS_PAGE_TWO_INDEX = 15

        private val TRIGGER_STEPS = listOf(
            ClickStep(
                "预约挂号",
                exact = false,
                postconditionTokens = listOf("选择医院", "北京协和医院"),
            ),
            ClickStep(
                "北京协和医院",
                exact = false,
                postconditionTokens = listOf("选择科室", "已选医院"),
            ),
            ClickStep(
                "消化内科门诊",
                exact = false,
                postconditionTokens = listOf("选择医生", "李明"),
            ),
            ClickStep(
                "李明",
                exact = false,
                postconditionTokens = listOf("选择号源", "10:00"),
            ),
            ClickStep(
                "10:00",
                exact = false,
                postconditionTokens = listOf("确认预约", "当前就诊人"),
            ),
        )

        private const val MAX_WORKFLOW_DURATION_MS = 8 * 60_000L
        private const val MAX_TRACE_OUTPUT_CHARS = 16_384
        private const val MAX_VQA_ATTEMPTS_PER_STAGE = 4
        private val VQA_ACTIONS = setOf(
            "tap",
            "click",
            "type_at",
            "select",
            "scroll",
            "wait",
            "guard",
        )
    }
}
