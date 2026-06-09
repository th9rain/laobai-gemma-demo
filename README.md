# 老白 Agent Web Demo

这是一个面向路演录屏的端云结合 Agent Web Demo。在电脑上运行本地 server 后，会自动打开两个竖屏手机比例的 HTML 页面，并由 Agent 自动执行任务。

核心 case：

- `Always-on 自动填表`：识别社区报名表，自动填写常用资料，停在提交前。
- `Trigger 看病挂号`：用户触发“我胃不舒服，帮我挂号”，完成问询、科室/医院规划、模拟挂号，停在确认/支付/验证码前。

## 快速开始

需要本机安装 Node.js 18+。

```powershell
.\run-demo-server.ps1
```

打开首页后选择任一 demo。

也可以直接启动指定 demo：

```powershell
.\run-always-on-demo.ps1
.\run-trigger-demo.ps1
```

两个 HTML 也可以直接双击打开做离线演示；这时会使用页面内置的安全 action 序列。需要隐藏 Key 的模型调用时，使用上面的本地 server 启动方式。

## 模型调用与 Key

页面上不会展示 API Key，也不会展示具体底层供应商或转发服务。

公开仓库只提交 `config.example.json`。本地演示时复制一份：

```powershell
Copy-Item config.example.json config.local.json
```

然后在 `config.local.json` 中填写私有 planner endpoint、model 和 key。这个文件被 `.gitignore` 忽略，不会提交到 GitHub。
如果没有单独的端侧 Computer-Use adapter，可以让 `edgeModel` 留空；server 会使用浏览器执行器按安全 action schema 操作模拟手机控件。

页面只显示：

- `Gemma 4B Computer-Use`
- `Cloud Planner Adapter`

实际请求由 `tools/demo-server.mjs` 的 `/api/plan` 代理完成。浏览器前端只能看到 `/api/plan`，看不到真实 Key、真实 endpoint、真实 model 或底层服务。server 会先请求私有 planner adapter，再把规划交给端侧 Computer-Use adapter 或浏览器执行器生成最终 GUI action。

如果没有配置 Key，demo 会自动使用本地安全 fallback action，保证演示可运行。

## Demo 行为

### Always-on 自动填表

执行链路：

1. 点击 `启动 Agent` 或用 `?autostart=1` 自动启动。
2. 页面生成脱敏观察摘要。
3. 本地 server 调用私有 planner adapter 或使用 fallback。
4. 执行器逐步操作模拟手机页面：
   - 输入姓名、年龄段、手机号、居住区域、紧急联系人
   - 选择报名课程
   - 停在 `提交报名`
5. 右侧实时显示每一步 action 和原因。

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
  verify-demo.mjs
config.example.json
run-demo-server.ps1
run-always-on-demo.ps1
run-trigger-demo.ps1
```

## 验证

```powershell
node .\tools\verify-demo.mjs
```
