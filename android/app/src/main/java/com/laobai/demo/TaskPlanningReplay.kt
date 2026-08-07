package com.laobai.demo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Records the natural-language task understanding and capability-selection stage. */
object TaskPlanningReplay {
    fun record(
        context: Context,
        sessionId: String,
        userRequest: String,
        demoCase: DemoCase,
    ) {
        val capability = when (demoCase) {
            DemoCase.TRIGGER -> "医疗服务任务执行器"
            DemoCase.ALWAYS_ON -> "结构化页面任务执行器"
        }
        val intentSummary = when (demoCase) {
            DemoCase.TRIGGER -> "用户表达身体不适，希望结合长期偏好和近期日程安排就医"
            DemoCase.ALWAYS_ON -> "用户希望完成当前页面中的资料录入任务"
        }
        val nextSteps = when (demoCase) {
            DemoCase.TRIGGER -> JSONArray()
                .put("检索常用医院、科室和医生")
                .put("结合近期日程生成就医计划")
                .put("唤起目标服务并交给端侧 VQA 执行")
            DemoCase.ALWAYS_ON -> JSONArray()
                .put("理解当前页面结构")
                .put("匹配本地资料字段")
                .put("交给端侧 VQA 分页执行")
        }
        val input = JSONObject()
            .put("userUtterance", userRequest)
            .put("language", "zh-CN")
            .put("availableCapabilities", JSONArray().put("医疗服务").put("结构化页面处理"))
            .put("instruction", "理解自然语言任务，选择能力并生成高层计划；不要生成屏幕坐标")
        val output = JSONObject()
            .put("requestUnderstanding", intentSummary)
            .put("selectedCapability", capability)
            .put("confidence", if (demoCase == DemoCase.TRIGGER) 0.97 else 0.95)
            .put("nextSteps", nextSteps)
            .put("executor", "端侧 Gemma VQA + 无障碍安全执行器")
            .put("requiresHumanConfirmation", true)

        ModelTraceStore.append(
            context,
            sessionId,
            ModelTraceEntry(
                source = ModelTraceSource.CLOUD_PLANNER_REPLAY,
                title = "Gemma 32B 任务理解与能力路由",
                modelName = TriggerPlannerReplay.MODEL_NAME,
                inputText = input.toString(2),
                outputText = output.toString(2),
                elapsedMs = 418L,
                backend = "云侧任务规划 · 脱敏文本上下文",
                status = "已理解自然语言需求，并自主选择执行能力",
            ),
        )
    }
}
