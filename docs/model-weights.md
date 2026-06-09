# 模型权重说明

当前 APK 不内置 Gemma 主模型权重。

原因：

- Gemma LiteRT / MediaPipe 端侧权重通常较大，不适合直接打进演示 APK。
- 公开仓库不适合提交模型下载 token 或私有访问凭证。
- 当前目标优先级是让两个 demo 在手机上稳定跑通并可录屏。

当前版本已经包含：

- Android AccessibilityService 手机 GUI 操作骨架。
- 本地 workflow。
- 本地 embedding demo asset：`app/src/main/assets/embedding_kb.json`。
- 可选 Ark 云端 planner 调用。

后续接真实 Gemma 时建议分三步：

1. 把 Gemma LiteRT / MediaPipe 权重作为 GitHub Release 附件或手动导入文件提供，不提交到 Git。
2. 在 App 内增加模型导入和状态检测，显示真实加载状态。
3. 将 `HealthBookingWorkflow` 和 `AlwaysOnFormWorkflow` 中的固定策略替换为 Gemma planner / UI understanding 输出，同时保留 `SafetyGuard`。
