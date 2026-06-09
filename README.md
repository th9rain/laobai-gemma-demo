# 老白 Gemma Agent APK

`laobai-gemma-demo` 是一个面向比赛路演和手机实测的 Android Agent Demo。它不再只是页面内状态切换，而是通过 Android `AccessibilityService` 观察和操作真实 Android 控件：读取页面节点、填写输入框、点击低风险按钮，并在提交、支付、验证码等高风险动作前停止。

项目只保留两条核心 case：

- Demo 1：Always-on 自动填表助手
- Demo 2：Trigger 看病挂号助手

## APK 下载

APK 提交在仓库内：

```text
releases/laobai-gemma-demo-v0.2.0.apk
```

安装后需要在 Android 系统设置里启用无障碍服务：

```text
设置 -> 无障碍 -> 老白 Agent 手机操作服务
```

不启用无障碍服务时，首页可以打开，但 Agent 不能自动操作页面。

## Demo 1：Always-on 自动填表

表单页面：`北京市朝阳区社区智慧课堂报名表`

内置虚拟老人资料：

- 姓名：李桂兰
- 年龄段：70s
- 手机号：138****2675
- 居住区域：北京市朝阳区望京街道
- 紧急联系人：女儿 王敏
- 报名课程：智能手机基础课

演示流程：

1. 打开 App，确认无障碍服务已启用。
2. 点击 `Demo 1：Always-on 自动填表`。
3. 老白识别当前页面是报名表。
4. 无障碍服务向真实输入框写入本地记忆中的虚拟资料。
5. 停在 `提交报名` 前，不自动提交。

## Demo 2：Trigger 看病挂号

默认触发语：

```text
我胃不舒服，帮我挂号
```

北京医院候选：

- 北京协和医院
- 北京大学人民医院
- 北京朝阳医院

演示流程：

1. 打开 App，确认无障碍服务已启用。
2. 可选：在首页开启 Ark 云端 planner，并输入 API Key。
3. 点击 `Demo 2：Trigger 看病挂号`。
4. 老白自动点击 `开始问询`，询问持续时间和危险信号。
5. 老白自动点击演示回答，使用本地 embedding 知识库推荐 `消化内科`。
6. 如果启用了 Ark planner，会向云端发送脱敏摘要并显示“已调用 Ark 云端 planner”。
7. 老白填写模拟挂号页：医院、科室、时间、准备材料。
8. 停在 `确认挂号 / 支付 / 验证码` 前，不自动确认。

## 模型方案

- 端侧 Gemma：当前版本提供模型槽和本地 workflow 兜底；真实权重接入后再显示真实加载状态。
- 本地知识库：内置 `app/src/main/assets/embedding_kb.json`，用于挂号科室推荐演示。
- 云端 planner：支持在 App 内填入 Ark API Key 后调用 Ark Responses API。API Key 只保存在手机本机偏好里，不写入仓库。
- Gemma 主模型权重：不打进 APK，避免安装包过大；后续应通过 GitHub Release 附件或 App 内导入槽交付。

## 隐私边界

- 原始页面、身份证、完整手机号、验证码、病历原文默认不上云。
- 云端 planner 只接收脱敏症状摘要和本地建议。
- 高风险动作统一停止：提交、确认挂号、支付、验证码、授权、删除。

## 本地构建

```powershell
.\gradlew.bat assembleRelease
```

生成路径：

```text
app/build/outputs/apk/release/app-release.apk
```

Release 构建使用 debug signing，方便直接侧载安装测试。
