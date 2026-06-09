# 技术架构

## 目标

老白 Agent Web Demo 用电脑上的浏览器模拟老人手机。页面保持竖屏手机比例，适合直接录屏。它不在界面展示 API Key。

## 总体结构

```text
PowerShell 启动脚本
  -> tools/demo-server.mjs
    -> 静态托管 web/*.html
    -> /api/public-config 暴露安全显示名
    -> /api/plan 隐藏 Key 并代理 planner adapter / 端侧 action adapter

HTML 手机模拟页
  -> web/agent.js
    -> 读取页面观察摘要
    -> 请求 /api/plan
    -> 按 action JSON 执行 click/type/select/guard
    -> 实时写入执行轨迹
```

## 模型层

页面上只展示两个演示能力：

- `Gemma 4B Computer-Use`：端侧屏幕理解、控件定位、动作执行。
- `Cloud Planner Adapter`：复杂规划，生成候选计划。
- 浏览器执行器或 `Gemma 4B Computer-Use` adapter：把候选计划转成可执行 GUI action。

实际 endpoint、model、key 存在本地 `config.local.json` 或环境变量里。公开仓库不提交真实 Key，也不暴露底层服务。
如果没有明确配置 `edgeModel`，server 不会强行调用端侧 adapter，而是直接使用浏览器执行器执行已校验的 action。

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

动作：

- 填写姓名：李桂兰
- 填写年龄段：70s
- 填写脱敏手机号：138****2675
- 填写居住区域：北京市朝阳区望京街道
- 填写紧急联系人：女儿 王敏
- 选择报名课程：智能手机基础课
- `guard` 停在提交报名

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
- planner 不可用时使用 deterministic fallback，保证演示稳定
