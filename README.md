# 老白 Agent Web Demo

这个仓库用于验证两个比赛 demo：

- `Always-on 填表`：必须走真实截图 computer-use 链路。本地 Gemma LiteRT 接收手机页面截图和任务，输出坐标动作 JSON，外部 Playwright runner 按坐标点击和输入。
- `Trigger 看病挂号`：保留云端 planner 入口。没有云端配置时使用本地安全 fallback，保证挂号流程可展示。

## 当前边界

Always-on 不再使用 DOM id、控件列表、结构化 observation 来假装 computer-use。它的输入和输出必须是：

```text
输入：屏幕截图 PNG + 任务文本
输出：{"actions":[{"type":"type_at","x":123,"y":456,"text":"李桂兰"}]}
执行：Playwright page.mouse.click(x, y) + keyboard.type(text)
```

如果本地 LiteRT 视觉调用失败或超时，Always-on 会直接失败，不会回退到静态填表。

Gemma E4B 在这里按多模态端侧模型使用：Python runner 会用 LiteRT-LM 的 `vision_backend` 加载模型，并把 PNG 截图作为 image content 传给模型。模型返回的 JSON 必须包含 `x/y` 坐标，执行器只按这些坐标操作页面。

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

## 模型配置

Always-on 只使用本地 Gemma LiteRT：

```powershell
$env:LAOBAI_LOCAL_GEMMA_ENABLED = "1"
$env:LAOBAI_LOCAL_GEMMA_MODEL_PATH = "models/gemma-4-E4B-it.litertlm"
$env:LAOBAI_LOCAL_GEMMA_PYTHON = ".venv/Scripts/python.exe"
```

Trigger 的云端 planner 配置放在 `config.local.json`，该文件不会提交到 GitHub。公开仓库只保留 `config.example.json`。

## 文件结构

```text
web/
  always-on-form.html      # 被截图的手机表单页面
  trigger-health.html      # Trigger 挂号页面
  agent.js                 # 展示 trace/model IO；Trigger DOM 执行器
tools/
  always-on-workflow.py    # Gemma LiteRT 多模态截图调用，校验坐标 JSON
  external-always-on-runner.mjs # Playwright 截图与坐标执行
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

`npm run verify` 会检查 Always-on 是否拒绝旧的结构化 observation 请求，并确认 Trigger 仍可运行。`run-always-on.ps1` 会真正调用本地 Gemma E4B 多模态权重完成截图坐标链路。
