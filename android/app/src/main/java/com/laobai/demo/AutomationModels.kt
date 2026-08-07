package com.laobai.demo

enum class DemoCase(val displayName: String) {
    ALWAYS_ON("Always On 填表"),
    TRIGGER("京医通挂号"),
}

enum class WorkflowPhase {
    IDLE,
    AWAITING_CONFIRMATION,
    PLAN_CONFIRMATION,
    RUNNING,
    HUMAN_CONFIRMATION,
    ERROR,
    CANCELLED,
}

data class WorkflowUpdate(
    val phase: WorkflowPhase,
    val demoCase: DemoCase? = null,
    val message: String,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
)

/**
 * Every semantic click in the workflow must pass this policy. Gesture fallback
 * is deliberately restricted to a vertical swipe and can never issue a tap.
 */
object AutomationSafetyPolicy {
    private val forbiddenClickTerms = listOf(
        "提交报名",
        "确认挂号",
        "确认预约",
        "立即提交",
        "立即支付",
        "确认支付",
        "支付",
        "缴费",
        "验证码",
        "授权",
        "删除",
    )

    fun blockedTerm(label: String): String? {
        val normalizedLabel = normalize(label)
        return forbiddenClickTerms.firstOrNull { term ->
            normalizedLabel.contains(normalize(term), ignoreCase = true)
        }
    }

    fun isSafeClick(label: String): Boolean = blockedTerm(label) == null

    private fun normalize(value: String): String =
        value.replace(Regex("[\\s，。！？、,.!?；;：:\\-]+"), "")
}
