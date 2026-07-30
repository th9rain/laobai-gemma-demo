package com.laobai.demo

import android.content.Context

/** A redacted fixture captured from an earlier cloud-planner run. */
object TriggerPlannerReplay {
    const val MODEL_NAME = "Gemma 32B Dense（历史回放）"

    val prompt: String = """
        IMPORTANT: Return one compact valid JSON object only. No markdown. No explanation.
        You are the cloud planner for LaoBai, a senior-assistance mobile GUI agent demo.
        Task: user has stomach discomfort for 2 days, mild nausea, no chest pain.
        Tomorrow is busy because the user attends a mobile training competition. Choose the day after tomorrow at 10:00.
        Use frequent hospital/department/doctor from local memory. Never include raw ID number, full phone, OTP, payment, or raw medical records.
        Return this JSON shape exactly. Do not return GUI actions; local execution is handled on device:
        {"summary":"中文一句话","appointment":{"hospital":"北京协和医院","department":"消化内科","doctor":"李明 主任医师","date":"后天","time":"上午 10:00","reason":"中文一句话"}}
        Local memory summary: {"frequentHospital":"北京协和医院","frequentDepartment":"消化内科","frequentDoctor":"李明 主任医师","busyTomorrow":"mobile training competition","freeSlot":"day after tomorrow 10:00"}
        Privacy flags: {"rawScreenUploaded":false,"fullPhoneUploaded":false,"idNumberUploaded":false,"otpUploaded":false,"rawMedicalRecordUploaded":false}
    """.trimIndent()

    val output: String = """
        {
          "summary": "已避开明天手机培训比赛，建议后天上午 10:00 挂北京协和医院消化内科常去医生。",
          "appointment": {
            "hospital": "北京协和医院",
            "department": "消化内科",
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

    fun record(context: Context, sessionId: String) {
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
                backend = "历史真实调用回放 · 当前未联网",
                status = "回放完成",
            ),
        )
    }
}
