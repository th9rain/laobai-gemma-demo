package com.laobai.demo

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.math.abs

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
    private var pendingOptionOpenedAtElapsedMs = 0L
    private var pendingSelectBaselineWindowIds: Set<Int> = emptySet()
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
    private var currentTriggerPlan: TriggerReplayPlan? = null
    private var currentTriggerMemory: TriggerMemoryContext? = null
    private val completedVqaStages = mutableSetOf<String>()
    private val vqaAttempts = mutableMapOf<String, Int>()
    private var pendingModelScrollStage: String? = null
    private var activeVqaBatch: AuthorizedVqaBatch? = null
    private var pendingBatchScroll = false
    private var viewportSequence = 0
    private var viewportEpoch = 0L
    private var activeBatchRequestToken: String? = null
    private var batchValidationRetries = 0
    private val visualBadgeRandom = SecureRandom()

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
                DemoCase.ALWAYS_ON -> "检测到可能需要填写个人信息的表单，等待用户确认"
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
            activeVqaBatch = null
            pendingBatchScroll = false
            activeBatchRequestToken = null
            targetScope = null
            currentTriggerPlan = null
            currentTriggerMemory = null
            GemmaRuntime.closeSession()
            emit(WorkflowPhase.IDLE, "待命")
        }
    }

    fun start(demoCase: DemoCase, userRequest: String? = null) {
        runGeneration += 1L
        clearScheduledAdvance()
        inferencePending = false
        gesturePending = false
        resetSearchState()
        completedVqaStages.clear()
        vqaAttempts.clear()
        pendingModelScrollStage = null
        activeVqaBatch = null
        pendingBatchScroll = false
        viewportSequence = 0
        viewportEpoch = 0L
        activeBatchRequestToken = null
        batchValidationRetries = 0
        pendingOption = null
        pendingOptionRetries = 0
        pendingOptionOpenedAtElapsedMs = 0L
        pendingSelectBaselineWindowIds = emptySet()
        pendingVerification = null
        verificationRetries = 0
        currentTriggerPlan = null
        currentTriggerMemory = null
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
        val normalizedRequest = userRequest
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: when (demoCase) {
                DemoCase.TRIGGER -> "我有点不舒服，帮我安排一下就医"
                DemoCase.ALWAYS_ON -> "帮我处理一下当前页面上的任务"
            }
        if (demoCase == DemoCase.TRIGGER) {
            TaskPlanningReplay.record(
                service,
                traceSessionId.orEmpty(),
                normalizedRequest,
                demoCase,
            )
        } else {
            AlwaysOnWorkflowRepository.recordMatch(service, traceSessionId.orEmpty())
        }
        val triggerPlan = if (demoCase == DemoCase.TRIGGER) {
            runCatching {
                val memory = TriggerMemoryRepository.retrieveAndRecord(
                    service,
                    traceSessionId.orEmpty(),
                    normalizedRequest,
                )
                currentTriggerMemory = memory
                TriggerPlannerReplay.record(
                    service,
                    traceSessionId.orEmpty(),
                    memory,
                    normalizedRequest,
                )
            }.getOrElse { error ->
                AccessibilityNodeOps.recycle(root)
                targetScope = null
                fail("挂号记忆或云侧规划校验失败：${error.message}")
                return
            }
        } else {
            null
        }
        currentTriggerPlan = triggerPlan
        steps = if (triggerPlan == null) ALWAYS_STEPS else triggerSteps(triggerPlan)
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
        if (demoCase == DemoCase.TRIGGER) {
            emit(
                WorkflowPhase.PLAN_CONFIRMATION,
                triggerPlanConfirmationMessage(triggerPlan, currentTriggerMemory),
                stepIndex.coerceAtMost(steps.size),
                steps.size,
            )
            return
        }
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        emit(
            WorkflowPhase.RUNNING,
            "已加载本地个人资料与填表 workflow；准备运行端侧 VQA",
            stepIndex.coerceAtMost(steps.size),
            steps.size,
        )
        scheduleAdvance(250)
    }

    fun confirmTriggerPlan() {
        if (
            phase != WorkflowPhase.PLAN_CONFIRMATION ||
            activeCase != DemoCase.TRIGGER ||
            currentTriggerPlan == null
        ) {
            return
        }
        val root = findCaseRoot(DemoCase.TRIGGER)
            ?: return fail("执行计划前未找到京医通页面，请重新打开后再试")
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName.isBlank()) {
            AccessibilityNodeOps.recycle(root)
            return fail("执行计划前无法确认当前页面所属应用")
        }
        targetScope = TargetScope(packageName, root.windowId)
        stepIndex = try {
            inferStartingStep(DemoCase.TRIGGER, root)
        } finally {
            AccessibilityNodeOps.recycle(root)
        }
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        emit(
            WorkflowPhase.RUNNING,
            "计划已确认，正在交给端侧 Gemma 4B VQA 执行",
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
        pendingOptionOpenedAtElapsedMs = 0L
        pendingSelectBaselineWindowIds = emptySet()
        pendingVerification = null
        pendingModelScrollStage = null
        activeVqaBatch = null
        pendingBatchScroll = false
        activeBatchRequestToken = null
        targetScope = null
        currentTriggerPlan = null
        currentTriggerMemory = null
        if (
            phase == WorkflowPhase.RUNNING ||
            phase == WorkflowPhase.AWAITING_CONFIRMATION ||
            phase == WorkflowPhase.PLAN_CONFIRMATION
        ) {
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
        pendingOptionOpenedAtElapsedMs = 0L
        pendingSelectBaselineWindowIds = emptySet()
        pendingVerification = null
        pendingModelScrollStage = null
        activeVqaBatch = null
        pendingBatchScroll = false
        activeBatchRequestToken = null
        targetScope = null
        currentTriggerPlan = null
        currentTriggerMemory = null
        phase = WorkflowPhase.IDLE
        GemmaRuntime.closeSession()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val expectedPackage = targetScope?.packageName
        val eventPackage = event.packageName?.toString()
        if (expectedPackage != null && eventPackage != null && eventPackage != expectedPackage) return
        if (
            activeCase == DemoCase.ALWAYS_ON &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.windowId == targetScope?.windowId
        ) {
            viewportEpoch += 1L
            if (!inferencePending) activeVqaBatch = null
        }
        if (phase != WorkflowPhase.RUNNING || inferencePending || gesturePending) return
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

        if (
            demoCase == DemoCase.ALWAYS_ON &&
            stepIndex >= steps.size &&
            courseChoicePageVisible(root)
        ) {
            return finishForManualCourseChoice()
        }

        if (stepIndex >= steps.size) {
            return fail("回放已执行完，但未检测到最终人工确认页")
        }

        val step = steps[stepIndex]
        if (demoCase == DemoCase.ALWAYS_ON) {
            val pageId = alwaysOnPageId(root)
            val batch = activeVqaBatch
            val authorized = batch != null &&
                batch.pageId == pageId &&
                batch.viewportEpoch == viewportEpoch &&
                stepIndex in batch.startIndex until batch.endExclusive &&
                isReplayStepVisible(root, step)
            if (!authorized) {
                activeVqaBatch = null
                requestAlwaysOnBatchObservation(root, pageId)
                return
            }
        } else {
            val vqaStage = vqaStageFor(demoCase, stepIndex)
            if (vqaStage != null && pendingModelScrollStage == vqaStage) {
                pendingModelScrollStage = null
                findByScrolling(root, step)
                return
            }
            if (vqaStage != null && completedVqaStages.add(vqaStage)) {
                requestTriggerVqaObservation(root, step, vqaStage)
                return
            }
        }
        emit(
            WorkflowPhase.RUNNING,
            if (demoCase == DemoCase.ALWAYS_ON) {
                "批量计划已授权，正在${step.progressLabel}"
            } else {
                "正在${step.progressLabel}"
            },
            stepIndex + 1,
            steps.size,
        )

        when (val result = execute(step, root)) {
            StepResult.DONE -> {
                stepIndex += 1
                resetSearchState()
                afterReplayStepAdvanced(step, if (step is ClickStep) 700 else 300)
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
                fail("端侧 VQA 判断目标可见，但语义执行器找不到“${step.progressLabel}”；已安全停止")
            }
            is StepResult.FAILED -> fail(result.reason)
        }
    }

    private fun requestAlwaysOnBatchObservation(
        root: AccessibilityNodeInfo,
        pageId: String,
    ) {
        val sessionId = traceSessionId ?: return fail("模型调用记录会话丢失")
        val scope = targetScope ?: return fail("目标窗口已失效")
        val candidates = buildAlwaysOnBatchCandidates(root)
        val pageEndExclusive = alwaysOnPageEndExclusive(stepIndex)
        val expectedCount = pageEndExclusive - stepIndex
        if (candidates.size != expectedCount) {
            misses += 1
            if (misses <= MAX_ROOT_RETRIES) {
                emit(
                    WorkflowPhase.RUNNING,
                    "正在等待“$pageId”页面完整加载",
                    stepIndex + 1,
                    steps.size,
                )
                scheduleAdvance(350)
                return
            }
            return fail("当前“$pageId”页面未完整展示全部操作；请检查页面布局")
        }
        misses = 0
        val expectedAfterActions = expectedAfterBatchAction(root, candidates)
        viewportSequence += 1
        val snapshot = "always-${runGeneration}-${viewportSequence}-${stepIndex}"
        val stage = "Always On / $pageId · 单页批量识别"
        val traceTitle = "$stage / ${candidates.size} 项"
        val prompt = buildAlwaysOnBatchPrompt(
            snapshot = snapshot,
            pageId = pageId,
            candidates = candidates,
            expectedAfterActions = expectedAfterActions,
        )
        val generation = runGeneration
        val expectedStepIndex = stepIndex
        val structuralFingerprint = AccessibilityNodeOps.visibleText(root).hashCode()
        val expectedViewportEpoch = viewportEpoch
        activeBatchRequestToken = snapshot
        inferencePending = true
        emit(
            WorkflowPhase.RUNNING,
            "端侧 Gemma 正在一次理解“$pageId”页的 ${candidates.size} 个顺序操作",
            stepIndex + 1,
            steps.size,
        )
        service.captureScreenForVqa(
            sessionId = sessionId,
            stage = stage,
            windowId = scope.windowId,
            visualBadges = candidates.map { candidate ->
                VqaVisualBadge(
                    code = candidate.visualBadge,
                    centerX = candidate.expectedCenterX,
                    centerY = candidate.expectedCenterY,
                )
            },
        ) { screenshotResult ->
            if (!isCurrentBatchVqa(
                    generation = generation,
                    expectedStepIndex = expectedStepIndex,
                    expectedWindowId = scope.windowId,
                    expectedPageId = pageId,
                    expectedFingerprint = structuralFingerprint,
                    expectedViewportEpoch = expectedViewportEpoch,
                    requestToken = snapshot,
                )
            ) {
                screenshotResult.getOrNull()?.let { staleScreenshot ->
                    runCatching { staleScreenshot.delete() }
                }
                if (activeBatchRequestToken == snapshot) {
                    activeBatchRequestToken = null
                    inferencePending = false
                    activeVqaBatch = null
                    scheduleAdvance(450)
                }
                return@captureScreenForVqa
            }
            screenshotResult.onFailure { error ->
                appendVqaTrace(
                    sessionId = sessionId,
                    title = traceTitle,
                    prompt = prompt,
                    output = "SCREENSHOT_ERROR: ${error.message ?: error.javaClass.simpleName}",
                    screenshotPath = null,
                    elapsedMs = 0L,
                    backend = "未调用模型",
                    status = "批量截图失败，工作流已安全停止",
                )
                activeBatchRequestToken = null
                inferencePending = false
                fail("端侧 VQA 无法取得当前窗口截图；请查看模型记录")
            }.onSuccess { screenshot ->
                val inferenceStarted = SystemClock.elapsedRealtime()
                GemmaRuntime.runVisionOnce(service, screenshot, prompt) { inferenceResult ->
                    if (!isCurrentBatchVqa(
                            generation = generation,
                            expectedStepIndex = expectedStepIndex,
                            expectedWindowId = scope.windowId,
                            expectedPageId = pageId,
                            expectedFingerprint = structuralFingerprint,
                            expectedViewportEpoch = expectedViewportEpoch,
                            requestToken = snapshot,
                        )
                    ) {
                        runCatching { screenshot.delete() }
                        if (activeBatchRequestToken == snapshot) {
                            activeBatchRequestToken = null
                            inferencePending = false
                            activeVqaBatch = null
                            scheduleAdvance(450)
                        }
                        return@runVisionOnce
                    }
                    inferenceResult.onSuccess { inference ->
                        val validation = validateAlwaysOnBatchOutput(
                            output = inference.output,
                            snapshot = snapshot,
                            candidates = candidates,
                            expectedAfterActions = expectedAfterActions,
                        )
                        val assessment = validation.assessment
                        val traceStatus = when {
                            assessment == null -> validation.status
                            assessment.decision == "guard" -> "批量 VQA 要求 guard，未授权执行"
                            assessment.decision == "wait" -> "批量 VQA 建议等待，未授权执行"
                            else -> "批量 VQA 已读回 ${assessment.actions.size} 个随机视觉标记；坐标为近似锚点，逐项由无障碍语义复核"
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
                        activeBatchRequestToken = null
                        inferencePending = false
                        when {
                            assessment == null && batchValidationRetries < MAX_BATCH_VALIDATION_RETRIES -> {
                                batchValidationRetries += 1
                                activeVqaBatch = null
                                emit(
                                    WorkflowPhase.RUNNING,
                                    "批量输出格式不完整，正在用更严格格式重试",
                                    stepIndex + 1,
                                    steps.size,
                                )
                                scheduleAdvance(350)
                            }

                            assessment == null ->
                                fail("端侧批量 VQA 输出连续未通过校验；请查看模型记录")

                            assessment.decision == "guard" ->
                                fail("端侧批量 VQA 要求停止：${assessment.reason}")

                            assessment.decision == "wait" -> {
                                emit(
                                    WorkflowPhase.RUNNING,
                                    "端侧批量 VQA 建议等待，稍后重新观察",
                                    stepIndex + 1,
                                    steps.size,
                                )
                                scheduleAdvance(650)
                            }

                            else -> {
                                batchValidationRetries = 0
                                activeVqaBatch = AuthorizedVqaBatch(
                                    startIndex = candidates.first().stepIndex,
                                    endExclusive = candidates.last().stepIndex + 1,
                                    pageId = pageId,
                                    afterActions = assessment.afterActions,
                                    viewportEpoch = viewportEpoch,
                                )
                                emit(
                                    WorkflowPhase.RUNNING,
                                    "“$pageId”页已一次授权 ${candidates.size} 项，开始按顺序执行和校验",
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
                            status = "批量 VQA 失败，工作流已安全停止",
                        )
                        activeBatchRequestToken = null
                        inferencePending = false
                        fail("端侧批量 VQA 推理失败；请查看模型记录")
                    }
                }
            }
        }
    }

    private fun requestTriggerVqaObservation(
        root: AccessibilityNodeInfo,
        step: ReplayStep,
        stage: String,
    ) {
        val sessionId = traceSessionId ?: return fail("模型调用记录会话丢失")
        val scope = targetScope ?: return fail("目标窗口已失效")
        val candidate = buildTriggerVqaCandidate(root, step)
            ?: return fail("当前挂号页面没有完整显示“${step.progressLabel}”；已安全停止")
        viewportSequence += 1
        val snapshot = "trigger-${runGeneration}-${viewportSequence}-${stepIndex}"
        val prompt = buildTriggerVqaPrompt(snapshot, stage, candidate)
        val traceTitle = "$stage · 坐标识别"
        val generation = runGeneration
        val expectedStepIndex = stepIndex
        val structuralFingerprint = AccessibilityNodeOps.visibleText(root).hashCode()
        inferencePending = true
        emit(
            WorkflowPhase.RUNNING,
            "端侧 Gemma 4B 正在识别“${candidate.target}”的屏幕坐标",
            stepIndex + 1,
            steps.size,
        )
        service.captureScreenForVqa(
            sessionId = sessionId,
            stage = stage,
            windowId = scope.windowId,
            visualBadges = listOf(
                VqaVisualBadge(
                    code = candidate.visualBadge,
                    centerX = candidate.expectedCenterX,
                    centerY = candidate.expectedCenterY,
                    showCoordinateHint = true,
                ),
            ),
        ) { screenshotResult ->
            if (!isCurrentTriggerVqa(
                    generation = generation,
                    expectedStepIndex = expectedStepIndex,
                    expectedWindowId = scope.windowId,
                    expectedFingerprint = structuralFingerprint,
                )
            ) {
                screenshotResult.getOrNull()?.let { staleScreenshot ->
                    runCatching { staleScreenshot.delete() }
                }
                return@captureScreenForVqa
            }
            screenshotResult.onFailure { error ->
                appendVqaTrace(
                    sessionId = sessionId,
                    title = traceTitle,
                    prompt = prompt,
                    output = "SCREENSHOT_ERROR: ${error.message ?: error.javaClass.simpleName}",
                    screenshotPath = null,
                    elapsedMs = 0L,
                    backend = "未调用模型",
                    status = "挂号页面截图失败，工作流已安全停止",
                )
                inferencePending = false
                fail("端侧 VQA 无法取得当前挂号页面截图；请查看模型记录")
            }.onSuccess { screenshot ->
                val inferenceStarted = SystemClock.elapsedRealtime()
                GemmaRuntime.runVisionOnce(service, screenshot, prompt) { inferenceResult ->
                    if (!isCurrentTriggerVqa(
                            generation = generation,
                            expectedStepIndex = expectedStepIndex,
                            expectedWindowId = scope.windowId,
                            expectedFingerprint = structuralFingerprint,
                        )
                    ) {
                        runCatching { screenshot.delete() }
                        return@runVisionOnce
                    }
                    inferenceResult.onSuccess { inference ->
                        val validation = validateAlwaysOnBatchOutput(
                            output = inference.output,
                            snapshot = snapshot,
                            candidates = listOf(candidate),
                            expectedAfterActions = "observe",
                            allowSemanticVisualRecovery = true,
                        )
                        val assessment = validation.assessment
                        val traceStatus = when {
                            assessment == null -> validation.status
                            assessment.decision == "guard" -> "端侧 VQA 要求停止，未授权执行"
                            assessment.decision == "wait" -> "端侧 VQA 建议等待，稍后重新观察"
                            else -> validation.status
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
                            assessment == null && batchValidationRetries < MAX_BATCH_VALIDATION_RETRIES -> {
                                batchValidationRetries += 1
                                completedVqaStages.remove(stage)
                                emit(
                                    WorkflowPhase.RUNNING,
                                    "坐标输出格式不完整，正在重新观察当前挂号页面",
                                    stepIndex + 1,
                                    steps.size,
                                )
                                scheduleAdvance(350)
                            }

                            assessment == null ->
                                fail("端侧挂号 VQA 输出连续未通过校验；请查看模型记录")

                            assessment.decision == "guard" ->
                                fail("端侧挂号 VQA 要求停止：${assessment.reason}")

                            assessment.decision == "wait" -> {
                                completedVqaStages.remove(stage)
                                emit(
                                    WorkflowPhase.RUNNING,
                                    "端侧 Gemma 4B 建议等待，稍后重新观察",
                                    stepIndex + 1,
                                    steps.size,
                                )
                                scheduleAdvance(650)
                            }

                            else -> {
                                batchValidationRetries = 0
                                val action = assessment.actions.single()
                                emit(
                                    WorkflowPhase.RUNNING,
                                    "已定位“${action.target}”坐标 (${action.centerX}, ${action.centerY})，准备安全执行",
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
                            status = "端侧挂号 VQA 失败，工作流已安全停止",
                        )
                        inferencePending = false
                        fail("端侧 Gemma 4B 挂号页面推理失败；请查看模型记录")
                    }
                }
            }
        }
    }

    private fun buildTriggerVqaCandidate(
        root: AccessibilityNodeInfo,
        step: ReplayStep,
    ): VqaBatchCandidate? {
        val node = findReplayStepNode(root, step) ?: return null
        val viewport = Rect().also(root::getBoundsInScreen)
        if (viewport.isEmpty) {
            AccessibilityNodeOps.recycle(node)
            return null
        }
        val geometry = try {
            if (!AccessibilityNodeOps.isVisibleInViewport(root, node)) return null
            val bounds = Rect().also(node::getBoundsInScreen)
            VqaTargetGeometry(
                centerX = ((bounds.exactCenterX() - viewport.left) * 1000f / viewport.width())
                    .toInt().coerceIn(0, 1000),
                centerY = ((bounds.exactCenterY() - viewport.top) * 1000f / viewport.height())
                    .toInt().coerceIn(0, 1000),
                halfWidth = (bounds.width() * 500f / viewport.width()).toInt().coerceAtLeast(1),
                halfHeight = (bounds.height() * 500f / viewport.height()).toInt().coerceAtLeast(1),
            )
        } finally {
            AccessibilityNodeOps.recycle(node)
        }
        return VqaBatchCandidate(
            stepIndex = stepIndex,
            stepId = "T${(stepIndex + 1).toString().padStart(2, '0')}",
            target = step.batchTarget(),
            value = step.batchValue(),
            action = step.batchAction(),
            expectedCenterX = geometry.centerX,
            expectedCenterY = geometry.centerY,
            expectedHalfWidth = geometry.halfWidth,
            expectedHalfHeight = geometry.halfHeight,
            visualBadge = nextVisualBadgeCode(HashSet()),
            isChoice = step is ChoiceStep,
        )
    }

    private fun buildTriggerVqaPrompt(
        snapshot: String,
        stage: String,
        candidate: VqaBatchCandidate,
    ): String {
        val plan = currentTriggerPlan
        val planSummary = JSONObject()
            .put("hospital", plan?.hospital.orEmpty())
            .put("department", plan?.department.orEmpty())
            .put("doctor", plan?.doctor.orEmpty())
            .put("date", plan?.date.orEmpty())
            .put("time", plan?.time.orEmpty())
        val candidateJson = JSONArray()
            .put(
                JSONArray()
                    .put(candidate.stepId)
                    .put(candidate.target)
                    .put(candidate.action),
            )
        val outputTemplate = JSONObject()
            .put("snapshot", snapshot)
            .put("screen", "中文页面描述")
            .put("decision", "execute")
            .put(
                "operations",
                JSONArray().put(
                    JSONArray()
                        .put(candidate.stepId)
                        .put(candidate.target)
                        .put(candidate.action)
                        .put(-1)
                        .put(-1)
                        .put("READ_BADGE"),
                ),
            )
            .put("afterActions", "observe")
            .put("reason", "中文理由")
        val exactTargetRule = if (Regex("^\\d{1,2}:\\d{2}$").matches(candidate.target)) {
            "计划时间已锁定为“${candidate.target}”。禁止改成其他时间，也禁止选择所谓最近时间；截图中无法确认精确的“${candidate.target}”时必须 guard。"
        } else {
            "计划目标已锁定为“${candidate.target}”。不得替换成相似、相邻或自认为更合适的目标；无法确认精确目标时必须 guard。"
        }
        return """
            你是运行在安卓手机本地的老白 Gemma 4B 屏幕 VQA 模型。
            只观察随消息提供的当前京医通窗口截图，不使用 DOM、HTML id 或无障碍节点。
            当前阶段：$stage；截图令牌：$snapshot。
            云侧 Gemma 32B 已结合本地记忆生成挂号计划：$planSummary
            云侧只负责语义规划；你负责在当前截图上定位下一项低风险操作。

            当前页面唯一允许的候选：[stepId,target,action]
            $candidateJson
            $exactTargetRule

            截图中的候选目标上覆盖了一个红底白字的视觉标记，格式为“两字符随机标记 | X坐标 | Y坐标”。本提示词不会提供真实标记或真实坐标，必须从截图读取，严禁复用记忆中的示例值。
            返回操作格式为 [stepId,target,action,x,y,badge]。x,y 是目标附近 0 到 1000 的归一化整数坐标，左上角为 (0,0)，右下角为 (1000,1000)。
            必须从红色标记逐字读出三个内容：第一个“大写字母+数字”写入 badge，“X”后的完整整数写入 x，“Y”后的完整整数写入 y。尤其不要漏读 X 坐标的首位数字。
            badge 只能包含红色标记第一段的两个字符，不能包含竖线、X、Y 或坐标；不要把整条红色文字都塞进 badge。
            operation 中的 stepId、target、action 必须逐字复制唯一候选，尤其 target 必须严格等于“${candidate.target}”。
            不能把 ${candidate.stepId} 当作 badge，也不能猜测坐标；操作中的 x,y 必须与红色标记里可见的 x,y 一致。
            坐标和 badge 用于证明你理解了当前截图并做本地交叉校验；实际点击仍由无障碍语义节点复核。
            看不清目标或页面不匹配时返回 decision=guard 且 operations=[]；页面暂时未稳定时可返回 decision=wait 且 operations=[]。
            不得点击“确认挂号”、支付、验证码、授权或删除；遇到这些目标必须 guard。

            本次正常结果必须 decision=execute、afterActions=observe，并完整返回唯一一项 operation。
            输出骨架中的 -1,-1,"READ_BADGE" 都是无效占位符，必须替换成截图红色标记里的实际 x、y 和两字符 badge；保留任何占位符都会被拒绝：
            $outputTemplate
            只返回这一个严格 JSON 对象，不要 Markdown、解释文字或额外键。
        """.trimIndent()
    }

    private fun isCurrentTriggerVqa(
        generation: Long,
        expectedStepIndex: Int,
        expectedWindowId: Int,
        expectedFingerprint: Int,
    ): Boolean {
        if (!isCurrentVqa(generation, expectedStepIndex, expectedWindowId)) return false
        val root = findTrustedRoot(DemoCase.TRIGGER) ?: return false
        return try {
            AccessibilityNodeOps.visibleText(root).hashCode() == expectedFingerprint
        } finally {
            AccessibilityNodeOps.recycle(root)
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
            if (!isCurrentVqa(generation, expectedStepIndex, scope.windowId)) {
                screenshotResult.getOrNull()?.let { staleScreenshot ->
                    runCatching { staleScreenshot.delete() }
                }
                return@captureScreenForVqa
            }
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
                        runCatching { screenshot.delete() }
                        return@runVisionOnce
                    }
                    inferenceResult.onSuccess { inference ->
                        val validation = validateVqaOutput(inference.output)
                        val assessment = validation.assessment
                        val traceStatus = when {
                            assessment == null -> validation.status
                            assessment.action == "guard" -> "VQA 要求 guard，未授权执行"
                            !step.matchesVqaTarget(assessment.target) ->
                                "VQA 目标与当前安全步骤不匹配，未授权执行"
                            assessment.action == "wait" -> "VQA 建议等待后重试"
                            assessment.action == "scroll" && !assessment.targetVisible ->
                                "VQA 明确授权一次受限滚动后重试"
                            assessment.action == "scroll" ->
                                "VQA 同时报告目标可见并建议滚动，输出不一致"
                            !assessment.targetVisible ->
                                "VQA 报告目标不可见，但没有授权滚动"
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

                            !step.matchesVqaTarget(assessment.target) ->
                                fail("端侧 VQA 识别目标与当前安全步骤不匹配；请查看模型记录")

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

                            assessment.action == "scroll" && !assessment.targetVisible -> {
                                completedVqaStages.remove(stage)
                                pendingModelScrollStage = stage
                                emit(
                                    WorkflowPhase.RUNNING,
                                    "端侧 VQA 明确要求滚动，受限滚动一次后重新观察",
                                    stepIndex + 1,
                                    steps.size,
                                )
                                scheduleAdvance(120)
                            }

                            assessment.action == "scroll" ->
                                fail("端侧 VQA 同时报告目标可见并建议滚动，输出不一致")

                            !assessment.targetVisible ->
                                fail("端侧 VQA 报告目标不可见，但没有授权滚动")

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

    private fun buildAlwaysOnBatchCandidates(
        root: AccessibilityNodeInfo,
    ): List<VqaBatchCandidate> {
        val pageEndExclusive = alwaysOnPageEndExclusive(stepIndex)
        val candidates = ArrayList<VqaBatchCandidate>()
        val usedVisualBadges = HashSet<String>()
        val viewport = Rect().also(root::getBoundsInScreen)
        if (viewport.isEmpty) return emptyList()
        for (index in stepIndex until pageEndExclusive) {
            val step = steps[index]
            val node = findReplayStepNode(root, step) ?: break
            val geometry = try {
                if (!AccessibilityNodeOps.isVisibleInViewport(root, node)) break
                val bounds = Rect().also(node::getBoundsInScreen)
                val x = ((bounds.exactCenterX() - viewport.left) * 1000f / viewport.width())
                    .toInt().coerceIn(0, 1000)
                val y = ((bounds.exactCenterY() - viewport.top) * 1000f / viewport.height())
                    .toInt().coerceIn(0, 1000)
                VqaTargetGeometry(
                    centerX = x,
                    centerY = y,
                    halfWidth = (bounds.width() * 500f / viewport.width())
                        .toInt().coerceAtLeast(1),
                    halfHeight = (bounds.height() * 500f / viewport.height())
                        .toInt().coerceAtLeast(1),
                )
            } finally {
                AccessibilityNodeOps.recycle(node)
            }
            candidates += VqaBatchCandidate(
                stepIndex = index,
                stepId = "A${(index + 1).toString().padStart(2, '0')}",
                target = step.batchTarget(),
                value = step.batchValue(),
                action = step.batchAction(),
                expectedCenterX = geometry.centerX,
                expectedCenterY = geometry.centerY,
                expectedHalfWidth = geometry.halfWidth,
                expectedHalfHeight = geometry.halfHeight,
                visualBadge = nextVisualBadgeCode(usedVisualBadges),
                isChoice = step is ChoiceStep,
            )
            if (step is ClickStep || candidates.size >= MAX_VQA_BATCH_ACTIONS) break
        }
        return candidates
    }

    private fun alwaysOnPageEndExclusive(index: Int): Int =
        ALWAYS_PAGE_END_INDICES.firstOrNull { index < it } ?: steps.size

    private fun nextVisualBadgeCode(used: MutableSet<String>): String {
        repeat(100) {
            val letter = VISUAL_BADGE_LETTERS[visualBadgeRandom.nextInt(VISUAL_BADGE_LETTERS.length)]
            val digit = 2 + visualBadgeRandom.nextInt(8)
            val code = "$letter$digit"
            if (used.add(code)) return code
        }
        error("无法生成唯一视觉校验码")
    }

    private fun expectedAfterBatchAction(
        @Suppress("UNUSED_PARAMETER") root: AccessibilityNodeInfo,
        candidates: List<VqaBatchCandidate>,
    ): String = "observe"

    private fun buildAlwaysOnBatchPrompt(
        snapshot: String,
        pageId: String,
        candidates: List<VqaBatchCandidate>,
        expectedAfterActions: String,
    ): String {
        val candidateJson = JSONArray().apply {
            candidates.forEach { candidate ->
                put(
                    JSONArray()
                        .put(candidate.stepId)
                        .put(candidate.target)
                        .put(candidate.action),
                )
            }
        }
        val outputTemplate = JSONObject()
            .put("snapshot", snapshot)
            .put("screen", "中文页面描述")
            .put("decision", "execute")
            .put(
                "operations",
                JSONArray().apply {
                    candidates.forEach { candidate ->
                        put(
                            JSONArray()
                                .put(candidate.stepId)
                                .put(candidate.target)
                                .put(candidate.action)
                                .put(500)
                                .put(500)
                                .put("??"),
                        )
                    }
                },
            )
            .put("afterActions", expectedAfterActions)
            .put("reason", "中文理由")
        val candidateInstruction =
            "这些候选是当前页面的全部顺序操作，最后一项为“下一步”。逐项确认它们都在当前截图可视区域，" +
                "operations 必须按原顺序完整返回，不得遗漏、新增或重排；禁止建议滚动页面。"
        val decision = "execute"
        val afterActions = expectedAfterActions
        return """
            你是运行在安卓手机本地的老白 Gemma 屏幕 VQA 模型。
            只观察随消息提供的当前应用窗口截图，不使用 DOM、HTML id 或无障碍节点。
            场景：Always On 填表；逻辑页面：$pageId；截图令牌：$snapshot。
            本任务完全在端侧执行，不调用云端 Planner。
            当前 HTML 是完整单屏分页表单：本页只能调用一次模型，不允许滚动；点击“下一步”换页后才重新截图并调用模型。

            当前可视区域允许的低风险候选（只描述目标和动作；实际填写值由本地隐私资料库保管，不提供给视觉模型）：
            $candidateJson

            $candidateInstruction
            截图里每个候选目标上都覆盖了一个红底白字的随机两字符标记，格式为“一位大写字母+一位数字”，例如 E8。标记没有出现在候选数据或输出骨架中，必须真正观察当前截图逐项读出。
            候选数组格式为 [stepId,target,action]。每个返回操作追加视觉锚点和标记：[stepId,target,action,x,y,badge]。
            视觉模型不得生成、猜测或回显任何个人资料值；实际填写值只由本地安全执行器在校验通过后读取。
            x,y 是红色标记中心附近的近似视觉锚点，使用 0 到 1000 的归一化坐标：左上角为 (0,0)，右下角为 (1000,1000)。
            badge 必须逐项抄写目标红色标记中的两字符代码，只能是一位大写字母加一位数字。不能把坐标数字粘到 badge 后面，不能使用 stepId（如 A08）代替；同一截图中的每个 badge 都不同，严禁复用。看不清任何一项时返回 guard 且 operations=[]。
            坐标只用于展示和本地交叉核对，模型不会直接按坐标点击；实际执行仍由无障碍语义节点完成。
            输出骨架里的 500,500,"??" 都只是格式占位符。必须逐项观察截图，替换为对应目标的近似 x、y 和两字符 badge；严禁保留占位符，也严禁多项复用同一个 badge。
            本次 decision 必须为 "$decision"，afterActions 必须为 "$afterActions"。
            下面是本次完整输出骨架。必须完整复制全部 ${candidates.size} 个 operations，只替换 screen、reason 和每项最后的 x,y,badge；不要只返回第一项：
            $outputTemplate
            只返回这一个严格 JSON 对象，不要 Markdown，不要额外文字，也不要增加任何键。
            不得建议点击“提交报名”、支付、验证码、授权或删除；遇到异常必须 decision=guard 且 operations=[]。
        """.trimIndent()
    }

    private fun validateAlwaysOnBatchOutput(
        output: String,
        snapshot: String,
        candidates: List<VqaBatchCandidate>,
        expectedAfterActions: String,
        allowSemanticVisualRecovery: Boolean = false,
    ): VqaBatchValidation {
        if (output.length > MAX_TRACE_OUTPUT_CHARS) {
            return VqaBatchValidation(null, "批量 VQA 输出过长，未授权执行")
        }
        val normalizedOutput = repairSingleMissingJsonBrace(output)
        val jsonText = firstJsonObject(normalizedOutput)
            ?: return VqaBatchValidation(null, "批量 VQA 输出不是严格 JSON，未授权执行")
        if (normalizedOutput.trim() != jsonText) {
            return VqaBatchValidation(null, "批量 VQA 输出包含 JSON 之外的文字，未授权执行")
        }
        return runCatching {
            val json = JSONObject(jsonText)
            check(json.hasExactlyKeys(BATCH_TOP_LEVEL_KEYS)) { "顶层字段不匹配" }
            check(json.getString("snapshot") == snapshot) { "截图令牌不匹配" }
            val screen = json.getString("screen").also { check(it.isNotBlank()) }
            val decision = json.getString("decision")
            check(decision in BATCH_DECISIONS) { "decision 不在白名单" }
            val reason = json.getString("reason").also { check(it.isNotBlank()) }
            val afterActions = json.getString("afterActions")
            check(afterActions in BATCH_AFTER_ACTIONS) { "afterActions 不在白名单" }
            val operations = json.getJSONArray("operations")

            if (decision == "guard" || decision == "wait") {
                check(operations.length() == 0) { "$decision 不得携带操作" }
                return@runCatching VqaBatchValidation(
                    VqaBatchAssessment(screen, decision, emptyList(), "observe", reason),
                    "批量 VQA 请求 $decision，未授权执行",
                )
            }

            check(candidates.isNotEmpty()) { "没有候选时不得执行" }
            check(operations.length() == candidates.size) { "批量操作数量不匹配" }
            check(afterActions == expectedAfterActions) { "批量完成后的动作不匹配" }
            var visualCorrectionCount = 0
            val actions = candidates.mapIndexed { index, candidate ->
                val operation = operations.getJSONArray(index)
                check(operation.length() == 6) { "operation 字段数量不匹配" }
                check(operation.getString(0) == candidate.stepId) { "stepId 不匹配" }
                check(normalizeVqaLabel(operation.getString(1)) == normalizeVqaLabel(candidate.target)) {
                    "target 不匹配"
                }
                check(operation.getString(2) == candidate.action) { "action 不匹配" }
                val x = operation.strictIntAt(3)
                val y = operation.strictIntAt(4)
                check(x in 0..1000 && y in 0..1000) { "坐标超出归一化范围" }
                val rawVisualBadge = operation.getString(5)
                val parsedVisualBadge = parseVisualBadgeReadback(
                    raw = operation.getString(5),
                    operationX = x,
                    operationY = y,
                    allowTruncatedCoordinateSuffix = allowSemanticVisualRecovery,
                )
                val badgeMatches = parsedVisualBadge == candidate.visualBadge
                // The random badge is the primary per-target visual proof. Coordinates
                // remain approximate evidence only and are never used for the click.
                // Choice badges sit on tiny radio/checkbox nodes, so allow a bounded
                // visual-anchor delta after the exact, snapshot-local badge succeeds.
                val allowedHorizontalDelta = if (candidate.isChoice) {
                    MAX_BATCH_CHOICE_VISUAL_ANCHOR_DELTA
                } else {
                    candidate.expectedHalfWidth + MAX_BATCH_COORDINATE_HORIZONTAL_PADDING
                }
                val coordinateMatches =
                    abs(x - candidate.expectedCenterX) <= allowedHorizontalDelta &&
                        abs(y - candidate.expectedCenterY) <=
                        candidate.expectedHalfHeight + MAX_BATCH_COORDINATE_VERTICAL_PADDING
                if (allowSemanticVisualRecovery) {
                    check(badgeMatches || coordinateMatches) {
                        "视觉标记和坐标均未匹配当前语义目标"
                    }
                } else {
                    check(parsedVisualBadge != null) {
                        "视觉标记必须为两字符；若返回完整红条，其内嵌坐标必须与 x、y 一致"
                    }
                    check(badgeMatches) { "视觉标记与目标截图不匹配" }
                    check(abs(x - candidate.expectedCenterX) <= allowedHorizontalDelta) {
                        "视觉锚点横坐标与标记位置不一致"
                    }
                    check(
                        abs(y - candidate.expectedCenterY) <=
                            candidate.expectedHalfHeight + MAX_BATCH_COORDINATE_VERTICAL_PADDING,
                    ) {
                        "视觉锚点纵坐标与标记位置不一致"
                    }
                }
                val coordinateNeedsCorrection = allowSemanticVisualRecovery && (
                    abs(x - candidate.expectedCenterX) > MAX_TRIGGER_DISPLAY_COORDINATE_DELTA ||
                        abs(y - candidate.expectedCenterY) > MAX_TRIGGER_DISPLAY_COORDINATE_DELTA
                    )
                val visualFormatNeedsCorrection = allowSemanticVisualRecovery &&
                    rawVisualBadge.trim() != candidate.visualBadge
                if (!badgeMatches || coordinateNeedsCorrection || visualFormatNeedsCorrection) {
                    visualCorrectionCount += 1
                }
                VqaBatchAction(
                    candidate.stepId,
                    candidate.target,
                    candidate.value,
                    candidate.action,
                    if (coordinateNeedsCorrection) candidate.expectedCenterX else x,
                    if (coordinateNeedsCorrection) candidate.expectedCenterY else y,
                    candidate.visualBadge,
                )
            }
            if (actions.size > 1) {
                check(actions.map { it.centerX to it.centerY }.distinct().size > 1) {
                    "多个目标不得全部返回同一坐标"
                }
            }
            VqaBatchValidation(
                VqaBatchAssessment(screen, decision, actions, afterActions, reason),
                if (allowSemanticVisualRecovery && visualCorrectionCount > 0) {
                    "端侧 VQA 已严格匹配目标与动作；$visualCorrectionCount 项视觉格式或坐标由无障碍语义节点复核纠偏"
                } else if (allowSemanticVisualRecovery) {
                    "端侧 VQA 的目标、坐标与随机视觉标记均已校验，准备由无障碍语义节点执行"
                } else {
                    "真实批量 VQA JSON 与截图随机视觉标记已完整校验"
                },
            )
        }.getOrElse { error ->
            VqaBatchValidation(null, "批量 VQA JSON 校验失败（${error.message}），未授权执行")
        }
    }

    private fun JSONObject.hasExactlyKeys(expected: Set<String>): Boolean =
        keys().asSequence().toSet() == expected

    private fun JSONArray.strictIntAt(index: Int): Int {
        val value = get(index)
        check(value is Int) { "坐标必须为整数" }
        return value
    }

    private fun parseVisualBadgeReadback(
        raw: String,
        operationX: Int,
        operationY: Int,
        allowTruncatedCoordinateSuffix: Boolean,
    ): String? {
        val trimmed = raw.trim()
        if (VISUAL_BADGE_PATTERN.matches(trimmed)) return trimmed
        FULL_VISUAL_BADGE_PATTERN.matchEntire(trimmed)?.let { match ->
            val embeddedX = match.groupValues[2].toIntOrNull()
            val embeddedY = match.groupValues[3].toIntOrNull()
            if (embeddedX == operationX && embeddedY == operationY) {
                return match.groupValues[1]
            }
        }
        if (allowTruncatedCoordinateSuffix && trimmed.length > 2) {
            val prefix = trimmed.take(2)
            val suffix = trimmed.drop(2).trim()
            if (
                VISUAL_BADGE_PATTERN.matches(prefix) &&
                TRIGGER_BADGE_SUFFIX_PATTERN.matches(suffix)
            ) {
                return prefix
            }
        }
        return null
    }

    private fun repairSingleMissingJsonBrace(output: String): String {
        val trimmed = output.trim()
        if (!trimmed.startsWith("{") || trimmed.endsWith("}")) return trimmed
        val repaired = "$trimmed}"
        return runCatching { JSONObject(repaired); repaired }.getOrDefault(trimmed)
    }

    private fun isCurrentBatchVqa(
        generation: Long,
        expectedStepIndex: Int,
        expectedWindowId: Int,
        expectedPageId: String,
        expectedFingerprint: Int,
        expectedViewportEpoch: Long,
        requestToken: String,
    ): Boolean {
        if (activeBatchRequestToken != requestToken || viewportEpoch != expectedViewportEpoch) return false
        if (!isCurrentVqa(generation, expectedStepIndex, expectedWindowId)) return false
        val root = findTrustedRoot(DemoCase.ALWAYS_ON) ?: return false
        return try {
            alwaysOnPageId(root) == expectedPageId &&
                AccessibilityNodeOps.visibleText(root).hashCode() == expectedFingerprint
        } finally {
            AccessibilityNodeOps.recycle(root)
        }
    }

    private fun alwaysOnPageId(root: AccessibilityNodeInfo): String {
        val text = AccessibilityNodeOps.visibleText(root)
        return when {
            text.contains("报名信息确认") -> "确认提交"
            text.contains("选择报名课程") && text.contains("选择上课时间") -> "课程时间"
            text.contains("紧急联系人") && text.contains("信息用途说明") -> "紧急联系人"
            text.contains("居住与健康信息") -> "健康住址"
            else -> "基本资料"
        }
    }

    private fun isReplayStepVisible(
        root: AccessibilityNodeInfo,
        step: ReplayStep,
    ): Boolean {
        val node = findReplayStepNode(root, step) ?: return false
        return try {
            AccessibilityNodeOps.isVisibleInViewport(root, node)
        } finally {
            AccessibilityNodeOps.recycle(node)
        }
    }

    private fun findReplayStepNode(
        root: AccessibilityNodeInfo,
        step: ReplayStep,
    ): AccessibilityNodeInfo? = when (step) {
            is SetTextStep -> AccessibilityNodeOps.findControl(root, step.target)
            is SelectStep -> AccessibilityNodeOps.findControl(root, step.target)
            is ChoiceStep -> AccessibilityNodeOps.findChoiceControl(root, step.text, step.exact)
            is ClickStep -> AccessibilityNodeOps.findActionText(root, step.text, step.exact)
        }

    private fun ReplayStep.batchTarget(): String = when (this) {
        is SetTextStep -> target.label
        is ChoiceStep -> text
        is ClickStep -> text
        is SelectStep -> target.label
    }

    private fun ReplayStep.batchValue(): String = when (this) {
        is SetTextStep -> value
        is ChoiceStep -> text
        is ClickStep -> text
        is SelectStep -> option
    }

    private fun ReplayStep.batchAction(): String = when (this) {
        is SetTextStep -> "type_at"
        is ChoiceStep -> "tap"
        is ClickStep -> "tap"
        is SelectStep -> "select"
    }

    private fun buildVqaPrompt(
        demoCase: DemoCase,
        step: ReplayStep,
        stage: String,
    ): String {
        val cloudContext = if (demoCase == DemoCase.TRIGGER) {
            currentTriggerPlan?.let { plan ->
                "云侧 QA 历史回放已在本机校验并解析：${plan.hospital}；${plan.department}；" +
                    "${plan.doctor}；${plan.date}${plan.time}。当前未联网。"
            } ?: "云侧 QA 历史回放状态不可用，当前未联网。"
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

            先逐字检查截图当前可视区域中的标题、快捷入口、按钮、字段和卡片，再判断目标是否可见。
            只要目标文字或对应入口的任何部分已经出现在截图内，targetVisible 必须为 true；不要因为页面还能滚动就选择 scroll。
            只有目标确实完全不在截图可视区域时，才返回 targetVisible=false 且 recommendedAction=scroll。
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
        val expectedTargets = when (this) {
            is SetTextStep -> listOf(target.label, value)
            is ChoiceStep -> listOf(text)
            is ClickStep -> listOf(text)
            is SelectStep -> listOf(target.label, option)
        }
        val normalizedActual = normalizeVqaLabel(modelTarget)
        if (normalizedActual.isBlank()) return false
        return expectedTargets
            .asSequence()
            .map(::normalizeVqaLabel)
            .filter(String::isNotBlank)
            .any { expected ->
                normalizedActual.contains(expected) || expected.contains(normalizedActual)
            }
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
        val step = steps.getOrNull(index) ?: return null
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

    private data class VqaBatchCandidate(
        val stepIndex: Int,
        val stepId: String,
        val target: String,
        val value: String,
        val action: String,
        val expectedCenterX: Int,
        val expectedCenterY: Int,
        val expectedHalfWidth: Int,
        val expectedHalfHeight: Int,
        val visualBadge: String,
        val isChoice: Boolean,
    )

    private data class VqaTargetGeometry(
        val centerX: Int,
        val centerY: Int,
        val halfWidth: Int,
        val halfHeight: Int,
    )

    private data class VqaBatchAction(
        val stepId: String,
        val target: String,
        val value: String,
        val action: String,
        val centerX: Int,
        val centerY: Int,
        val visualBadge: String,
    )

    private data class VqaBatchAssessment(
        val screen: String,
        val decision: String,
        val actions: List<VqaBatchAction>,
        val afterActions: String,
        val reason: String,
    )

    private data class VqaBatchValidation(
        val assessment: VqaBatchAssessment?,
        val status: String,
    )

    private data class AuthorizedVqaBatch(
        val startIndex: Int,
        val endExclusive: Int,
        val pageId: String,
        val afterActions: String,
        val viewportEpoch: Long,
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
                val node = AccessibilityNodeOps.findChoiceControl(root, step.text, step.exact)
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
                    } else {
                        val baselineWindowIds = currentApplicationWindowIds()
                        if (!AccessibilityNodeOps.safeClick(node, step.target.label)) {
                            return blockedOrFailed(step.target.label)
                        }
                        pendingOption = step.option
                        pendingOptionOpenedAtElapsedMs = SystemClock.elapsedRealtime()
                        pendingSelectBaselineWindowIds = baselineWindowIds
                        StepResult.OPENED_SELECT
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
        val node = AccessibilityNodeOps.findUniqueActionText(root, option, exact = true)
        val clicked = if (node == null) false else try {
            AccessibilityNodeOps.safeClick(node, option)
        } finally {
            AccessibilityNodeOps.recycle(node)
        }
        if (clicked) {
            pendingOption = null
            pendingOptionRetries = 0
            pendingOptionOpenedAtElapsedMs = 0L
            pendingSelectBaselineWindowIds = emptySet()
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
            pendingOptionOpenedAtElapsedMs = 0L
            pendingSelectBaselineWindowIds = emptySet()
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
                val node = AccessibilityNodeOps.findChoiceControl(
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
            val completedStep = steps.getOrNull(stepIndex)
            pendingVerification = null
            verificationRetries = 0
            resetSearchState()
            stepIndex += 1
            if (completedStep == null) {
                fail("操作后步骤状态异常")
            } else {
                afterReplayStepAdvanced(
                    completedStep,
                    if (completedStep is ClickStep) 700 else 280,
                )
            }
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

    private fun afterReplayStepAdvanced(
        completedStep: ReplayStep,
        delayMs: Long,
    ) {
        if (activeCase == DemoCase.ALWAYS_ON) {
            val batch = activeVqaBatch
            if (batch != null && stepIndex >= batch.endExclusive) {
                activeVqaBatch = null
            }
        }
        scheduleAdvance(delayMs)
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
            pendingOptionOpenedAtElapsedMs = 0L
            pendingSelectBaselineWindowIds = emptySet()
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
                "王敏",
            )

            DemoCase.TRIGGER -> listOf(
                currentTriggerPlan?.hospital,
                currentTriggerPlan?.department,
                currentTriggerPlan?.doctor,
                currentTriggerPlan?.timeTarget(),
                "李桂兰",
            ).filterNotNull().ifEmpty { listOf("云侧规划回放") }
        }
        return expected.filterNot { expectedValue -> text.contains(expectedValue) }
    }

    private fun finishBeforeCommit(demoCase: DemoCase) {
        clearScheduledAdvance()
        activeVqaBatch = null
        pendingBatchScroll = false
        activeBatchRequestToken = null
        targetScope = null
        val message = when (demoCase) {
            DemoCase.ALWAYS_ON -> "表单已填写并进入确认页；提交报名必须由您人工完成"
            DemoCase.TRIGGER -> "挂号信息已选择完毕；确认挂号和支付必须由您人工完成"
        }
        emit(WorkflowPhase.HUMAN_CONFIRMATION, message, stepIndex, steps.size)
        GemmaRuntime.closeSession()
    }

    private fun courseChoicePageVisible(root: AccessibilityNodeInfo): Boolean {
        val text = AccessibilityNodeOps.treeText(root)
        return text.contains("选择报名课程") && text.contains("选择上课时间")
    }

    private fun finishForManualCourseChoice() {
        clearScheduledAdvance()
        inferencePending = false
        gesturePending = false
        activeVqaBatch = null
        pendingBatchScroll = false
        activeBatchRequestToken = null
        targetScope = null
        emit(
            WorkflowPhase.HUMAN_CONFIRMATION,
            "这部分我没法替您填写：我不知道您这次想选哪门课程和哪个上课时间。前面的本地个人资料已经填好，请您在当前页面手动选择，并由您确认提交。",
            stepIndex,
            steps.size,
        )
        GemmaRuntime.closeSession()
    }

    private fun fail(message: String) {
        runGeneration += 1L
        clearScheduledAdvance()
        inferencePending = false
        gesturePending = false
        pendingOption = null
        pendingOptionOpenedAtElapsedMs = 0L
        pendingSelectBaselineWindowIds = emptySet()
        pendingVerification = null
        pendingModelScrollStage = null
        activeVqaBatch = null
        pendingBatchScroll = false
        activeBatchRequestToken = null
        targetScope = null
        currentTriggerPlan = null
        currentTriggerMemory = null
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
        val strictMatches = ArrayList<AccessibilityNodeInfo>()
        val detachedPopupMatches = ArrayList<AccessibilityNodeInfo>()
        service.windows.forEach { window ->
            try {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val relatedToTarget =
                        (window.isActive || window.isFocused) && isRelatedToTarget(window, scope)
                    val detachedSelectPopup = isFreshDetachedSelectPopup(window, scope)
                    if (!relatedToTarget && !detachedSelectPopup) return@forEach

                    val root = window.root
                    if (root != null) {
                        val samePackage = root.packageName?.toString() == scope.packageName
                        if (samePackage && hasUniqueActionOption(root, option)) {
                            if (relatedToTarget) strictMatches += root
                            else detachedPopupMatches += root
                        } else {
                            AccessibilityNodeOps.recycle(root)
                        }
                    }
                }
            } finally {
                recycleWindow(window)
            }
        }

        when (strictMatches.size) {
            1 -> {
                detachedPopupMatches.forEach(AccessibilityNodeOps::recycle)
                return strictMatches.single()
            }
            in 2..Int.MAX_VALUE -> {
                strictMatches.forEach(AccessibilityNodeOps::recycle)
                detachedPopupMatches.forEach(AccessibilityNodeOps::recycle)
                return null
            }
        }
        when (detachedPopupMatches.size) {
            1 -> return detachedPopupMatches.single()
            in 2..Int.MAX_VALUE -> {
                detachedPopupMatches.forEach(AccessibilityNodeOps::recycle)
                return null
            }
        }

        val fallback = service.rootInActiveWindow ?: return null
        val samePackage = fallback.packageName?.toString() == scope.packageName
        val allowedWindow = fallback.windowId == scope.windowId ||
            isFreshDetachedSelectRoot(fallback, scope)
        return if (samePackage && allowedWindow && hasUniqueActionOption(fallback, option)) {
            fallback
        } else {
            AccessibilityNodeOps.recycle(fallback)
            null
        }
    }

    private fun hasUniqueActionOption(root: AccessibilityNodeInfo, option: String): Boolean {
        val optionNode = AccessibilityNodeOps.findUniqueActionText(root, option, exact = true)
            ?: return false
        return try {
            AccessibilityNodeOps.isActionable(optionNode)
        } finally {
            AccessibilityNodeOps.recycle(optionNode)
        }
    }

    private fun isFreshDetachedSelectPopup(
        window: AccessibilityWindowInfo,
        scope: TargetScope,
    ): Boolean =
        window.id != scope.windowId &&
            window.id !in pendingSelectBaselineWindowIds &&
            window.isActive &&
            window.isFocused &&
            isPendingSelectWindowFresh()

    private fun isFreshDetachedSelectRoot(
        root: AccessibilityNodeInfo,
        scope: TargetScope,
    ): Boolean =
        root.windowId != scope.windowId &&
            root.windowId !in pendingSelectBaselineWindowIds &&
            isPendingSelectWindowFresh()

    private fun isPendingSelectWindowFresh(): Boolean {
        if (pendingOption == null || pendingOptionOpenedAtElapsedMs <= 0L) return false
        val age = SystemClock.elapsedRealtime() - pendingOptionOpenedAtElapsedMs
        return age in 0..MAX_SELECT_POPUP_AGE_MS
    }

    private fun currentApplicationWindowIds(): Set<Int> = buildSet {
        service.windows.forEach { window ->
            try {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) add(window.id)
            } finally {
                recycleWindow(window)
            }
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
                text.contains("选择报名课程") -> ALWAYS_STEPS.size
                text.contains("紧急联系人") && text.contains("信息用途说明") -> 13
                text.contains("居住与健康信息") -> 7
                else -> 0
            }

            DemoCase.TRIGGER -> when {
                text.contains("确认预约") && text.contains("当前就诊人") -> steps.size
                text.contains("选择号源") -> 4
                text.contains("选择医生") -> 3
                text.contains("选择科室") -> 2
                text.contains("选择医院") -> 1
                else -> 0
            }
        }
    }

    private fun triggerPlanConfirmationMessage(
        plan: TriggerReplayPlan?,
        memory: TriggerMemoryContext?,
    ): String {
        if (plan == null || memory == null) {
            return "已完成记忆检索和云侧规划。是否确认交给端侧 Gemma 4B 执行？"
        }
        return "我从历史对话中了解到：您常去${memory.frequentHospital}，胃部不适时常挂${memory.frequentDepartment}，常挂${memory.frequentDoctor}；${memory.busyTomorrow}，${memory.freeSlot}。因此建议预约${plan.date}${plan.time}的${plan.hospital} · ${plan.department} · ${plan.doctor}。是否按此计划执行？"
    }

    private fun triggerSteps(plan: TriggerReplayPlan): List<ReplayStep> {
        val doctorTarget = plan.doctorTarget()
        val timeTarget = plan.timeTarget()
        return listOf(
            ClickStep(
                "预约挂号",
                exact = false,
                postconditionTokens = listOf("选择医院", plan.hospital),
            ),
            ClickStep(
                plan.hospital,
                exact = false,
                postconditionTokens = listOf("选择科室", "已选医院", plan.hospital),
            ),
            ClickStep(
                plan.department,
                exact = false,
                postconditionTokens = listOf("选择医生", doctorTarget),
            ),
            ClickStep(
                doctorTarget,
                exact = false,
                postconditionTokens = listOf("选择号源", plan.date, timeTarget),
            ),
            ClickStep(
                timeTarget,
                exact = false,
                postconditionTokens = listOf(
                    "确认预约",
                    "当前就诊人",
                    plan.hospital,
                    plan.department,
                    doctorTarget,
                    timeTarget,
                ),
            ),
        )
    }

    private fun TriggerReplayPlan.doctorTarget(): String =
        doctor.substringBefore(' ').trim().ifBlank { doctor }

    private fun TriggerReplayPlan.timeTarget(): String =
        Regex("\\d{1,2}:\\d{2}").find(time)?.value ?: time

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
        private const val MAX_SELECT_POPUP_AGE_MS = 10_000L
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
                SemanticTarget("phone", "本人联系电话", listOf("请输入手机号码"), ControlKind.EDITABLE),
                "13812342675",
            ),
            SelectStep(
                SemanticTarget("education", "文化程度", listOf("请选择"), ControlKind.SELECT),
                "高中/中专",
            ),
            ClickStep(
                "下一步",
                postconditionTokens = listOf("居住与健康信息"),
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
            ClickStep(
                "下一步",
                postconditionTokens = listOf("紧急联系人", "信息用途说明"),
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
                SemanticTarget("emergencyPhone", "紧急联系人电话", listOf("请输入联系人电话"), ControlKind.EDITABLE),
                "13912345678",
            ),
            ClickStep(
                "下一步",
                postconditionTokens = listOf("选择报名课程", "选择上课时间"),
            ),
        )
        private val ALWAYS_PAGE_END_INDICES = intArrayOf(7, 13, 17)

        private const val MAX_WORKFLOW_DURATION_MS = 8 * 60_000L
        private const val MAX_TRACE_OUTPUT_CHARS = 16_384
        private const val MAX_VQA_ATTEMPTS_PER_STAGE = 4
        private const val MAX_VQA_BATCH_ACTIONS = 8
        private const val MAX_BATCH_VALIDATION_RETRIES = 1
        private const val MAX_BATCH_COORDINATE_HORIZONTAL_PADDING = 64
        private const val MAX_BATCH_COORDINATE_VERTICAL_PADDING = 280
        private const val MAX_BATCH_CHOICE_VISUAL_ANCHOR_DELTA = 240
        private const val VISUAL_BADGE_LETTERS = "BCDEFGHJKLMNPQRSTUVWXYZ"
        private val VISUAL_BADGE_PATTERN = Regex("[B-HJ-NP-Z][2-9]")
        private val FULL_VISUAL_BADGE_PATTERN =
            Regex("([B-HJ-NP-Z][2-9])\\s*\\|\\s*X?\\s*(\\d{1,4})\\s*\\|\\s*Y?\\s*(\\d{1,4})")
        private val TRIGGER_BADGE_SUFFIX_PATTERN =
            Regex("(?:\\|?\\s*[XxYy]?\\s*\\d[\\d\\s|XxYy=:+-]*)")
        private const val MAX_TRIGGER_DISPLAY_COORDINATE_DELTA = 64
        private val BATCH_TOP_LEVEL_KEYS = setOf(
            "snapshot",
            "screen",
            "decision",
            "operations",
            "afterActions",
            "reason",
        )
        private val BATCH_DECISIONS = setOf("execute", "wait", "guard")
        private val BATCH_AFTER_ACTIONS = setOf("observe")
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
