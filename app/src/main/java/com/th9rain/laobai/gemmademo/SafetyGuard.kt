package com.th9rain.laobai.gemmademo

object SafetyGuard {
    private val blockedTargets = listOf("提交", "确认挂号", "支付", "验证码", "授权", "删除")

    fun shouldStopBefore(target: String): Boolean {
        return blockedTargets.any { target.contains(it, ignoreCase = true) }
    }

    fun stopMessage(target: String): String {
        return "老白已经停在「$target」前。请老人或家人检查信息，确认无误后再手动操作。"
    }
}
