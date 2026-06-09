const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

window.bootAgent = async function bootAgent(options) {
  const state = {
    running: false,
    step: 0,
    config: { plannerLabel: "Gemini 4 30B Cloud Model", edgeLabel: "Gemma 4B Computer-Use", plannerOnline: false },
  };

  const trace = document.querySelector("[data-trace]");
  const startButton = document.querySelector("[data-start]");
  const resetButton = document.querySelector("[data-reset]");
  const plannerBadge = document.querySelector("[data-planner-badge]");
  const plannerLabel = document.querySelector("[data-planner-label]");
  const edgeLabel = document.querySelector("[data-edge-label]");

  state.config = await loadPublicConfig();
  if (plannerLabel) plannerLabel.textContent = state.config.plannerLabel;
  if (edgeLabel) edgeLabel.textContent = state.config.edgeLabel;
  if (plannerBadge) plannerBadge.textContent = state.config.plannerOnline ? "ready" : "offline fallback";

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
    addTrace("启动 Agent", "正在读取屏幕结构，准备请求 planner。");
    showToast("老白正在观察当前手机页面。");

    const observation = options.observe();
    const plan = await requestPlan(options.scenario, observation);
    addTrace("隐私防火墙", runtimePrivacyText(plan.runtime));
    addTrace("Planner 状态", runtimePlannerText(plan.runtime));
    addTrace("Computer Use 状态", runtimeEdgeText(plan.runtime));
    addTrace("规划完成", `${plan.summary || "已生成安全动作序列"}（${plan.source || "safe-policy"}）`);

    for (const action of plan.actions || []) {
      await executeAction(action);
    }

    state.running = false;
  }

  async function executeAction(action) {
    const target = document.getElementById(action.target);
    const title = actionTitle(action);
    addTrace(title, action.reason || action.target, action.type === "guard");
    if (!target && action.type !== "wait") {
      showToast(`没有找到目标控件：${action.target}`);
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

  function addTrace(title, detail, guard = false) {
    state.step += 1;
    const item = document.createElement("div");
    item.className = `trace-item${guard ? " guard" : ""}`;
    item.innerHTML = `
      <div class="dot">${state.step}</div>
      <div>
        <div class="trace-title">${escapeHtml(title)}</div>
        <div class="trace-detail">${escapeHtml(detail || "")}</div>
      </div>
    `;
    trace?.appendChild(item);
    trace?.scrollTo({ top: trace.scrollHeight, behavior: "smooth" });
  }
};

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
    if (!response.ok) {
      return fallbackPlan(scenario);
    }
    return response.json();
  } catch {
    return fallbackPlan(scenario);
  }
}

async function loadPublicConfig() {
  if (window.location.protocol === "file:") {
    return {
      plannerLabel: "Gemini 4 30B Cloud Model",
      edgeLabel: "Gemma 4B Computer-Use",
      plannerOnline: false,
    };
  }
  try {
    const response = await fetch("/api/public-config");
    if (!response.ok) return {};
    return response.json();
  } catch {
    return {};
  }
}

function fallbackPlan(scenario) {
  if (scenario === "trigger-health") {
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
  return {
    ok: true,
    source: "static-html-policy",
    summary: "静态 HTML 模式：使用内置安全动作序列演示自动填表。",
    runtime: fallbackRuntime(),
    actions: [
      { type: "type", target: "name", value: "李桂兰", reason: "填写本地记忆中的姓名" },
      { type: "type", target: "age", value: "70s", reason: "填写年龄段" },
      { type: "type", target: "phone", value: "138****2675", reason: "只填写脱敏手机号" },
      { type: "type", target: "area", value: "北京市朝阳区望京街道", reason: "填写居住区域" },
      { type: "type", target: "contact", value: "女儿 王敏", reason: "填写紧急联系人" },
      { type: "select", target: "course", value: "智能手机基础课", reason: "选择偏好课程" },
      { type: "guard", target: "submit-button", reason: "提交报名属于高风险动作，必须停住" },
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

function runtimePrivacyText(runtime = {}) {
  const scope = runtime.privacyScope || "redacted-structured-observation";
  const hidden = runtime.keyVisibleToBrowser === false && runtime.providerVisibleToBrowser === false && runtime.modelNameVisibleToBrowser === false;
  return hidden
    ? `${scope}；Key、底层服务和真实调用参数不进入浏览器。`
    : `${scope}；当前只展示演示所需的抽象状态。`;
}

function runtimePlannerText(runtime = {}) {
  if (runtime.plannerHandoff) return "Gemini 4 30B 云侧模型已返回安全 JSON action plan。";
  if (runtime.plannerConfigured) return "Gemini 4 30B 云侧模型已配置，但本轮使用安全 fallback。";
  return "Gemini 4 30B 云侧模型未启用，本轮使用本地安全策略。";
}

function runtimeEdgeText(runtime = {}) {
  if (runtime.edgeHandoff) return "Gemma 4B Computer-Use adapter 已生成 GUI action。";
  if (runtime.edgeConfigured) return "Gemma 4B Computer-Use adapter 已配置，但本轮使用浏览器执行器。";
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
    guard: "安全守卫停止",
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
