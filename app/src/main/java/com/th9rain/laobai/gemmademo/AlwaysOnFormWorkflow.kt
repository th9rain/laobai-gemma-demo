package com.th9rain.laobai.gemmademo

object AlwaysOnFormWorkflow {
    fun initialState(): FormDemoState {
        return FormDemoState(
            fields = listOf(
                FormField("姓名"),
                FormField("年龄段"),
                FormField("手机号"),
                FormField("居住区域"),
                FormField("紧急联系人"),
                FormField("报名课程"),
            )
        )
    }

    fun openForm(state: FormDemoState): FormDemoState {
        return state.copy(
            screenOpened = true,
            sentinelDetected = true,
            promptVisible = true,
            stoppedBeforeSubmit = false,
            fields = initialState().fields,
            steps = listOf(
                DemoStep("打开真实表单页面", "当前页面：北京市朝阳区社区智慧课堂报名表"),
                DemoStep("Always-on Sentinel", "端侧无障碍服务识别固定报名表模板，未上传原始屏幕"),
                DemoStep("轻量提醒", "老白提示：我看到这是报名表，要不要帮您填写常用信息？"),
            )
        )
    }

    fun confirmFill(state: FormDemoState): FormDemoState {
        val stopTarget = "提交报名"
        val steps = state.steps + listOf(
            DemoStep("用户确认", "老人同意使用本地已授权资料填写"),
            DemoStep("本地记忆", "读取李桂兰的虚拟资料，手机号保持脱敏"),
            DemoStep("GUI 自动化", "无障碍服务向真实输入框写入姓名、年龄段、手机号、住址、联系人、课程", RiskLevel.Medium),
            DemoStep("安全守卫", SafetyGuard.stopMessage(stopTarget), RiskLevel.High),
        )
        return state.copy(
            promptVisible = false,
            filling = false,
            stoppedBeforeSubmit = SafetyGuard.shouldStopBefore(stopTarget),
            fields = LocalMemoryStore.authorizedFormFields(),
            steps = steps,
        )
    }

    fun reset(): FormDemoState = initialState()
}
