# 老白 Agent Web Demo

这是一个面向路演录屏的端云结合 Agent Web Demo。在电脑上运行本地 server 后，会自动打开两个竖屏手机比例的 HTML 页面，并由 Agent 自动执行任务。

核心 case：

- `Always-on 自动填表`：端侧识别社区报名表，必须调用本地 Gemma 4B/E4B LiteRT 权重生成 GUI action，自动填写常用资料，停在提交前。
- `Trigger 看病挂号`：用户触发“我胃不舒服，帮我挂号”，云侧 Planner 做复杂规划，端侧 Gemma 执行 GUI 动作，停在确认/支付/验证码前。

## 快速开始

需要本机安装 Node.js 18+。

只跑 `Always-on 自动填表` 的真实本地 Gemma 权重版本：

```powershell
.\tools\download-gemma-model.ps1
.\tools\setup-litert-lm.ps1
.\run-always-on.ps1
```

这个入口会忽略 `config.local.json`，不会读取云端 planner endpoint 或 key。它只使用本地 `models/gemma-4-E4B-it.litertlm` 和 `.venv/Scripts/python.exe`。如果权重、Python 环境或模型输出不可用，Always-on 会直接失败，不会静态兜底填表。

如果想看更接近真实自动化的版本，先安装 Node 依赖：

```powershell
npm install
npx playwright install chromium
.\tools\run-external-always-on.ps1
```

这个入口会启动本地 server，然后用外部 Playwright 脚本读取页面 observation，请求本地 Gemma 权重生成 action，再由脚本从页面外部执行 `fill/select/click`。它不是页面 JS 自己填自己。
脚本默认会打开一个可见浏览器窗口，方便录屏；如果只想在后台验证，可以先设置 `$env:LAOBAI_HEADLESS="1"`。

也可以不打开网页，直接在命令行跑本地 workflow：

```powershell
.\tools\run-always-on-workflow.ps1
```

它会输出本地 Gemma 解析后的 action JSON，可用于检查真实权重是否能返回可执行动作。

运行 Trigger 看病挂号：

```powershell
.\run-trigger.ps1
```

根目录只保留两个入口：`run-always-on.ps1` 和 `run-trigger.ps1`。

Always-on 不能直接双击 HTML 运行，必须通过本地 server 调用真实 Gemma 权重。Trigger 页面在没有云端配置时仍保留本地安全 fallback，保证挂号流程可展示。

## 模型调用与 Key

页面上不会展示 API Key，也不会展示具体底层供应商或转发服务。

Always-on 不需要配置云端 Key。

公开仓库只提交 `config.example.json`。本地演示时复制一份：

```powershell
Copy-Item config.example.json config.local.json
```

如果要运行 Trigger 看病挂号的云侧 planner，再在 `config.local.json` 中填写私有 planner endpoint、model 和 key。这个文件被 `.gitignore` 忽略，不会提交到 GitHub。
如果要启用本地 Gemma Computer-Use，先下载权重并安装 LiteRT-LM：

```powershell
.\tools\download-gemma-model.ps1
.\tools\setup-litert-lm.ps1
```

然后在 `config.local.json` 中保留：

```json
{
  "localGemmaEnabled": true,
  "localGemmaModelPath": "models/gemma-4-E4B-it.litertlm",
  "localGemmaPython": ".venv/Scripts/python.exe"
}
```

Always-on 填表不请求云端 planner。它会走 `tools/always-on-workflow.py`，由本地 Gemma LiteRT 权重直接生成 GUI action；模型必须返回合法 JSON action plan，否则本轮失败。
当前表单被拆成两页：第一页填写姓名、年龄段、手机号，点击下一页后重新观察第二页；第二页填写居住区域、紧急联系人、课程和学习目标，并在提交前停住。因此正常录屏会看到两次本地 Gemma 调用。
Trigger 挂号才会先请求私有 planner adapter，再把规划交给本地 Gemma / Computer-Use adapter 生成最终 GUI action；如果 adapter 不可用，才退回浏览器执行器。

页面只显示：

- `Gemma 4B Computer-Use`
- Always-on 页面：`真实本地 Gemma 权重`
- Trigger 页面：`Gemma 4 30B Cloud Planner`

实际请求由 `tools/demo-server.mjs` 的 `/api/plan` 代理完成。浏览器前端只能看到 `/api/plan`，看不到真实 Key、真实 endpoint、真实 model 或底层服务。Always-on 分支只在本地执行；Trigger 分支才会调用云侧 Planner。

如果没有配置云端 Key，Trigger 挂号会自动使用本地安全 fallback action，保证挂号流程可运行；Always-on 不使用静态 fallback。

## Demo 行为

### Always-on 自动填表

执行链路：

1. 点击 `启动 Agent` 或用 `?autostart=1` 自动启动。
2. 页面生成脱敏观察摘要。
3. 本地 server 调用 `tools/always-on-workflow.py`。
4. Python 调用本地 Gemma LiteRT 权重，要求模型返回合法 JSON action plan。
5. 右侧显示本次真实送给模型的 Prompt 和模型原始输出。
6. 执行器逐步操作模拟手机页面：
   - 第一次调用：输入姓名、年龄段、手机号，点击下一页
   - 第二次调用：输入居住区域、紧急联系人
   - 选择报名课程
   - 停在 `提交报名`
7. 右侧实时显示每一步 action 和原因。

### Trigger 看病挂号

执行链路：

1. 点击 `启动 Agent` 或用 `?autostart=1` 自动启动。
2. Agent 点击 `开始问询`。
3. Agent 点击演示回答。
4. planner 输出医院、科室、时间、准备材料。
5. 执行器填写模拟挂号页面。
6. 停在 `确认挂号 / 支付 / 验证码` 前。

## 隐私边界

- 原始屏幕不上传。
- 身份证、完整手机号、验证码、病历原文不上云。
- planner adapter 只接收结构化脱敏摘要。
- 高风险动作必须 `guard`，不能自动执行。

## 文件结构

```text
web/
  index.html
  always-on-form.html
  trigger-health.html
  styles.css
  agent.js
tools/
  demo-server.mjs
  always-on-workflow.py
  verify-demo.mjs
config.example.json
run-always-on.ps1
run-trigger.ps1
```

## 验证

```powershell
node .\tools\verify-demo.mjs
```
