# Gemma 权重说明

当前 APK 不内置 Gemma 主模型权重。

原因：

- Gemma LiteRT / MediaPipe 端侧权重通常较大。
- 直接打进 APK 会导致安装包过大。
- 公开仓库不适合硬编码模型下载 token 或私有访问凭证。

建议交付方式：

1. APK 通过 `releases/laobai-gemma-demo-v0.1.0.apk` 分发。
2. Gemma 权重作为 GitHub Release 附件单独上传。
3. 用户下载安装到手机后，通过后续版本的导入槽选择本地权重。

当前 v0.1.0 已经包含：

- 本地 workflow。
- 本地 embedding demo asset。
- Gemma 状态展示。
- 可选 Ark 云端 planner。
