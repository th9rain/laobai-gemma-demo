# 技术架构

## 目标

老白提供两个独立的真实业务 HTML，后续由 Android 本地文件直接打开。页面本身不包含手机外壳、Agent 面板或模型调试界面。

## 总体结构

```text
PowerShell 启动脚本
  -> tools/demo-server.mjs
    -> 静态托管 web/*.html
    -> /api/public-config 暴露安全显示名
    -> /api/plan 隐藏 Key，并按 scenario 路由到本地 workflow 或云侧 planner

独立业务 HTML
  -> web/always-on-form.html
  -> web/trigger-health.html

端侧执行验证
  -> tools/mobile-workflow-runner.mjs
  -> tap/type_at/select/scroll/wait/guard 动作协议
  -> 最终提交或确认前停止
```

Always-on 真实本地权重入口：

```text
run-always-on.ps1
  -> 忽略 config.local.json
  -> 清空云端 planner / edge 环境变量
  -> 启用 LAOBAI_LOCAL_GEMMA_ENABLED=1
  -> 打开 always-on-form.html
  -> /api/plan 进入 always-on-local-only
  -> tools/always-on-workflow.py 调用本地 Gemma 权重
```

Always-on 外部执行器入口：

```text
tools/run-external-always-on.ps1
  -> 启动本地 server，但不打开浏览器
  -> tools/external-always-on-runner.mjs 打开 always-on-form.html?external=1
  -> Playwright 在页面外部读取 observeAlwaysOnForm()
  -> POST /api/plan 获取本地 Gemma workflow 输出
  -> Playwright 按模型输出执行 fill/select/click
  -> 打印最终表单状态和两次模型调用摘要
```

命令行 workflow 验证入口：

```text
tools/run-always-on-workflow.ps1
  -> 使用 tools/always-on-workflow.py 内置固定表单 observation
  -> 调用 tools/always-on-workflow.py
  -> 输出本地 action JSON
```

## 模型层

页面上只展示两个演示能力：

- `Gemma 4B Computer-Use`：端侧屏幕理解、控件定位、动作执行。
- `Gemma 4B/E4B LiteRT Computer-Use`：本地读取 `models/gemma-4-E4B-it.litertlm`。Always-on 必须由这个本地权重生成 GUI action；Trigger 可继续使用云端 Planner 后再交给本地 Gemma / 执行器转换。
- `Gemma 4 30B Cloud Planner`：只用于 Trigger 看病挂号这类复杂规划，生成候选计划。

实际 endpoint、model、key 存在本地 `config.local.json` 或环境变量里。公开仓库不提交真实 Key，也不暴露底层服务。
如果配置 `localGemmaEnabled=true`，server 会优先调用本地 Gemma LiteRT 模型。
Always-on 分支不调用云端 planner：它调用 `tools/always-on-workflow.py`，由 Python 脚本跑本地模型并解析输出。
Trigger 分支才会先调用云端 planner，再调用本地 Gemma 生成/校验 GUI action；没有本地权重或 LiteRT-LM 环境时，继续保留浏览器执行器 fallback。

## Planner 输出协议

`/api/plan` 返回：

```json
{
  "ok": true,
  "source": "cloud-planner",
  "summary": "一句中文摘要",
  "actions": [
    {
      "type": "click",
      "target": "ask-button",
      "value": "",
      "reason": "中文原因"
    }
  ]
}
```

允许的动作类型：

- `click`
- `type`
- `select`
- `wait`
- `guard`

高风险目标只能用 `guard`，不能用 `click`。

## Demo 1：Always-on 自动填表

页面：`web/always-on-form.html`

链路：

- `run-always-on.ps1` 显式关闭云端配置，只启用本地 Gemma
- 第 1 页 observation 识别基础信息表单
- `/api/plan` 进入 `always-on-local-only` 分支
- `tools/always-on-workflow.py` 调用本地 Gemma LiteRT 权重，并返回 `modelInput` / `modelOutput`
- Python 脚本解析模型输出，要求模型返回完整 JSON action plan；缺字段或输出非法时直接失败
- 执行器填写第一页并点击 `下一页`
- 第 2 页重新 observation，再次调用本地 Gemma
- 执行器填写第二页，并用 `guard` 停在提交前

动作：

- 填写姓名：李桂兰
- 填写年龄段：70s
- 填写脱敏手机号：138****2675
- 点击下一页
- 填写居住区域：北京市朝阳区望京街道
- 填写紧急联系人：女儿 王敏
- 选择报名课程：智能手机基础课
- 填写学习目标：想学会微信视频、线上挂号和识别诈骗短信。
- `guard` 停在提交报名

右侧模型 I/O 面板展示每次调用的：

- `模型输入 Prompt`：包含当前页码、可见控件 id、端侧本地记忆、脱敏 observation 和安全规则。
- `模型原始输出`：Gemma LiteRT 返回的原文，便于录屏时证明不是静态字幕。

## Demo 2：Trigger 看病挂号

页面：`web/trigger-health.html`

动作：

- 点击开始问询
- 点击演示回答
- 选择北京协和医院
- 选择消化内科
- 选择明天上午
- 填写准备材料
- `guard` 停在确认挂号 / 支付 / 验证码

## 安全策略

`tools/demo-server.mjs` 会过滤 planner 返回的 action：

- 只允许 `click/type/select/wait/guard`
- 如果目标包含 `submit/confirm/payment/otp/delete/authorize`，非 `guard` 动作会被丢弃
- Trigger planner 不可用时使用 deterministic fallback，保证挂号演示稳定；Always-on 不使用静态 fallback
