# 老白 Agent Web Demo

这个仓库用于验证两个比赛 demo：

- `Always-on 填表`：必须走真实截图 computer-use 链路。本地 Gemma LiteRT 接收手机页面截图和任务，输出坐标动作 JSON，外部 Playwright runner 按坐标点击和输入。
- `Trigger 看病挂号`：优化版 Trigger 页面。云端 30B Planner 使用历史调用回放 fixture 展示脱敏输入、Prompt、原始输出和解析结果；本地执行阶段使用 Gemma E4B 截图坐标链路打开模拟京医通、选择医院/科室/医生/时间，并在确认挂号、支付、验证码前 guard。

## 当前边界

Always-on 不再使用 DOM id、控件列表、结构化 observation 来假装 computer-use。它的输入和输出必须是：

```text
输入：屏幕截图 PNG + 任务文本
输出：{"actions":[{"type":"type_at","x":123,"y":456,"text":"李桂兰"}]}
执行：Playwright page.mouse.click(x, y) + keyboard.type(text)
```

如果本地 LiteRT 视觉调用失败或超时，Always-on 会直接失败，不会回退到静态填表。

Gemma E4B 在这里按多模态端侧模型使用：Python runner 会用 LiteRT-LM 的 `vision_backend` 加载模型，并把 PNG 截图作为 image content 传给模型。模型返回的 JSON 必须包含 `x/y` 坐标，执行器只按这些坐标操作页面。

Trigger 的云端规划不实时请求外部 API。为了保证现场稳定，它展示的是 cached cloud planner 记录；本地执行阶段仍然走截图坐标动作链路，并在右侧 IO 面板展示每一轮截图输入、Prompt、原始输出和解析后的动作 JSON。

## 快速启动

先准备 Node 依赖、Gemma 权重和 LiteRT-LM：

```powershell
npm install
npx playwright install chromium
.\tools\download-gemma-model.ps1
.\tools\setup-litert-lm.ps1
```

运行真实 Always-on 截图坐标链路：

```powershell
.\run-always-on.ps1
```

这个脚本会启动本地 server、打开一个可见浏览器、截取左侧手机屏幕、调用本地 `models/gemma-4-E4B-it.litertlm`，然后按模型返回的坐标执行填写。右侧会显示截图、Prompt、模型原始输出和解析后的坐标动作。

正常跑通时会发生两次本地 Gemma 调用：

1. 第 1 页截图：模型输出姓名、年龄段、手机号三个 `type_at` 坐标，以及“下一页”的 `click` 坐标。
2. 第 2 页截图：模型输出居住区域、联系人、课程、学习目标四个 `type_at` 坐标，并对“提交报名”输出 `guard`，不会点击提交。

每次运行的调试文件会写到 `%TEMP%\laobai-computer-use-*`，里面包含截图、模型输入/输出、坐标计划和执行日志。

运行 Trigger 挂号：

```powershell
.\run-trigger.ps1
```

Trigger 会启动本地 server、打开优化版手机界面，并执行：

1. 点击右下角“老白”浮窗进入对话。
2. 展示云端 Planner 历史回放：脱敏症状、日程、常去医院、Planner Prompt 和 JSON 输出。
3. 本地 Gemma 看手机截图，输出坐标 JSON。
4. Runner 按坐标打开京医通，选择北京协和医院、消化内科、李明主任医师、后天上午 10:00。
5. 到确认挂号 / 支付 / 验证码前停止。

## 模型配置

Always-on 只使用本地 Gemma LiteRT：

```powershell
$env:LAOBAI_LOCAL_GEMMA_ENABLED = "1"
$env:LAOBAI_LOCAL_GEMMA_MODEL_PATH = "models/gemma-4-E4B-it.litertlm"
$env:LAOBAI_LOCAL_GEMMA_PYTHON = ".venv/Scripts/python.exe"
```

模型权重、虚拟环境和本地配置不会提交到 GitHub。公开仓库只保留源码、页面、runner 和配置模板。

## 文件结构

```text
web/
  always-on-form.html      # 被截图的手机表单页面
  trigger-health.html      # 优化版 Trigger 手机挂号页面
  trigger-health.js        # Trigger 对话、Planner 回放、执行状态渲染
  agent.js                 # 展示 trace/model IO
  vendor/framework7/       # Trigger 手机 UI 使用的本地静态样式
tools/
  always-on-workflow.py    # Gemma LiteRT 多模态截图调用，校验坐标 JSON
  trigger-health-workflow.py # Trigger Gemma 截图坐标调用
  external-always-on-runner.mjs # Playwright 截图与坐标执行
  external-trigger-runner.mjs   # Trigger Playwright 截图与坐标执行
  demo-server.mjs          # 本地 API 代理；Always-on 禁止 fallback
run-always-on.ps1          # 真实 Always-on 截图坐标链路入口
run-trigger.ps1            # Trigger 挂号入口
```

## 验证

```powershell
npm run verify
$env:LAOBAI_HEADLESS = "1"
.\run-always-on.ps1
```

`npm run verify` 会检查 Always-on 是否拒绝旧的结构化 observation 请求，并确认 Trigger Planner fixture、脱敏 runtime 和 guard action 能正常返回。
