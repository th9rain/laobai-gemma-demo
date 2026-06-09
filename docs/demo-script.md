# 路演录屏脚本

## 准备

1. 配置 `config.local.json`，填入 planner endpoint、model 和 key。
2. 运行：

```powershell
.\run-demo-server.ps1
```

3. 浏览器打开首页后开始录屏。

## Demo 1：Always-on 自动填表

1. 点击 `Always-on 自动填表`。
2. 页面展示一个竖屏手机报名表。
3. 点击 `启动 Agent`。
4. 右侧展示：
   - Edge Computer-Use Policy
   - Cloud 30B Planner
5. Agent 自动填写：
   - 李桂兰
   - 70s
   - 138****2675
   - 北京市朝阳区望京街道
   - 女儿 王敏
   - 智能手机基础课
6. Agent 停在 `提交报名` 前。

讲解重点：Always-on 不是连续录屏，而是页面状态触发；端侧执行低风险动作，提交前停住。

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

讲解重点：Trigger 模式由用户主动触发；云侧只拿脱敏摘要做规划，端侧负责执行和安全守卫。

## 话术

```text
老白不是替老人做危险决定，而是帮老人完成低风险、重复、容易出错的页面操作。
涉及提交、支付、验证码和授权时，它一定停住。
```

```text
界面上看不到 Key，也看不到底层供应商。演示中只展示端侧 Computer-Use 能力和云侧 30B Planner 的协作。
```
