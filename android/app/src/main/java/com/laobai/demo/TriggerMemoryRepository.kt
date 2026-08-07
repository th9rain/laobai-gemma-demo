package com.laobai.demo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TriggerMemoryContext(
    val frequentHospital: String,
    val frequentDepartment: String,
    val frequentDoctor: String,
    val busyTomorrow: String,
    val freeSlot: String,
    val localPatient: String,
) {
    fun cloudSummary(): JSONObject = JSONObject()
        .put("frequentHospital", frequentHospital)
        .put("frequentDepartment", frequentDepartment)
        .put("frequentDoctor", frequentDoctor)
        .put("busyTomorrow", busyTomorrow)
        .put("freeSlot", freeSlot)
}

/** Deterministic local-memory retrieval used by the Trigger demonstration. */
object TriggerMemoryRepository {
    fun retrieveAndRecord(
        context: Context,
        sessionId: String,
        userRequest: String,
    ): TriggerMemoryContext {
        val memory = TriggerMemoryContext(
            frequentHospital = "北京协和医院",
            frequentDepartment = "消化内科门诊",
            frequentDoctor = "李明 主任医师",
            busyTomorrow = "明天参加手机培训比赛，全天不便就诊",
            freeSlot = "后天上午 10:00 有空",
            localPatient = "李桂兰（已实名，本地保存）",
        )
        val input = JSONObject()
            .put("userRequest", userRequest)
            .put("intent", "预约挂号")
            .put(
                "retrieve",
                JSONArray()
                    .put("常用医院")
                    .put("常用科室")
                    .put("常挂医生")
                    .put("近期日程")
                    .put("可用就诊时间"),
            )
            .put("privacy", "只向云侧规划提供脱敏偏好和日程摘要；实名就诊人只留在端侧")
        val output = JSONObject()
            .put("query", "挂号偏好与近期可用时间")
            .put(
                "matches",
                JSONArray()
                    .put(memoryMatch("care-preference-01", "历史对话总结 · 长期就医偏好", "常去北京协和医院"))
                    .put(memoryMatch("care-preference-02", "历史对话总结 · 长期就医偏好", "胃部不适时常挂消化内科门诊"))
                    .put(memoryMatch("care-preference-03", "历史对话总结 · 长期就医偏好", "常挂李明主任医师"))
                    .put(memoryMatch("calendar-07", "历史对话总结 · 近期日程", memory.busyTomorrow))
                    .put(memoryMatch("calendar-08", "历史对话总结 · 近期日程", memory.freeSlot)),
            )
            .put("cloudContext", memory.cloudSummary())
            .put(
                "localOnly",
                JSONObject()
                    .put("patient", memory.localPatient)
                    .put("sentToCloud", false),
            )
        ModelTraceStore.append(
            context,
            sessionId,
            ModelTraceEntry(
                source = ModelTraceSource.LOCAL_MEMORY,
                title = "调取挂号相关记忆",
                modelName = "老白本地记忆库",
                inputText = input.toString(2),
                outputText = output.toString(2),
                elapsedMs = 36L,
                backend = "APK 本地存储 · 离线检索",
                status = "命中 5 条相关记忆；已生成脱敏规划上下文",
            ),
        )
        return memory
    }

    private fun memoryMatch(id: String, source: String, fact: String): JSONObject =
        JSONObject()
            .put("memoryId", id)
            .put("source", source)
            .put("fact", fact)
}
