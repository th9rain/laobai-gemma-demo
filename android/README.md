# 老白 Android 工程

这是单 APK 的原生 Kotlin 演示工程，不依赖 QPython。构建时会把仓库根目录
`web/` 中的两个 HTML 作为 Android assets 打包进 APK：

- `always-on-form.html`
- `trigger-health.html`

## 已包含

- `MainActivity`：无障碍权限引导与两个本地 case 的入口。
- `CaseActivity`：全屏 WebView，直接加载 APK 内置 HTML。
- `LaoBaiAccessibilityService`：无障碍服务、可拖动悬浮气泡和确认面板。
- `WorkflowEngine`：按已缓存的离线 workflow 执行语义点击、文本填写、
  控件滚动以及必要时的垂直滑动。
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
离线演示，需要事先在目标手机安装中文离线语音包并用飞行模式验证。当前
workflow 是确定性的历史计划回放，不会在运行时调用云端大模型，也不会把
Gemma 4B 权重打进 APK。

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

这是用于演示和真机调试的 APK。首次安装后仍需由用户在系统设置中手动
启用“老白主动服务”无障碍服务，并在使用语音时授予麦克风权限。
