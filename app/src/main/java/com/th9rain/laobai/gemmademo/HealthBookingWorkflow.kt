package com.th9rain.laobai.gemmademo

import android.content.Context

object HealthBookingWorkflow {
    fun initialState(): HealthDemoState = HealthDemoState()

    fun trigger(text: String): HealthDemoState {
        val triggerText = text.ifBlank { "我胃不舒服，帮我挂号" }
        return HealthDemoState(
            triggerText = triggerText,
            stage = HealthStage.Asking,
            question = "您这个不舒服有多久了？有没有发热、呕吐、胸痛这些情况？",
            steps = listOf(
                DemoStep("Trigger 触发", "用户主动说：$triggerText"),
                DemoStep("端侧问询", "老白先问持续时间、严重程度和危险信号，不做诊断"),
            )
        )
    }

    suspend fun plan(
        context: Context,
        state: HealthDemoState,
        cloudConfig: CloudPlannerConfig,
    ): HealthDemoState {
        val query = "${state.triggerText} ${state.symptoms} ${state.duration} ${state.severity}"
        val localPlan = LocalEmbeddingEngine.recommend(context, query)
        val cloudPrompt = buildCloudPrompt(state, localPlan)

        val cloudText = if (cloudConfig.enabled && cloudConfig.apiKey.isNotBlank()) {
            ArkCloudPlanner.plan(cloudConfig.apiKey, cloudPrompt).getOrNull()
        } else {
            null
        }

        val result = if (cloudText.isNullOrBlank()) {
            localPlan
        } else {
            localPlan.copy(
                reason = "云端 planner 返回：${cloudText.take(160)}\n端侧仍按安全工作流执行挂号页面操作。",
                usedCloud = true,
            )
        }

        return state.copy(
            stage = HealthStage.Booking,
            question = "",
            department = result.department,
            hospital = result.hospital,
            planReason = result.reason,
            cloudPlannerUsed = result.usedCloud,
            cloudPlannerStatus = if (result.usedCloud) "已调用 Ark 云端 planner" else "未调用云端，使用本地 Gemma workflow",
            bookingPageOpened = true,
            stoppedBeforeConfirm = SafetyGuard.shouldStopBefore("确认挂号"),
            steps = state.steps + listOf(
                DemoStep("本地知识库检索", result.reason),
                DemoStep("挂号计划", "医院：${result.hospital}；科室：${result.department}；建议：明天上午"),
                DemoStep("GUI 自动化", "打开模拟挂号页，自动选择 ${result.hospital} / ${result.department}"),
                DemoStep("安全守卫", SafetyGuard.stopMessage("确认挂号 / 支付 / 验证码"), RiskLevel.High),
            )
        )
    }

    fun reset(): HealthDemoState = initialState()

    private fun buildCloudPrompt(state: HealthDemoState, localPlan: PlannerResult): String {
        return """
        你是一个演示用云端挂号规划器。请只基于脱敏摘要做建议，不要诊断，不要要求身份证、手机号或验证码。
        用户目标：看病挂号
        症状摘要：${state.symptoms}
        持续时间：${state.duration}
        严重程度：${state.severity}
        本地建议：${localPlan.hospital} / ${localPlan.department}
        请用一句中文返回适合录屏展示的挂号规划说明。
        """.trimIndent()
    }
}
