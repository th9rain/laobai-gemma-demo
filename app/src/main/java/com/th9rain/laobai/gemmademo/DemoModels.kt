package com.th9rain.laobai.gemmademo

data class SeniorProfile(
    val name: String = "李桂兰",
    val ageBand: String = "70s",
    val phoneMasked: String = "138****2675",
    val area: String = "北京市朝阳区望京街道",
    val emergencyContact: String = "女儿 王敏",
    val preferredCourse: String = "智能手机基础课",
    val preferredHospital: String = "北京协和医院",
)

data class FormField(
    val label: String,
    val value: String = "",
    val autofilled: Boolean = false,
)

data class DemoStep(
    val title: String,
    val detail: String,
    val risk: RiskLevel = RiskLevel.Low,
)

enum class RiskLevel {
    Low,
    Medium,
    High,
}

data class FormDemoState(
    val screenOpened: Boolean = false,
    val sentinelDetected: Boolean = false,
    val promptVisible: Boolean = false,
    val filling: Boolean = false,
    val stoppedBeforeSubmit: Boolean = false,
    val fields: List<FormField> = emptyList(),
    val steps: List<DemoStep> = emptyList(),
)

data class HealthDemoState(
    val triggerText: String = "我胃不舒服，帮我挂号",
    val stage: HealthStage = HealthStage.Idle,
    val question: String = "",
    val answer: String = "",
    val symptoms: String = "胃不舒服，伴随轻微恶心",
    val duration: String = "两天",
    val severity: String = "中等",
    val department: String = "",
    val hospital: String = "",
    val planReason: String = "",
    val cloudPlannerUsed: Boolean = false,
    val cloudPlannerStatus: String = "未配置 API Key，默认走本地 workflow",
    val bookingPageOpened: Boolean = false,
    val stoppedBeforeConfirm: Boolean = false,
    val steps: List<DemoStep> = emptyList(),
)

enum class HealthStage {
    Idle,
    Asking,
    Planning,
    Booking,
    Stopped,
}

data class CloudPlannerConfig(
    val apiKey: String = "",
    val enabled: Boolean = false,
)

data class PlannerResult(
    val hospital: String,
    val department: String,
    val reason: String,
    val usedCloud: Boolean,
)
