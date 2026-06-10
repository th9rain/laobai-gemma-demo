const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

let traceStep = 0;
let modelCallCount = 0;

window.addAgentTrace = function addAgentTrace(title, detail, guard = false) {
  traceStep += 1;
  const trace = document.querySelector("[data-trace]");
  const item = document.createElement("div");
  item.className = `trace-item${guard ? " guard" : ""}`;
  item.innerHTML = `
    <div class="dot">${traceStep}</div>
    <div>
      <div class="trace-title">${escapeHtml(title)}</div>
      <div class="trace-detail">${escapeHtml(detail || "")}</div>
    </div>
  `;
  trace?.appendChild(item);
  trace?.scrollTo({ top: trace.scrollHeight, behavior: "smooth" });
};

window.renderModelCall = function renderModelCall(call = {}) {
  const modelIo = document.querySelector("[data-model-io]");
  if (!modelIo) return;
  modelCallCount += 1;
  const item = document.createElement("details");
  item.className = "model-call";
  item.open = true;
  const screenshot = call.screenshotDataUrl
    ? `<div class="model-call-block">
        <div class="model-call-label">模型输入截图</div>
        <img class="model-screenshot" src="${escapeHtml(call.screenshotDataUrl)}" alt="Gemma screenshot input" />
      </div>`
    : "";
  const parsed = call.parsedActions
    ? `<div class="model-call-block">
        <div class="model-call-label">解析后的坐标动作</div>
        <pre>${escapeHtml(JSON.stringify(call.parsedActions, null, 2))}</pre>
      </div>`
    : "";
  item.innerHTML = `
    <summary>${escapeHtml(call.title || `本地 Gemma 调用 ${modelCallCount}`)}</summary>
    ${screenshot}
    <div class="model-call-block">
      <div class="model-call-label">模型输入 Prompt</div>
      <pre>${escapeHtml(call.input || "")}</pre>
    </div>
    <div class="model-call-block">
      <div class="model-call-label">模型原始输出</div>
      <pre>${escapeHtml(call.output || "")}</pre>
    </div>
    ${parsed}
  `;
  modelIo.appendChild(item);
  modelIo.scrollTo({ top: modelIo.scrollHeight, behavior: "smooth" });
};

window.bootPassiveAlwaysOnPage = async function bootPassiveAlwaysOnPage() {
  drawCoordinateGrid();
  const startButton = document.querySelector("[data-start]");
  startButton?.addEventListener("click", () => {
    window.addAgentTrace(
      "需要外部坐标 Runner",
      "请在 PowerShell 运行 .\\run-always-on.ps1。网页本身不会生成动作，避免伪造 computer-use。",
      true,
    );
    showToast("请从 PowerShell 启动真实截图坐标链路。", true);
  });
  window.addAgentTrace(
    "等待截图",
    "Always-on 当前只接受外部 Playwright runner 截图后的真实 Gemma 坐标 JSON。",
  );
};

function drawCoordinateGrid() {
  const overlay = document.querySelector(".coordinate-grid-overlay");
  const screen = document.querySelector("[data-computer-use-surface]");
  if (!overlay || !screen) return;
  const width = Math.round(screen.getBoundingClientRect().width);
  const height = Math.round(screen.getBoundingClientRect().height);
  overlay.innerHTML = "";
  for (let x = 100; x < width; x += 100) {
    const line = document.createElement("div");
    line.className = "coordinate-grid-line-x";
    line.style.left = `${x}px`;
    overlay.appendChild(line);
    const label = document.createElement("div");
    label.className = "coordinate-grid-label";
    label.style.left = `${x + 2}px`;
    label.style.top = "36px";
    label.textContent = `x${x}`;
    overlay.appendChild(label);
  }
  for (let y = 100; y < height; y += 100) {
    const line = document.createElement("div");
    line.className = "coordinate-grid-line-y";
    line.style.top = `${y}px`;
    overlay.appendChild(line);
    const label = document.createElement("div");
    label.className = "coordinate-grid-label";
    label.style.left = "8px";
    label.style.top = `${y + 2}px`;
    label.textContent = `y${y}`;
    overlay.appendChild(label);
  }
}

