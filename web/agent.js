const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export async function bootAgent(options) {
  const state = {
    running: false,
    step: 0,
    config: { plannerLabel: "Cloud 30B Planner", edgeLabel: "Edge Computer-Use Policy", plannerOnline: false },
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
  if (plannerBadge) plannerBadge.textContent = state.config.plannerOnline ? "online" : "local fallback";

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
    addTrace("规划完成", `${plan.summary || "已生成安全动作序列"}（planner handoff complete）`);

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
}

async function requestPlan(scenario, observation) {
  const response = await fetch("/api/plan", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ scenario, observation }),
  });
  if (!response.ok) {
    throw new Error(`Planner request failed: ${response.status}`);
  }
  return response.json();
}

async function loadPublicConfig() {
  try {
    const response = await fetch("/api/public-config");
    if (!response.ok) return {};
    return response.json();
  } catch {
    return {};
  }
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
