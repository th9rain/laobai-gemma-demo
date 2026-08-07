package com.laobai.demo

import android.content.Context
import org.json.JSONObject

data class TriggerReplayPlan(
    val hospital: String,
    val department: String,
    val doctor: String,
    val date: String,
    val time: String,
    val reason: String,
)

/** A redacted fixture captured from an earlier cloud-planner run. */
object TriggerPlannerReplay {
    const val MODEL_NAME = "Gemma 32B Dense（云侧 Planner）"

    private fun prompt(memory: TriggerMemoryContext, userRequest: String): String = """
        IMPORTANT: Return one compact valid JSON object only. No markdown. No explanation.
        You are the cloud planner for LaoBai, a senior-assistance mobile GUI agent demo.
        User request: $userRequest
        Normalized symptom summary: 胃部不适持续两天，伴轻微恶心，无胸痛。
        Use the retrieved local-memory summary to choose hospital, department, doctor and an available time.
        Do not return GUI coordinates or GUI actions. Those are produced later from screenshots by the on-device Gemma 4B VQA executor.
        Never include raw ID number, full phone, OTP, payment, or raw medical records.
        Return fields for summary, requestUnderstanding, memoryUsed, appointment, executionPlan and safety.
        Local memory summary: ${memory.cloudSummary()}
        Privacy flags: {"rawScreenUploaded":false,"fullPhoneUploaded":false,"idNumberUploaded":false,"otpUploaded":false,"rawMedicalRecordUploaded":false}
    """.trimIndent()

    private val output: String = """
        {
          "summary": "已结合症状、就医偏好和近期日程，规划后天上午 10:00 前往北京协和医院消化内科门诊。",
          "requestUnderstanding": {
            "intent": "预约挂号",
            "symptomSummary": "胃部不适持续两天，伴轻微恶心，无胸痛",
            "urgency": "当前描述未出现胸痛等演示中的紧急信号；如症状加重应立即线下就医"
          },
          "memoryUsed": [
            {"memoryId": "care-preference-01", "conclusion": "优先选择常去的北京协和医院"},
            {"memoryId": "care-preference-02", "conclusion": "胃部不适优先消化内科门诊"},
            {"memoryId": "care-preference-03", "conclusion": "优先选择常挂的李明主任医师"},
            {"memoryId": "calendar-07", "conclusion": "避开明天的手机培训比赛"},
            {"memoryId": "calendar-08", "conclusion": "选择后天上午 10:00 的空闲时间"}
          ],
          "appointment": {
            "hospital": "北京协和医院",
            "department": "消化内科门诊",
            "doctor": "李明 主任医师",
            "date": "后天",
            "time": "上午 10:00",
            "reason": "症状是胃部不适两天并伴轻微恶心，优先消化内科；明天全天有手机培训比赛安排，后天上午 10:00 空闲；本地记忆显示老人常去北京协和医院并常挂李明主任医师。"
          },
          "executionPlan": {
            "executor": "端侧 Gemma 4B VQA",
            "strategy": "每进入一个新页面重新截图，输出当前目标的坐标和随机视觉标记，再由无障碍语义复核执行",
            "steps": [
              "打开预约挂号",
              "选择北京协和医院",
              "选择消化内科门诊",
              "选择李明主任医师",
              "选择后天上午 10:00 号源"
            ]
          },
          "safety": {
            "cloud_receives_raw_screenshot": false,
            "cloud_receives_full_phone": false,
            "cloud_receives_id_number": false,
            "stop_before": ["确认挂号", "支付", "验证码"]
          }
        }
    """.trimIndent()

    fun record(
        context: Context,
        sessionId: String,
        memory: TriggerMemoryContext,
        userRequest: String,
    ): TriggerReplayPlan {
        val parsed = runCatching(::parseOutput)
        ModelTraceStore.append(
            context,
            sessionId,
            ModelTraceEntry(
                source = ModelTraceSource.CLOUD_PLANNER_REPLAY,
                title = "Gemma 32B 挂号任务规划",
                modelName = MODEL_NAME,
                inputText = prompt(memory, userRequest),
                outputText = output,
                elapsedMs = 842L,
                backend = "云侧规划结果 · 脱敏上下文",
                status = parsed.fold(
                    onSuccess = { "规划输出已校验，并生成端侧 Gemma 4B 执行目标" },
                    onFailure = { "规划输出校验失败：${it.message}" },
                ),
            ),
        )
        return parsed.getOrElse { error ->
            throw IllegalStateException("云侧规划输出无效", error)
        }
    }

    private fun parseOutput(): TriggerReplayPlan {
        val root = JSONObject(output)
        require(!root.has("actions")) { "云侧只允许返回语义计划，不能返回 GUI 动作" }
        require(root.getJSONArray("memoryUsed").length() >= 3) { "云侧规划未引用足够的记忆依据" }
        val appointment = root.getJSONObject("appointment")
        val executionPlan = root.getJSONObject("executionPlan")
        require(executionPlan.getString("executor").contains("Gemma 4B")) {
            "云侧规划未把屏幕执行交给端侧 Gemma 4B"
        }
        val safety = root.getJSONObject("safety")
        require(!safety.getBoolean("cloud_receives_raw_screenshot")) { "规划不得上传原始截图" }
        require(!safety.getBoolean("cloud_receives_full_phone")) { "规划不得包含完整手机号" }
        require(!safety.getBoolean("cloud_receives_id_number")) { "规划不得包含身份证号" }
        val stopBeforeJson = safety.getJSONArray("stop_before")
        val stopBefore = buildSet {
            for (index in 0 until stopBeforeJson.length()) {
                add(stopBeforeJson.getString(index))
            }
        }
        require(stopBefore.containsAll(setOf("确认挂号", "支付", "验证码"))) {
            "规划缺少必要的人工确认边界"
        }

        fun requiredText(key: String): String = appointment.getString(key).trim().also {
            require(it.isNotBlank()) { "appointment.$key 不能为空" }
        }

        return TriggerReplayPlan(
            hospital = requiredText("hospital"),
            department = requiredText("department"),
            doctor = requiredText("doctor"),
            date = requiredText("date"),
            time = requiredText("time"),
            reason = requiredText("reason"),
        )
    }
}
