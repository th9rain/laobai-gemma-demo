# 老白 Gemma Demo APK

`laobai-gemma-demo` 是一个为黑客松路演录屏准备的 Android 演示 APK。它聚焦两条老人手机 Agent 场景：

- Demo 1：Always-on 固定表单填写助手
- Demo 2：Trigger 看病挂号助手

项目只使用 Gemma 叙事，不接其他非 Gemma 模型。当前版本把两条能力做成稳定的本地 workflow，保证 APK 可以在安卓手机上直接跑通并录屏。

## APK 下载

APK 会提交在仓库内：

```text
releases/laobai-gemma-demo-v0.1.0.apk
```

如果该文件暂时不存在，请到 GitHub Actions 的 `Build APK` workflow 下载构建产物，或等待下一次提交把 APK 同步回 `releases/`。

## 两个 Demo

### Always-on 固定表单

表单题目：`北京市朝阳区社区智慧课堂报名表`

内置虚拟老人资料：

- 姓名：李桂兰
- 年龄段：70s
- 手机号：138****2675
- 居住区域：北京市朝阳区望京街道
- 紧急联系人：女儿 王敏
- 偏好课程：智能手机基础课

演示路径：

1. 点击 `模拟打开表单`
2. Always-on Sentinel 识别固定报名表
3. 弹出“要不要帮您填写常用信息”
4. 点击 `同意，帮我填写`
5. App 自动填写虚拟资料
6. 停在 `提交报名` 前，不自动提交

### Trigger 看病挂号

默认触发语：

```text
我胃不舒服，帮我挂号
```

北京医院候选：

- 北京协和医院
- 北京大学人民医院
- 北京朝阳医院

演示路径：

1. 点击 `Trigger`
2. 老白进行本地健康问询
3. 点击回答按钮
4. 本地 embedding 知识库推荐 `消化内科`
5. 进入模拟挂号页
6. 停在 `确认挂号 / 支付 / 验证码` 前

## 模型方案

- Gemma 主模型：不内置进 APK，避免 APK 过大。
- App 内有 `Gemma 端侧模型状态` 开关，用于录屏展示“模型已加载”状态。
- 本地知识库：内置一个小型 embedding demo asset：`app/src/main/assets/embedding_kb.json`。
- 演示云端 planner：支持填入 Ark API Key 后调用火山 Ark Responses API；API Key 不写入仓库。

没有 API Key 时，所有 demo 仍然可以离线跑通。

## 隐私边界

- 原始屏幕、身份证、手机号、验证码、完整病历原文不上传。
- 表单 demo 只使用虚拟资料。
- 挂号 demo 只上传脱敏摘要；默认不开启云端 planner。
- 高风险动作，包括提交、付款、验证码、授权，全部停住。

## 本地构建

```powershell
.\gradlew.bat assembleRelease
```

生成路径：

```text
app/build/outputs/apk/release/app-release.apk
```

该 APK 使用 debug signing 的 release build，可以直接侧载安装测试。
