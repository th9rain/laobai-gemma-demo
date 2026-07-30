# 老白 Android 工程

这是单 APK 的原生 Kotlin 演示工程，不依赖 QPython。构建时会把仓库根目录
`web/` 中的两个 HTML 作为 Android assets 打包进 APK：

- `always-on-form.html`
- `trigger-health.html`

## 已包含

- `MainActivity`：无障碍权限引导、端侧模型管理与两个本地 case 的入口。
- `ModelDownloadService`：在一个 APK 内断点下载 E4B/E2B 权重、显示前台
  进度，并在安装前校验文件长度和 SHA-256。
- `GemmaRuntime`：通过 LiteRT-LM 0.14.0 运行真实端侧推理；视觉任务优先
  尝试 GPU，失败后回退 CPU 多模态，并在一次 workflow 内复用已加载引擎。
- `CaseActivity`：全屏 WebView，直接加载 APK 内置 HTML。
- `LaoBaiAccessibilityService`：无障碍服务、可拖动悬浮气泡和确认面板。
- `WorkflowEngine`：每个低风险步骤执行前先截图并运行端侧 Gemma VQA，
  严格校验模型 JSON、目标和动作类型后，再由语义安全执行器点击或填写。
- `TriggerPlannerReplay`：挂号 case 回放一次历史云端 Planner 的脱敏输入和
  原始输出；当前演示不会伪装成实时云调用。
- `ModelTraceActivity`：流程完成或失败后，逐条查看云侧回放和端侧 VQA 的
  输入截图、Prompt、原始输出、实际后端、耗时及校验结果。
- `AccessibilityNodeOps`：按 HTML ID、文字、提示和相对位置查找控件。
- `VoiceCaptureActivity`：用户点击后进行一次中文语音识别，支持“挂号”与
  “填表”两个口令；API 31+ 优先使用系统端侧识别能力。
- 安全策略会阻止自动点击“提交报名”“确认挂号”、支付、验证码等动作。

两个 case 可以从 APK 主界面直接打开，也可以把 `web/` 中的 HTML 复制到
手机后用浏览器打开。点击悬浮气泡后，可以直接确认启动，也可以点击“语音”
并说“挂号”或“填表”。工作流会锁定启动时的应用窗口；如果切换到其他 App，
它不会继续输入或点击。最终会在提交、挂号确认和支付之前停止，交回用户
人工确认。

语音识别由手机系统提供。`EXTRA_PREFER_OFFLINE` 只是离线偏好；要做完全
离线演示，需要事先在目标手机安装中文离线语音包并用飞行模式验证。

## 端侧 Gemma

APK 内已经集成 `com.google.ai.edge.litertlm:litertlm-android:0.14.0`，主界面
支持安装并运行两种官方 LiteRT-LM 模型：

- Gemma 4 E4B IT：3,659,530,240 bytes，默认用于小米 14 Pro。
- Gemma 4 E2B IT：2,588,147,712 bytes，作为低内存兼容选项。

权重不会直接压进基础 APK。E4B 大于 GitHub 单个 Release asset 上限，而且
Android 原生引擎需要可 mmap 的真实文件路径；硬塞进 APK 还会在安装与复制时
产生两份约 3.66GB 的数据。首次启动后由同一个 App 下载模型到 app-private
external files，支持断点续传并校验官方 SHA-256。校验完成后不再需要网络。

主界面的“运行真实端侧自检”会初始化 LiteRT-LM、执行一次真实生成并展示
模型原始输出、实际后端和耗时。Always On 的每个步骤都由真实端侧 VQA
门控；Trigger 先展示历史云端规划回放，再逐步调用真实端侧 VQA。模型返回
目标不可见或 `scroll` 时只允许一次受限滚动并重新截图；截图、推理或 JSON
校验失败时安全停止，不会在没有真实 VQA 的情况下伪装成完成。

小米 14 Pro 的 12GB/16GB 版本都具备运行 E4B 的基本内存条件；16GB 更稳，
12GB 建议先清理后台。若 E4B 发生内存不足或后端初始化失败，可在同一 APK
中下载并切换到 E2B。最终速度与 GPU delegate 兼容性仍需以真机自检为准。

Android 14 及以上使用窗口级无障碍截图，不会把老白悬浮窗拍进模型输入。
升级到这个版本后，需要在系统无障碍设置中关闭再重新开启一次“老白辅助
操作”，让系统刷新截图能力。

## 构建要求

- JDK 17
- Android SDK 35
- Gradle 8.9

当前工程还没有提交 Gradle Wrapper。首次构建前，需要先用本机安装的
Gradle 8.9 在本目录生成 Wrapper：

```powershell
gradle wrapper --gradle-version 8.9 --distribution-type bin
```

生成后使用 Wrapper 构建，避免依赖全局 Gradle 版本：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。

也可以用 Android Studio 打开本目录，但仍需在 IDE 中配置 JDK 17、
Android SDK 35，以及 Gradle 8.9 或上述 Wrapper。

## GitHub Actions 构建

仓库根目录的 `.github/workflows/build-apk.yml` 会在 `main` 分支中的
`android/`、`web/` 或构建工作流发生变化时自动构建，也支持在 Actions
页面手动触发。构建成功后下载 `laobai-android-apk` artifact，其中包含：

- `laobai-demo-debug.apk`
- `laobai-demo-debug.apk.sha256`

这是用于演示和真机调试的 APK。CI 还会检查 APK 内确实包含 ARM64
`liblitertlm_jni.so` 与两个 HTML。首次安装后仍需由用户在系统设置中手动
启用“老白辅助操作”无障碍服务，并在使用语音时授予麦克风权限。
