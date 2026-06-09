# 技术架构

## 目标

老白 Gemma Agent 的目标是做一个能在 Android 手机上跑通的比赛 Demo。当前版本重点证明三件事：

- APK 有真实手机 GUI 操作能力，而不是纯静态页面。
- 两条 case 能稳定录屏：Always-on 自动填表、Trigger 看病挂号。
- 高风险动作必须停住，不自动提交、付款或处理验证码。

## 总体结构

```text
MainActivity 控制台
  -> 启用 Android AccessibilityService
  -> 配置 Ark 云端 planner API Key
  -> 打开 Demo 目标页面

LaoBaiAccessibilityService
  -> 观察当前窗口节点
  -> 识别固定表单 / 挂号页面
  -> 执行点击、输入、页面状态更新
  -> 调用本地 workflow 或云端 planner
  -> 经过 SafetyGuard 后停止在高风险按钮前

本地能力
  -> LocalMemoryStore 虚拟老人资料
  -> LocalEmbeddingEngine 内置知识库检索
  -> AlwaysOnFormWorkflow 填表策略
  -> HealthBookingWorkflow 挂号策略
  -> SafetyGuard 风险动作拦截
```

## Android 操作层

核心服务是 `LaoBaiAccessibilityService`。

它具备以下能力：

- 读取当前页面文本和控件 ID。
- 识别 `北京市朝阳区社区智慧课堂报名表`。
- 识别 `北京医院挂号助手`。
- 对 `EditText` 执行 `ACTION_SET_TEXT`。
- 对低风险按钮执行 `ACTION_CLICK`。
- 对高风险按钮只提示，不点击。

当前版本只操作本 App 内的两个目标页面，避免误操作真实支付、真实医院 App 或系统设置。后续要扩展到外部 App 时，可以在同一服务中增加目标包名、页面识别规则和控件定位策略。

## Demo 1：Always-on 自动填表

流程：

1. 用户打开固定表单页面。
2. AccessibilityService 观察到表单标题。
3. 服务读取 `LocalMemoryStore` 中的虚拟老人资料。
4. 服务逐个填写姓名、年龄段、手机号、居住区域、紧急联系人、报名课程。
5. `SafetyGuard` 识别 `提交报名` 是高风险动作，停止执行。

这一条模拟的是 Always-on Sentinel：用户不用主动下命令，只要页面出现，端侧 Agent 就能识别并提示/执行低风险辅助。

## Demo 2：Trigger 看病挂号

流程：

1. 用户打开 Trigger 挂号页面。
2. AccessibilityService 自动点击 `开始问询`。
3. 页面展示健康问询问题。
4. 服务点击演示回答。
5. `HealthBookingWorkflow` 使用本地 `LocalEmbeddingEngine` 推荐科室。
6. 如果开启 Ark planner，服务调用 `ArkCloudPlanner` 获取云端规划说明。
7. 服务填写医院、科室、时间、准备材料。
8. `SafetyGuard` 识别 `确认挂号 / 支付 / 验证码` 是高风险动作，停止执行。

这一条模拟的是 Trigger Assistant：由用户主动请求触发，端侧完成隐私处理和安全执行，云端 planner 只参与脱敏规划。

## 云端 Planner

云端 planner 是可选能力。首页开启后，App 会使用用户输入的 API Key 调用：

```text
https://ark.cn-beijing.volces.com/api/v3/responses
```

请求内容只包含脱敏摘要，例如：

```text
用户目标：看病挂号
症状摘要：胃不舒服，伴随轻微恶心
持续时间：两天
严重程度：中等
本地建议：北京协和医院 / 消化内科
```

云端不接收原始截图、身份证、完整手机号、验证码或完整病历。云端返回只用于规划说明，端侧仍按固定安全 workflow 执行。

## Gemma 权重

当前版本不把 Gemma 主模型权重打进 APK：

- 端侧权重通常较大，直接打包会让 APK 过大。
- 公开仓库不适合提交私有模型下载凭证。
- 比赛录屏优先保证稳定可跑。

当前 APK 保留端侧 Gemma 模型槽：本地 workflow 和 embedding 知识库负责可运行演示，后续可以把 planner / embedding / UI understanding 替换成 Gemma LiteRT 或 MediaPipe 端侧模型。

## 安全策略

`SafetyGuard` 拦截以下动作：

- 提交
- 确认挂号
- 支付
- 验证码
- 授权
- 删除

当前版本的原则是：低风险动作可以自动执行，高风险动作必须停住给老人或家人确认。
