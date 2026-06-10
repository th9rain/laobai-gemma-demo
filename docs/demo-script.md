# 路演录屏脚本

## 准备

1. 确认本地 Gemma 权重和 LiteRT-LM 环境已准备好。
2. 只录 Always-on 真实本地 Gemma 权重版本时运行：

```powershell
.\run-always-on-demo.ps1
```

3. 如果想演示“外部脚本读取模型输出后真实操作网页控件”，运行：

```powershell
npm install
npx playwright install chromium
.\run-external-always-on.ps1
```

这个脚本默认打开可见浏览器窗口，适合录屏。只做后台验证时，可以先设置 `$env:LAOBAI_HEADLESS="1"`。

4. 录完整 demo 时运行：

```powershell
.\run-demo-server.ps1
```

5. 浏览器打开首页后开始录屏。

Always-on 必须通过本地 server 调用真实 Gemma 权重，不能直接双击 HTML 文件运行。Trigger 页面在没有云端配置时仍保留本地安全 fallback。

## Demo 1：Always-on 自动填表

1. 点击 `Always-on 自动填表`。
2. 页面展示一个竖屏手机报名表。
3. 点击 `启动 Agent`。
4. 右侧展示：
   - Gemma 4B Computer-Use
   - 真实本地 Gemma 权重
5. 右侧展开 `模型输入 Prompt` 和 `模型原始输出`。内容来自 `tools/always-on-workflow.py` 对本地 Gemma LiteRT 权重的实际调用。
6. 第一次本地 Gemma 调用后，Agent 自动填写第一页：
   - 李桂兰
   - 70s
   - 138****2675
7. Agent 点击 `下一页`。
8. 页面翻到第二页后，Agent 重新观察当前页面，并进行第二次本地 Gemma 调用。
9. 第二次调用后，Agent 自动填写：
   - 北京市朝阳区望京街道
   - 女儿 王敏
   - 智能手机基础课
   - 想学会微信视频、线上挂号和识别诈骗短信。
10. Agent 停在 `提交报名` 前。

讲解重点：Always-on 不是连续录屏，而是页面状态触发；端侧 Gemma 权重生成低风险 GUI action，提交前停住。
Always-on 不请求云端 Planner，也不使用静态 fallback。两页表单会触发两次本地模型调用，右侧能看到每次模型的输入和输出。

## Demo 2：Trigger 看病挂号

1. 点击 `Trigger 看病挂号`。
2. 页面展示用户请求：`我胃不舒服，帮我挂号`。
3. 点击 `启动 Agent`。
4. Agent 自动点击开始问询。
5. Agent 自动点击演示回答。
6. Agent 选择：
   - 北京协和医院
   - 消化内科
   - 明天上午
7. Agent 填写准备材料。
8. Agent 停在 `确认挂号 / 支付 / 验证码` 前。

讲解重点：Trigger 模式由用户主动触发；planner 只拿脱敏摘要做规划，端侧负责执行和安全守卫。

## 话术

```text
老白不是替老人做危险决定，而是帮老人完成低风险、重复、容易出错的页面操作。
涉及提交、支付、验证码和授权时，它一定停住。
```

```text
界面上看不到 Key、真实 endpoint 或底层服务。Always-on 展示真实本地 Gemma 权重生成 GUI action；Trigger 展示 Gemma 4B Computer-Use 和 Gemma 4 30B Cloud Planner 的协作。
```