window.bootAgent = async function bootAgent(options) {
  const state = {
    running: false,
    config: { plannerLabel: "Gemma 4 30B Cloud Planner", edgeLabel: "Gemma 4B Computer-Use", plannerOnline: false },
  };

  const startButton = document.querySelector("[data-start]");
  const resetButton = document.querySelector("[data-reset]");
  const plannerBadge = document.querySelector("[data-planner-badge]");
  const plannerLabel = document.querySelector("[data-planner-label]");
  const edgeLabel = document.querySelector("[data-edge-label]");

  state.config = await loadPublicConfig();
  if (plannerLabel) plannerLabel.textContent = state.config.plannerLabel || plannerLabel.textContent;
  if (edgeLabel) edgeLabel.textContent = state.config.edgeLabel || edgeLabel.textContent;
  if (plannerBadge) {
    plannerBadge.textContent = state.config.plannerOnline ? "ready" : "offline fallback";
  }

  startButton?.addEventListener("click", () => run());
  resetButton?.addEventListener("click", () => window.location.reload());

  if (new URLSearchParams(window.location.search).get("autostart") === "1") {
    await delay(450);
    run();
  }

  async function run() {
    if (state.running) return;
    state.running = true;
    startButton.disabled = true;
    window.addAgentTrace("启动 Agent", "正在读取脱敏页面状态，准备请求云端 planner。");
    showToast("老白正在处理挂号请求。");

    const observation = options.observe();
    const plan = await requestPlan(options.scenario, observation);
    renderModelCalls(plan.modelCalls);
    window.addAgentTrace("隐私边界", runtimePrivacyText(plan.runtime));
    window.addAgentTrace("Planner 状态", runtimePlannerText(plan.runtime));
    window.addAgentTrace("Computer Use 状态", runtimeEdgeText(plan.runtime));
    window.addAgentTrace("规划完成", `${plan.summary || "已生成安全动作序列"}；source=${plan.source || "safe-policy"}`);

    for (const action of plan.actions || []) {
      await executeDomAction(action);
    }

    state.running = false;
  }
};

async function executeDomAction(action) {
  const target = document.getElementById(action.target);
  const title = actionTitle(action);
  window.addAgentTrace(title, action.reason || action.target, action.type === "guard");
  if (!target && action.type !== "wait") {
    showToast(`没有找到目标控件：${action.target}`, true);
    await delay(600);
    return;
  }

  if (target) {
    target.classList.add("agent-active");
    target.scrollIntoView({ behavior: "smooth", block: "center" });
  }

  await delay(550);

  if (action.type === "type") {
    await typeInto(target, action.value || "");
  } else if (action.type === "select") {
    selectValue(target, action.value || "");
  } else if (action.type === "click") {
    target.click();
  } else if (action.type === "guard") {
    showToast(`安全守卫：${action.reason || "高风险动作需要用户确认"}`, true);
  } else if (action.type === "wait") {
    await delay(Number(action.value || 700));
  }

  await delay(action.type === "guard" ? 1000 : 350);
  if (target) target.classList.remove("agent-active");
}

async function requestPlan(scenario, observation) {
  if (window.location.protocol === "file:") {
    return fallbackPlan(scenario);
  }
  try {
    const response = await fetch("/api/plan", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ scenario, observation }),
    });
    if (!response.ok) return fallbackPlan(scenario);
    return response.json();
  } catch {
    return fallbackPlan(scenario);
  }
}

async function loadPublicConfig() {
  if (window.location.protocol === "file:") return {};
  try {
    const response = await fetch("/api/public-config");
    if (!response.ok) return {};
    return response.json();
  } catch {
    return {};
  }
}

