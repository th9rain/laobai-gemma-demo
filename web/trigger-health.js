const triggerDelay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

let triggerTraceStep = 0;
let triggerIoCount = 0;
let triggerRunning = false;
let triggerPlan = null;

const triggerNodes = {
  fab: document.querySelector("[data-agent-fab]"),
  chat: document.querySelector("[data-chat-sheet]"),
  chatStatus: document.querySelector("[data-chat-status]"),
  messages: document.querySelector("[data-chat-messages]"),
  accept: document.querySelector("[data-accept-booking]"),
  close: document.querySelector("[data-close-chat]"),
  start: document.querySelector("[data-start-trigger]"),
  reset: document.querySelector("[data-reset]"),
  trace: document.querySelector("[data-trace]"),
  io: document.querySelector("[data-model-io]"),
  pointer: document.querySelector("[data-local-pointer]"),
  cloudStatus: document.querySelector("[data-cloud-status]"),
  localStatus: document.querySelector("[data-local-status]"),
  runStatus: document.querySelector("[data-run-status]"),
  ioStatus: document.querySelector("[data-io-status]"),
};

triggerNodes.fab?.addEventListener("click", openTriggerAssistant);
triggerNodes.start?.addEventListener("click", openTriggerAssistant);
triggerNodes.close?.addEventListener("click", () => {
  if (triggerNodes.chat) triggerNodes.chat.hidden = true;
});
triggerNodes.reset?.addEventListener("click", () => window.location.reload());
triggerNodes.accept?.addEventListener("click", runTriggerPlanningFlow);

addTriggerTrace("等待 Trigger", "老人感觉胃不舒服时，点击手机右侧老白浮窗主动求助。");

if (new URLSearchParams(window.location.search).get("autostart") === "1") {
  setTimeout(openTriggerAssistant, 500);
}

async function openTriggerAssistant() {
  if (!triggerNodes.chat) return;
  triggerNodes.chat.hidden = false;
  triggerNodes.chatStatus.textContent = "正在观察屏幕";
  triggerNodes.fab?.classList.add("agent-active");
  if (!triggerNodes.messages.dataset.seeded) {
    addTriggerMessage("assistant", "看你不舒服，我帮你挂号吧，需不需要帮你挂最近常去的医院？");
    triggerNodes.messages.dataset.seeded = "1";
    addTriggerTrace("Trigger 已触发", "用户主动打开老白浮窗，对话入口已建立。");
  }
}

async function runTriggerPlanningFlow() {
  if (triggerRunning) return;
  triggerRunning = true;
  if (triggerNodes.accept) triggerNodes.accept.disabled = true;
  addTriggerMessage("user", "好的，挂号。");
  addTriggerMessage("assistant thinking", "我先看你的日程和常去医院，再决定合适时间。");
  setTriggerStatus("planning");
  triggerNodes.cloudStatus.textContent = "Planner 回放中";
  addTriggerTrace("云端 Planner 回放", "展示历史 30B Planner 调用记录：只包含脱敏症状、日程摘要和常去医生，不包含原始截图、身份证号、完整手机号。");

  const observation = buildTriggerObservation();
  triggerPlan = await requestTriggerPlan(observation);
  renderTriggerModelCalls(triggerPlan.modelCalls || []);
  triggerNodes.cloudStatus.textContent = "cached plan 已返回";

  const appointment = triggerPlan.appointment || {};
  addTriggerMessage(
    "assistant",
    `我看了你的日程：明天要参加手机培训比赛，所以给你安排 ${appointment.date || "后天"} ${appointment.time || "上午 10:00"}，${appointment.hospital || "北京协和医院"} ${appointment.department || "消化内科"}，医生是 ${appointment.doctor || "李明 主任医师"}。`,
  );
  addTriggerTrace("规划完成", triggerPlan.summary || "云端 Planner 回放已给出挂号计划。");

  triggerNodes.localStatus.textContent = "等待本地 Gemma";
  addTriggerMessage("assistant thinking", "接下来我会在手机本地打开挂号 App，并帮你填到确认页。");
  triggerNodes.chat?.classList.add("compact");

  if (new URLSearchParams(window.location.search).get("external") !== "1") {
    addTriggerTrace(
      "网页预览模式",
      "当前是直接打开网页的预览动作，不会加载 Gemma 权重。真实截图坐标执行请运行 PowerShell：.\\run-trigger.ps1。",
      true,
    );
    await executeDemoPreviewActions(triggerPlan.actions || []);
    triggerNodes.localStatus.textContent = "预览完成";
    setTriggerStatus("preview");
    triggerRunning = false;
  }
}

