# 技术架构

## 总体结构

```text
Compose UI
  -> Local Workflow
  -> Local Memory / Embedding KB
  -> Safety Guard
  -> Demo Screen Output
```

当前版本优先服务手机录屏展示，不做完整生产级 Agent。

## 模块

- `LocalMemoryStore`：保存虚拟老人资料。
- `AlwaysOnFormWorkflow`：固定表单识别、轻量提醒、自动填充、提交前停止。
- `HealthBookingWorkflow`：Trigger 问询、科室推荐、挂号页模拟、确认前停止。
- `LocalEmbeddingEngine`：读取内置 embedding demo 知识库，完成症状到科室/医院的检索。
- `ArkCloudPlanner`：可选演示云端 planner，调用 Ark Responses API。
- `SafetyGuard`：统一拦截提交、确认挂号、支付、验证码、授权、删除。

## 云端 Planner

云端不是必需路径。默认关闭。

开启后，App 使用用户输入的 API Key 调用：

```text
https://ark.cn-beijing.volces.com/api/v3/responses
```

云端只参与“生成规划说明”，端侧仍然执行固定 workflow，并且仍然在确认挂号、支付、验证码前停止。

## Gemma 权重

Gemma 主模型不内置进 APK。原因是端侧权重通常是 GB 级，不适合直接提交到仓库或打进 APK。

比赛演示中：

- 用本地 workflow 保证稳定跑通；
- 用 `Gemma 状态` 展示端侧模型位；
- 用 embedding demo asset 展示本地知识库；
- 后续可把 Gemma LiteRT / MediaPipe 权重作为 GitHub Release 附件提供。