function fallbackPlan(scenario) {
  if (scenario !== "trigger-health") {
    return {
      ok: false,
      source: "no-fallback",
      summary: "Always-on 不提供前端 fallback。",
      runtime: fallbackRuntime(),
      actions: [],
    };
  }
  return {
    ok: true,
    source: "static-html-policy",
    summary: "静态 HTML 模式：使用内置安全动作序列演示挂号流程。",
    runtime: fallbackRuntime(),
    actions: [
      { type: "click", target: "ask-button", reason: "用户触发后先做端侧问询" },
      { type: "click", target: "answer-button", reason: "演示回答：两天，轻微恶心，无胸痛" },
      { type: "select", target: "hospital", value: "北京协和医院", reason: "北京固定候选医院中优先选择协和" },
      { type: "select", target: "department", value: "消化内科", reason: "胃部不适匹配消化内科" },
      { type: "select", target: "date", value: "明天上午", reason: "选择较近的可用时段" },
      { type: "type", target: "materials", value: "身份证、医保卡、既往病历", reason: "提醒准备材料" },
      { type: "guard", target: "confirm-button", reason: "确认挂号、支付、验证码必须由用户确认" },
    ],
  };
}

function fallbackRuntime() {
  return {
    privacyScope: "local-static-observation",
    keyVisibleToBrowser: false,
    providerVisibleToBrowser: false,
    modelNameVisibleToBrowser: false,
    plannerConfigured: false,
    plannerHandoff: false,
    plannerStatus: "static-html",
    edgeConfigured: false,
    edgeHandoff: false,
    edgeStatus: "browser-executor",
  };
}

function renderModelCalls(calls = []) {
  if (!Array.isArray(calls)) return;
  for (const call of calls) window.renderModelCall(call);
}

function runtimePrivacyText(runtime = {}) {
  const scope = runtime.privacyScope || "redacted-structured-observation";
  const hidden = runtime.keyVisibleToBrowser === false && runtime.providerVisibleToBrowser === false && runtime.modelNameVisibleToBrowser === false;
  return hidden
    ? `${scope}；Key、底层服务和真实调用参数不进入浏览器。`
    : `${scope}；当前只展示演示所需的抽象状态。`;
}

function runtimePlannerText(runtime = {}) {
  if (runtime.plannerHandoff) return "云端 planner 已返回安全 JSON action plan。";
  if (runtime.plannerConfigured) return "云端 planner 已配置，但本轮使用安全 fallback。";
  return "云端 planner 未启用，本轮使用本地安全策略。";
}

function runtimeEdgeText(runtime = {}) {
  if (runtime.localGemmaHandoff) return "本地 Gemma / computer-use adapter 已生成 GUI action。";
  if (runtime.edgeHandoff) return "computer-use adapter 已生成 GUI action。";
  return "浏览器执行器按安全 action schema 操作模拟手机控件。";
}

async function typeInto(element, value) {
  element.focus();
  element.value = "";
  element.dispatchEvent(new Event("input", { bubbles: true }));
  for (const char of value) {
    element.value += char;
    element.dispatchEvent(new Event("input", { bubbles: true }));
    await delay(26);
  }
}

function selectValue(element, value) {
  if (element.tagName === "SELECT") {
    const option = Array.from(element.options).find((item) => item.value === value || item.textContent === value);
    if (option) element.value = option.value;
  } else {
    element.value = value;
  }
  element.dispatchEvent(new Event("change", { bubbles: true }));
  element.dispatchEvent(new Event("input", { bubbles: true }));
}

function actionTitle(action) {
  const map = {
    click: "Computer Use：点击",
    type: "Computer Use：输入",
    select: "Computer Use：选择",
    wait: "等待页面变化",
    guard: "安全守卫停住",
  };
  return `${map[action.type] || "执行动作"} ${action.target || ""}`.trim();
}

function showToast(text, danger = false) {
  const toast = document.querySelector("[data-toast]");
  if (!toast) return;
  toast.textContent = text;
  toast.style.background = danger ? "rgba(173, 61, 43, 0.96)" : "rgba(34, 91, 77, 0.96)";
  toast.classList.add("show");
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => toast.classList.remove("show"), danger ? 2400 : 1400);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#039;",
  }[char]));
}
