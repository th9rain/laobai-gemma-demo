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
    const val MODEL_NAME = "Gemma 32B Dense（云侧历史回放）"

    val prompt: String = """
        IMPORTANT: Return one compact valid JSON object only. No markdown. No explanation.
        You are the cloud planner for LaoBai, a senior-assistance mobile GUI agent demo.
        Task: user has stomach discomfort for 2 days, mild nausea, no chest pain.
        Tomorrow is busy because the user attends a mobile training competition. Choose the day after tomorrow at 10:00.
        Use frequent hospital/department/doctor from local memory. Never include raw ID number, full phone, OTP, payment, or raw medical records.
        Return this JSON shape exactly. Do not return GUI actions; local execution is handled on device:
        {"summary":"中文一句话","appointment":{"hospital":"北京协和医院","department":"消化内科门诊","doctor":"李明 主任医师","date":"后天","time":"上午 10:00","reason":"中文一句话"}}
        Local memory summary: {"frequentHospital":"北京协和医院","frequentDepartment":"消化内科门诊","frequentDoctor":"李明 主任医师","busyTomorrow":"mobile training competition","freeSlot":"day after tomorrow 10:00"}
        Privacy flags: {"rawScreenUploaded":false,"fullPhoneUploaded":false,"idNumberUploaded":false,"otpUploaded":false,"rawMedicalRecordUploaded":false}
    """.trimIndent()

    val output: String = """
        {
          "summary": "已避开明天手机培训比赛，建议后天上午 10:00 挂北京协和医院消化内科门诊常去医生。",
          "appointment": {
            "hospital": "北京协和医院",
            "department": "消化内科门诊",
            "doctor": "李明 主任医师",
            "date": "后天",
            "time": "上午 10:00",
            "reason": "症状是胃部不适两天并伴轻微恶心，优先消化内科；明天全天有手机培训比赛安排，后天上午 10:00 空闲；本地记忆显示老人常去北京协和医院并常挂李明主任医师。"
          },
          "safety": {
            "cloud_receives_raw_screenshot": false,
            "cloud_receives_full_phone": false,
            "cloud_receives_id_number": false,
            "stop_before": ["确认挂号", "支付", "验证码"]
          }
        }
    """.trimIndent()

    fun record(context: Context, sessionId: String): TriggerReplayPlan {
        val parsed = runCatching(::parseOutput)
        ModelTraceStore.append(
            context,
            sessionId,
            ModelTraceEntry(
                source = ModelTraceSource.CLOUD_PLANNER_REPLAY,
                title = "挂号任务规划",
                modelName = MODEL_NAME,
                inputText = prompt,
                outputText = output,
                elapsedMs = 842L,
                backend = "脱敏历史调用回放 · 当前未联网",
                status = parsed.fold(
                    onSuccess = { "回放输出已校验，并生成端侧执行目标" },
                    onFailure = { "回放输出校验失败：${it.message}" },
                ),
            ),
        )
        return parsed.getOrElse { error ->
            throw IllegalStateException("云侧规划回放输出无效", error)
        }
    }

    private fun parseOutput(): TriggerReplayPlan {
        val root = JSONObject(output)
        require(!root.has("actions")) { "云侧只允许返回语义计划，不能返回 GUI 动作" }
        val appointment = root.getJSONObject("appointment")
        val safety = root.getJSONObject("safety")
        require(!safety.getBoolean("cloud_receives_raw_screenshot")) { "回放不得上传原始截图" }
        require(!safety.getBoolean("cloud_receives_full_phone")) { "回放不得包含完整手机号" }
        require(!safety.getBoolean("cloud_receives_id_number")) { "回放不得包含身份证号" }
        val stopBeforeJson = safety.getJSONArray("stop_before")
        val stopBefore = buildSet {
            for (index in 0 until stopBeforeJson.length()) {
                add(stopBeforeJson.getString(index))
            }
        }
        require(stopBefore.containsAll(setOf("确认挂号", "支付", "验证码"))) {
            "回放缺少必要的人工确认边界"
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
