# 模型配置说明

当前 Web Demo 不把模型权重放进仓库。

原因：

- 端侧模型权重体积大，不适合直接提交 Git。
- planner Key 不能提交到公开仓库。
- 路演演示更需要稳定的 GUI action 链路。

## 本地配置

复制模板：

```powershell
Copy-Item config.example.json config.local.json
```

配置示例：

```json
{
  "plannerEndpoint": "https://example.com/api/v3/responses",
  "plannerModel": "cloud-planner-model",
  "plannerApiKey": "your-local-key",
  "edgeEndpoint": "https://example.com/api/v3/responses",
  "edgeModel": "",
  "edgeApiKey": "your-local-key",
  "publicPlannerLabel": "Gemini 4 30B Cloud Model",
  "publicEdgeLabel": "Gemma 4B Computer-Use"
}
```

`config.local.json` 已加入 `.gitignore`，不会提交。
`edgeModel` 留空时会复用 `plannerModel` 做第二段 Computer-Use action 转换；如果你有独立端侧 adapter，再单独填写 `edgeEndpoint/edgeModel/edgeApiKey`。

## 替换真实模型

后续如果接入真实端侧模型和云侧 planner 能力：

1. 保持前端 action schema 不变。
2. 在 `config.local.json` 或环境变量中替换 planner endpoint。
3. 将 fallback action 替换为模型输出校验后的 action。
4. 保留高风险 action guard。