function buildTriggerObservation() {
  return {
    device: "mobile-portrait-sim",
    userRequest: "我胃不舒服，帮我挂号",
    symptomSummary: "胃部不适持续两天，轻微恶心，无胸痛，无持续高热；适合先挂消化内科普通门诊。",
    scheduleSummary: [
      { day: "明天", time: "09:30-17:30", title: "参加手机培训比赛彩排", availability: "busy" },
      { day: "后天", time: "10:00", title: "空闲，可就医", availability: "free" },
    ],
    localMemory: {
      patientAlias: "李桂兰",
      ageGroup: "70s",
      maskedPhone: "138****2675",
      homeArea: "北京市朝阳区望京街道",
      nearestHospital: "北京协和医院",
      frequentDepartment: "消化内科",
      frequentDoctor: "李明 主任医师",
    },
    privacy: {
      rawScreenUploaded: false,
      idNumberUploaded: false,
      fullPhoneUploaded: false,
      rawMedicalRecordUploaded: false,
      redaction: "strict",
    },
    goal: "生成挂号计划。必须避开明天比赛，优先后天上午 10 点；确认挂号、支付、验证码前必须 guard。",
  };
}

async function requestTriggerPlan(observation) {
  const response = await fetch("/api/plan", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ scenario: "trigger-health", observation }),
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Trigger plan failed: ${response.status} ${text}`);
  }
  return response.json();
}

async function executeDemoPreviewActions(actions) {
  document.querySelector("[data-trigger-computer-use-surface]")?.classList.add("runner-capture");
  triggerNodes.localStatus.textContent = "预览执行中";
  for (const action of actions) {
    addTriggerTrace(localTriggerActionTitle(action), action.reason || action.value || action.target, action.type === "guard");
    await applyTriggerAction(action);
    await triggerDelay(action.type === "guard" ? 260 : 360);
  }
  triggerNodes.localStatus.textContent = "确认前停止";
  setTriggerStatus("guarded");
}

async function applyTriggerAction(action) {
  if (action.type === "wait") {
    await triggerDelay(Number(action.value || 500));
    return;
  }
  const target = document.getElementById(action.target);
  if (!target) return;
  target.classList.add("agent-active");
  showTriggerPointer(target, action.type === "guard");
  if (action.target === "app-jingyitong") {
    await triggerDelay(450);
    switchTriggerView("hospital");
  } else if (["hospital-card", "department-card", "doctor-card", "time-card", "patient-card"].includes(action.target)) {
    const value = target.querySelector("[data-value]");
    if (value) value.textContent = action.value || "已选择";
    target.classList.add("selected");
  } else if (action.type === "guard") {
    addTriggerMessage("assistant", action.confirmationText || "我已经填到确认挂号页。确认挂号、支付和验证码必须你自己看清楚后再点。");
  }
  await triggerDelay(320);
  target.classList.remove("agent-active");
}

window.renderTriggerExternalEvent = function renderTriggerExternalEvent(event) {
  addTriggerTrace(event.title, event.detail, event.guard);
};

window.renderTriggerExternalModelCall = function renderTriggerExternalModelCall(call) {
  renderTriggerModelCalls([call]);
};

window.applyTriggerExternalAction = async function applyTriggerExternalAction(action) {
  await applyTriggerAction(action);
};

window.getTriggerPlanForRunner = function getTriggerPlanForRunner() {
  return triggerPlan;
};

window.startTriggerForRunner = async function startTriggerForRunner() {
  await openTriggerAssistant();
  await runTriggerPlanningFlow();
  if (triggerNodes.chat) triggerNodes.chat.hidden = true;
  return triggerPlan;
};

window.prepareTriggerStageForRunner = async function prepareTriggerStageForRunner(stage) {
  document.querySelector("[data-trigger-computer-use-surface]")?.classList.add("runner-capture");
  if (stage === "time" || stage === "patient" || stage === "time_patient" || stage === "guard") {
    if (triggerNodes.chat) triggerNodes.chat.hidden = true;
  }
  const pageContent = document.querySelector(".booking-page");
  if (!pageContent) return;
  if (stage === "time" || stage === "patient" || stage === "time_patient" || stage === "guard") {
    pageContent.scrollTo({ top: pageContent.scrollHeight, behavior: "instant" });
  } else {
    pageContent.scrollTo({ top: 0, behavior: "instant" });
  }
  await triggerDelay(120);
};

function switchTriggerView(name) {
  document.querySelectorAll("[data-view]").forEach((view) => {
    const active = view.dataset.view === name;
    view.hidden = !active;
    view.classList.toggle("active", active);
  });
}

function showTriggerPointer(target, danger = false) {
  const surface = document.querySelector("[data-trigger-computer-use-surface]")?.getBoundingClientRect();
  const box = target.getBoundingClientRect();
  if (!surface || !triggerNodes.pointer) return;
  triggerNodes.pointer.hidden = false;
  triggerNodes.pointer.style.left = `${box.left - surface.left + box.width / 2}px`;
  triggerNodes.pointer.style.top = `${box.top - surface.top + box.height / 2}px`;
  triggerNodes.pointer.classList.toggle("danger", danger);
}

function addTriggerMessage(role, text) {
  if (!triggerNodes.messages) return;
  const item = document.createElement("div");
  item.className = `chat-message ${role}`;
  item.textContent = text;
  triggerNodes.messages.appendChild(item);
  triggerNodes.messages.scrollTo({ top: triggerNodes.messages.scrollHeight, behavior: "smooth" });
}

function addTriggerTrace(title, detail, guard = false) {
  triggerTraceStep += 1;
  triggerNodes.trace?.classList.remove("empty");
  setTriggerStatus(guard ? "guard" : "running");
  const item = document.createElement("div");
  item.className = `trace-item${guard ? " guard" : ""}`;
  item.innerHTML = `
    <div class="dot">${triggerTraceStep}</div>
    <div>
      <div class="trace-title">${escapeTriggerHtml(title)}</div>
      <div class="trace-detail">${escapeTriggerHtml(detail || "")}</div>
    </div>
  `;
  triggerNodes.trace?.appendChild(item);
  triggerNodes.trace?.scrollTo({ top: triggerNodes.trace.scrollHeight, behavior: "smooth" });
}

function renderTriggerModelCalls(calls) {
  for (const call of calls) {
    triggerIoCount += 1;
    triggerNodes.io?.classList.remove("empty");
    triggerNodes.io?.querySelector(".empty-state")?.remove();
    if (triggerNodes.ioStatus) triggerNodes.ioStatus.textContent = `${triggerIoCount} call${triggerIoCount > 1 ? "s" : ""}`;
    const item = document.createElement("details");
    item.className = "model-call";
    item.open = true;
    const screenshot = call.screenshotDataUrl
      ? `<div class="model-call-block">
          <div class="model-call-label">1. 手机截图输入</div>
          <div class="screenshot-row">
            <img class="model-screenshot" src="${escapeTriggerHtml(call.screenshotDataUrl)}" alt="Gemma screenshot input" />
            <pre>${escapeTriggerHtml(JSON.stringify(call.rawInput || {}, null, 2))}</pre>
          </div>
        </div>`
      : `<div class="model-call-block">
          <div class="model-call-label">1. 脱敏输入 payload</div>
          <pre>${escapeTriggerHtml(JSON.stringify(call.rawInput || {}, null, 2))}</pre>
        </div>`;
    item.innerHTML = `
      <summary>
        <span>${escapeTriggerHtml(call.title || `Planner/Gemma ${triggerIoCount}`)}</span>
        <em>${escapeTriggerHtml(call.elapsedMs ? `${call.elapsedMs}ms` : "cached io")}</em>
      </summary>
      ${screenshot}
      <div class="model-call-block">
        <div class="model-call-label">2. Prompt</div>
        <pre>${escapeTriggerHtml(call.input || "")}</pre>
      </div>
      <div class="model-call-block">
        <div class="model-call-label">3. 原始输出</div>
        <pre>${escapeTriggerHtml(call.output || "")}</pre>
      </div>
      <div class="model-call-block">
        <div class="model-call-label">4. 解析结果</div>
        <pre>${escapeTriggerHtml(JSON.stringify(call.parsedActions || call.parsed || [], null, 2))}</pre>
      </div>
    `;
    triggerNodes.io?.appendChild(item);
    triggerNodes.io?.scrollTo({ top: triggerNodes.io.scrollHeight, behavior: "smooth" });
  }
}

function setTriggerStatus(status) {
  if (triggerNodes.runStatus) triggerNodes.runStatus.textContent = status;
  if (triggerNodes.chatStatus) {
    const labels = {
      planning: "Planner 回放中",
      preview: "本地预览完成",
      guarded: "确认前停住",
      guard: "安全守卫",
      running: "处理中",
    };
    triggerNodes.chatStatus.textContent = labels[status] || status;
  }
}

function localTriggerActionTitle(action) {
  const map = {
    tap: "本地执行：点击",
    choose: "本地执行：选择",
    fill: "本地执行：填写",
    wait: "本地执行：等待",
    guard: "安全守卫：停住",
  };
  return `${map[action.type] || "本地执行"} ${action.value || action.target || ""}`.trim();
}

function escapeTriggerHtml(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#039;",
  }[char]));
}
