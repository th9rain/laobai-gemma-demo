package com.laobai.demo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local, pre-established workflow and profile used by the proactive form assistant. */
object AlwaysOnWorkflowRepository {
    fun recordMatch(context: Context, sessionId: String) {
        val input = JSONObject()
            .put("detectedScene", "个人信息表单")
            .put("triggerMode", "Always On 主动感知")
            .put("userConfirmed", true)
            .put("cloudPlanningRequired", false)
        val output = JSONObject()
            .put("matchedWorkflow", "个人信息表单自动填写")
            .put("workflowVersion", "local-form-v1")
            .put(
                "localProfileFields",
                JSONArray()
                    .put("姓名")
                    .put("出生年月")
                    .put("身份证号（本地加密保存）")
                    .put("联系电话（本地加密保存）")
                    .put("住址")
                    .put("紧急联系人"),
            )
            .put("executor", "端侧 Gemma VQA + 无障碍安全执行器")
            .put("cloudDataSent", false)
            .put("automationBoundary", "进入课程与时间选择页后停止")
            .put("humanInputRequired", JSONArray().put("报名课程").put("上课时间"))
            .put("stopBefore", "选择课程、选择时间、提交报名")

        ModelTraceStore.append(
            context,
            sessionId,
            ModelTraceEntry(
                source = ModelTraceSource.LOCAL_MEMORY,
                title = "匹配本地填表 Workflow 与个人资料",
                modelName = "老白 Always On 本地能力库",
                inputText = input.toString(2),
                outputText = output.toString(2),
                elapsedMs = 24L,
                backend = "APK 本地存储 · 无需云侧规划",
                status = "已命中沉淀的填表 workflow，准备直接执行",
            ),
        )
    }
}
