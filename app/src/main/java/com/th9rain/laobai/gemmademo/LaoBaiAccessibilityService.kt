package com.th9rain.laobai.gemmademo

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LaoBaiAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastFormRunAt = 0L
    private var lastHealthRunAt = 0L
    private var healthPlanning = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        when {
            root.hasText("北京市朝阳区社区智慧课堂报名表") -> runFormAutomation(root)
            root.hasText("北京医院挂号助手") -> runHealthAutomation(root)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun runFormAutomation(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastFormRunAt < 1200) return
        lastFormRunAt = now

        sendStatus(AgentBroadcasts.PAGE_FORM, "Always-on Sentinel 已识别报名表，正在读取本地记忆")
        val profile = LocalMemoryStore.profile
        setTextById(root, "form_name_input", profile.name)
        setTextById(root, "form_age_input", profile.ageBand)
        setTextById(root, "form_phone_input", profile.phoneMasked)
        setTextById(root, "form_area_input", profile.area)
        setTextById(root, "form_contact_input", profile.emergencyContact)
        setTextById(root, "form_course_input", profile.preferredCourse)
        sendStatus(AgentBroadcasts.PAGE_FORM, SafetyGuard.stopMessage("提交报名"), "stopped")
    }

    private fun runHealthAutomation(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (healthPlanning || now - lastHealthRunAt < 1200) return
        lastHealthRunAt = now

        if (root.hasText("开始问询")) {
            sendStatus(AgentBroadcasts.PAGE_HEALTH, "Trigger 已收到：我胃不舒服，帮我挂号")
            clickById(root, "health_start_button")
            scheduleHealthRerun()
            return
        }

        if (root.hasText("回答：两天了")) {
            sendStatus(AgentBroadcasts.PAGE_HEALTH, "端侧完成健康问询，准备规划挂号")
            clickById(root, "health_answer_button")
            scheduleHealthRerun()
            return
        }

        if (root.findByIdSuffix("health_hospital_input").firstOrNull()?.text.isNullOrBlank()) {
            healthPlanning = true
            scope.launch {
                val config = cloudPlannerConfig()
                val planned = HealthBookingWorkflow.plan(
                    context = this@LaoBaiAccessibilityService,
                    state = HealthBookingWorkflow.trigger("我胃不舒服，帮我挂号"),
                    cloudConfig = config,
                )
                val latestRoot = rootInActiveWindow
                if (latestRoot != null) {
                    setTextById(latestRoot, "health_hospital_input", planned.hospital)
                    setTextById(latestRoot, "health_department_input", planned.department)
                    setTextById(latestRoot, "health_date_input", "明天上午")
                    setTextById(latestRoot, "health_materials_input", "身份证、医保卡、既往病历")
                    val planner = if (planned.cloudPlannerUsed) "已调用 Ark 云端 planner" else "本地 Gemma workflow + embedding 知识库"
                    sendStatus(AgentBroadcasts.PAGE_HEALTH, "$planner。${SafetyGuard.stopMessage("确认挂号 / 支付 / 验证码")}", "stopped")
                }
                healthPlanning = false
            }
        }
    }

    private fun scheduleHealthRerun() {
        scope.launch {
            delay(1400)
            rootInActiveWindow?.let { runHealthAutomation(it) }
        }
    }

    private fun cloudPlannerConfig(): CloudPlannerConfig {
        val prefs = getSharedPreferences(AgentPrefs.FILE, MODE_PRIVATE)
        return CloudPlannerConfig(
            enabled = prefs.getBoolean(AgentPrefs.KEY_CLOUD_ENABLED, false),
            apiKey = prefs.getString(AgentPrefs.KEY_ARK_API_KEY, "").orEmpty(),
        )
    }

    private fun sendStatus(page: String, status: String, stage: String = "running") {
        sendBroadcast(
            Intent(AgentBroadcasts.ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(AgentBroadcasts.EXTRA_PAGE, page)
                .putExtra(AgentBroadcasts.EXTRA_STATUS, status)
                .putExtra(AgentBroadcasts.EXTRA_STAGE, stage)
        )
    }

    private fun setTextById(root: AccessibilityNodeInfo, idSuffix: String, value: String): Boolean {
        val node = root.findByIdSuffix(idSuffix).firstOrNull() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clickById(root: AccessibilityNodeInfo, idSuffix: String): Boolean {
        val node = root.findByIdSuffix(idSuffix).firstOrNull() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun AccessibilityNodeInfo.findByIdSuffix(idSuffix: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val viewId = node.viewIdResourceName.orEmpty()
            if (viewId.endsWith(":id/$idSuffix")) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }
        visit(this)
        return result
    }

    private fun AccessibilityNodeInfo.hasText(text: String): Boolean {
        var found = false
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || found) return
            val nodeText = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (nodeText.contains(text) || desc.contains(text)) {
                found = true
                return
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }
        visit(this)
        return found
    }
}
