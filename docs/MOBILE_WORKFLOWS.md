# 老白离线手机工作流

两个业务页面都是可直接通过 Android `file://` 打开的普通 HTML，不包含手机外壳、Agent 面板或模型调试信息。

## 统一动作协议

坐标以当前截图左上角为原点，单位为像素。每次页面切换或滑动后必须重新截图，不能继续使用旧截图里的坐标。

```json
{
  "protocol": "laobai-mobile-actions/v1",
  "actions": [
    {"type":"tap","x":120,"y":640,"label":"下一步"},
    {"type":"type_at","x":180,"y":310,"text":"李桂兰"},
    {"type":"select","x":180,"y":410,"value":"海淀区"},
    {"type":"scroll","x1":195,"y1":690,"x2":195,"y2":250,"durationMs":500},
    {"type":"wait","ms":600},
    {"type":"guard","x":195,"y":790,"label":"提交报名"}
  ]
}
```

- Playwright 适配器：`tap/type_at/select/scroll` 分别映射为点击、输入、选择和滚轮/触摸拖动。
- QPython 适配器：`tap` 映射为 Android 点击，`scroll` 映射为从 `(x1,y1)` 到 `(x2,y2)` 的 swipe；输入和系统权限接入后再实现。
- `guard` 只标记位置，不执行。提交报名、确认挂号、支付、验证码、授权和删除都必须停下。

## Always-on 填表

1. 截图识别当前可见字段并填写。
2. 当前屏幕没有下一字段时向上滑动，再截图。
3. 完成个人、居住、健康和紧急联系人信息后进入课程页。
4. 选择“智能手机基础班”和“周三上午”。
5. 进入确认页，在“提交报名”前输出 `guard`。

长表单的关键规则是“一屏一计划”：模型不能为屏幕外控件猜坐标。滑动后必须重新观察。

## 京医通挂号

规划固定使用 `historical-cloud-plan-replay`：北京协和医院、消化内科、李明主任医师、后天上午 10:00。该 JSON 是历史结果回放，运行时不访问云端模型。

端侧执行顺序：预约挂号 → 医院 → 科室 → 医生 → 号源 → 确认预约页。到达“确认挂号”后输出 `guard`，不确认、不支付。

## 本机验证

```powershell
node tools/mobile-workflow-runner.mjs all
```

结果、逐屏截图、动作坐标和规划回放保存在 `workflow-runs/<时间>/`。

## 本地 Gemma 4B 视觉验证

先安装 LiteRT-LM，再将 runner 生成的网格截图交给模型：

```powershell
.\tools\setup-litert-lm.ps1
.\.venv\Scripts\python.exe tools\gemma-vision-smoke.py `
  --model models\gemma-4-E4B-it.litertlm `
  --screenshot workflow-runs\<时间>\trigger\00-home-model-grid.png `
  --task "在京医通首页找到并点击预约挂号；只做当前这一步" `
  --width 390 --height 844 --max-tokens 4096
```

`max-tokens` 不能设成 256/512：手机截图的视觉输入本身可能超过 489 token。当前 E4B 能正确识别页面和动作意图，但小控件坐标仍可能偏一格，因此实际执行必须做边界/风险校验，并保留确定性回放兜底。
