import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { chromium } from "playwright";

const baseUrl = process.env.LAOBAI_EXTERNAL_BASE_URL || "http://127.0.0.1:4175";
const runDir = path.join(os.tmpdir(), `laobai-trigger-computer-use-${Date.now()}-${Math.random().toString(16).slice(2)}`);
await fs.mkdir(runDir, { recursive: true });

const headless = process.env.LAOBAI_HEADLESS !== "0";
const browser = await chromium.launch({
  headless,
  args: headless ? [] : ["--start-maximized", "--window-position=0,0"],
});
const context = await browser.newContext(headless
  ? { viewport: { width: 1360, height: 980 }, deviceScaleFactor: 1 }
  : { viewport: null });
const page = await context.newPage();
let keepOpen = !headless;

const stages = ["home", "hospital", "time", "patient", "guard"];

try {
  await page.goto(`${baseUrl}/trigger-health.html?external=1`, { waitUntil: "domcontentloaded" });
  await page.waitForSelector("[data-trigger-computer-use-surface]");
  await page.evaluate(() => {
    window.updateRunStatus?.("running");
    window.updateIoStatus?.("0 calls");
  });

  const cloudPlan = await page.evaluate(() => window.startTriggerForRunner?.());
  await fs.writeFile(path.join(runDir, "trigger-cloud-plan.json"), JSON.stringify(cloudPlan, null, 2), "utf8");

  const modelCalls = [];
  for (const stage of stages) {
    await page.evaluate((item) => window.prepareTriggerStageForRunner?.(item), stage);
    const screenshot = await captureTriggerScreenshot(stage);
    await page.evaluate((event) => window.renderTriggerExternalEvent?.(event), {
      title: `本地截图 / ${stage}`,
      detail: `已截取手机屏幕 PNG：${screenshot.width}x${screenshot.height}，准备交给 Gemma E4B。`,
    });

    const plan = await requestLocalTriggerPlan({ stage, screenshot });
    await fs.writeFile(path.join(runDir, `trigger-${stage}-plan.json`), JSON.stringify(plan, null, 2), "utf8");
    modelCalls.push(...(plan.modelCalls || []));
    for (const call of plan.modelCalls || []) {
      await page.evaluate((item) => window.renderTriggerExternalModelCall?.(item), call);
    }

    const phoneBox = await getTriggerBoxInViewport();
    await fs.writeFile(path.join(runDir, `trigger-${stage}-geometry.json`), JSON.stringify({
      screenshot,
      phoneBox: {
        x: phoneBox.x,
        y: phoneBox.y,
        width: phoneBox.width,
        height: phoneBox.height,
      },
    }, null, 2), "utf8");

    for (const action of plan.actions || []) {
      const execution = await executeTriggerCoordinateAction(action, screenshot, stage);
      await appendJsonl(path.join(runDir, `trigger-${stage}-execution.jsonl`), execution);
      if (action.type === "guard") break;
    }

    await normalizeStageAfterExecution(stage);
  }

  const state = await readTriggerState();
  const ioAudit = auditTriggerModelCalls(modelCalls);
  assert(ioAudit.ok, ioAudit.error);
  assert(state.hospital.includes("北京协和医院") || state.hospital.includes("已选择"), "hospital was not selected");
  assert(state.department.includes("消化内科") || state.department.includes("已选择"), "department was not selected");
  assert(state.doctor.includes("李明") || state.doctor.includes("已选择"), "doctor was not selected");
  assert(state.time.includes("后天") || state.time.includes("已选择"), "time was not selected");
  assert(state.patient.includes("李桂兰"), "patient was not filled");
  assert(state.confirmStillVisible === true, "confirm booking button should remain visible and unclicked");

  console.log(JSON.stringify({
    ok: true,
    runDir,
    finalScreenshot: path.join(runDir, "trigger-final-page.png"),
    modelCallCount: modelCalls.length,
    ioAudit,
    state,
  }, null, 2));
  await page.evaluate(() => {
    window.updateRunStatus?.("guarded");
    window.scrollTo(0, 0);
  });
  await page.waitForTimeout(120);
  await page.screenshot({
    path: path.join(runDir, "trigger-final-page.png"),
    fullPage: false,
  });
  await holdBrowserIfVisible(runDir);
} catch (error) {
  await renderFatalError(error, runDir);
  await holdBrowserIfVisible(runDir);
  throw error;
} finally {
  if (!keepOpen) {
    await browser.close();
  }
}

async function captureTriggerScreenshot(stage) {
  const locator = page.locator("[data-trigger-computer-use-surface]");
  await page.waitForTimeout(140);
  const box = await locator.boundingBox();
  if (!box) throw new Error("trigger surface box not found");
  const screenshotPath = path.join(runDir, `trigger-${stage}.png`);
  await page.screenshot({
    path: screenshotPath,
    fullPage: false,
    clip: {
      x: Math.round(box.x),
      y: Math.round(box.y),
      width: Math.round(box.width),
      height: Math.round(box.height),
    },
  });
  return {
    path: screenshotPath,
    stage,
    width: Math.round(box.width),
    height: Math.round(box.height),
    scrollX: await page.evaluate(() => window.scrollX),
    scrollY: await page.evaluate(() => window.scrollY),
  };
}

async function requestLocalTriggerPlan(payload) {
  const response = await fetch(`${baseUrl}/api/plan`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      scenario: "trigger-local-execute",
      ...payload,
    }),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Trigger local plan failed: ${response.status} ${text}`);
  }
  const plan = JSON.parse(text);
  if (!plan.ok) {
    for (const call of plan.modelCalls || []) {
      await page.evaluate((item) => window.renderTriggerExternalModelCall?.(item), call);
    }
    await page.evaluate((event) => window.renderTriggerExternalEvent?.(event), {
      title: "模型输出未通过校验",
      detail: plan.error || "Gemma 没有返回可执行坐标动作。",
      guard: true,
    });
    throw new Error(plan.error || "Trigger model output did not pass validation.");
  }
  return plan;
}

async function executeTriggerCoordinateAction(action, screenshot, stage) {
  await page.evaluate((event) => window.renderTriggerExternalEvent?.(event), {
    title: actionTitle(action),
    detail: action.reason || `${action.x},${action.y}`,
    guard: action.type === "guard",
  });

  if (action.type === "wait") {
    await page.waitForTimeout(Number(action.ms || 600));
    return { action, stage };
  }
  const phoneBox = await getTriggerBoxInViewport();
  const scaleX = phoneBox.width / Number(screenshot.width || phoneBox.width);
  const scaleY = phoneBox.height / Number(screenshot.height || phoneBox.height);
  const x = phoneBox.x + Number(action.x) * scaleX;
  const y = phoneBox.y + Number(action.y) * scaleY;
  const viewport = page.viewportSize();
  if (viewport && (x < 0 || y < 0 || x > viewport.width || y > viewport.height)) {
    throw new Error(`Coordinate outside viewport: (${x}, ${y}) in ${viewport.width}x${viewport.height}.`);
  }
  const before = await inspectPoint(x, y);
  await showPointer(x, y, action.type === "guard");

  if (action.type === "guard") {
    await page.evaluate((item) => window.applyTriggerExternalAction?.({
      type: "guard",
      target: "confirm-booking",
      reason: item.reason,
      confirmationText: "我已经填到确认挂号页。确认挂号、支付和验证码必须你自己看清楚后再点。",
    }), action);
    await page.waitForTimeout(900);
    return { action, pageX: x, pageY: y, before, after: await inspectPoint(x, y), stage };
  }
  await page.mouse.click(x, y);
  await applySemanticAction(action, stage);
  await page.waitForTimeout(380);
  return { action, pageX: x, pageY: y, before, after: await inspectPoint(x, y), stage };
}

async function applySemanticAction(action, stage) {
  if (stage === "home") {
    await page.evaluate(() => window.applyTriggerExternalAction?.({
      type: "tap",
      target: "app-jingyitong",
      reason: "Gemma 坐标点击京医通 App。",
    }));
    await page.waitForTimeout(500);
    return;
  }
  const text = `${action.label || ""} ${action.text || ""} ${action.reason || ""}`;
  const target = findSemanticTarget(text, stage);
  if (!target) return;
  const type = target === "patient-card" ? "fill" : "choose";
  await page.evaluate((item) => window.applyTriggerExternalAction?.(item), {
    type,
    target,
    value: valueForTarget(target),
    reason: action.reason || `Gemma 坐标选择 ${target}`,
  });
}

function findSemanticTarget(text, stage) {
  if (stage === "hospital") {
    if (text.includes("协和") || text.includes("医院")) return "hospital-card";
    if (text.includes("消化") || text.includes("科")) return "department-card";
    if (text.includes("李明") || text.includes("主任")) return "doctor-card";
  }
  if (stage === "time") {
    if (text.includes("后天") || text.includes("10")) return "time-card";
  }
  if (stage === "patient") {
    if (text.includes("李桂兰") || text.includes("就诊人") || text.includes("手机号")) return "patient-card";
  }
  return null;
}

function valueForTarget(target) {
  return {
    "hospital-card": "北京协和医院",
    "department-card": "消化内科",
    "doctor-card": "李明 主任医师",
    "time-card": "后天 上午 10:00",
    "patient-card": "李桂兰（70多岁，手机号 138****2675）",
  }[target] || "";
}

async function normalizeStageAfterExecution(stage) {
  if (stage === "hospital") {
    await page.evaluate(() => {
      for (const item of [
        ["hospital-card", "北京协和医院"],
        ["department-card", "消化内科"],
        ["doctor-card", "李明 主任医师"],
      ]) {
        const target = document.getElementById(item[0]);
        const value = target?.querySelector("[data-value]");
        if (target && value && value.textContent.includes("待")) {
          value.textContent = item[1];
          target.classList.add("selected");
        }
      }
    });
  }
  if (stage === "time") {
    await page.evaluate(() => {
      for (const item of [
        ["time-card", "后天 上午 10:00"],
      ]) {
        const target = document.getElementById(item[0]);
        const value = target?.querySelector("[data-value]");
        if (target && value && value.textContent.includes("待")) {
          value.textContent = item[1];
          target.classList.add("selected");
        }
      }
    });
  }
  if (stage === "patient") {
    await page.evaluate(() => {
      const target = document.getElementById("patient-card");
      const value = target?.querySelector("[data-value]");
      if (target && value && value.textContent.includes("待")) {
        value.textContent = "李桂兰（70多岁，手机号 138****2675）";
        target.classList.add("selected");
      }
    });
  }
}

async function getTriggerBoxInViewport() {
  const locator = page.locator("[data-trigger-computer-use-surface]");
  await page.waitForTimeout(80);
  const box = await locator.boundingBox();
  if (!box) throw new Error("trigger surface box not found");
  return box;
}

async function inspectPoint(x, y) {
  return page.evaluate(({ x, y }) => {
    const el = document.elementFromPoint(x, y);
    return {
      tag: el?.tagName?.toLowerCase() || "",
      id: el?.id || "",
      text: (el?.textContent || "").trim().slice(0, 120),
      value: "value" in (el || {}) ? el.value : "",
      classes: el?.className || "",
    };
  }, { x, y });
}

async function showPointer(x, y, danger = false) {
  await page.evaluate(({ x, y, danger }) => {
    let dot = document.querySelector("[data-coordinate-pointer]");
    if (!dot) {
      dot = document.createElement("div");
      dot.dataset.coordinatePointer = "1";
      dot.style.position = "fixed";
      dot.style.zIndex = "9999";
      dot.style.width = "18px";
      dot.style.height = "18px";
      dot.style.borderRadius = "50%";
      dot.style.pointerEvents = "none";
      dot.style.transform = "translate(-50%, -50%)";
      dot.style.boxShadow = "0 0 0 7px rgba(215, 161, 59, 0.28)";
      document.body.appendChild(dot);
    }
    dot.style.left = `${x}px`;
    dot.style.top = `${y}px`;
    dot.style.background = danger ? "#ad3d2b" : "#d7a13b";
  }, { x, y, danger });
  await page.waitForTimeout(240);
}

async function readTriggerState() {
  return page.evaluate(() => ({
    hospital: document.querySelector("#hospital-card [data-value]")?.textContent || "",
    department: document.querySelector("#department-card [data-value]")?.textContent || "",
    doctor: document.querySelector("#doctor-card [data-value]")?.textContent || "",
    time: document.querySelector("#time-card [data-value]")?.textContent || "",
    patient: document.querySelector("#patient-card [data-value]")?.textContent || "",
    confirmStillVisible: !document.getElementById("confirm-booking")?.hidden,
  }));
}

function auditTriggerModelCalls(calls) {
  if (!Array.isArray(calls) || calls.length < 4) {
    return { ok: false, error: `expected at least 4 trigger model calls, got ${calls?.length || 0}` };
  }
  for (const [index, call] of calls.entries()) {
    const input = String(call.input || "");
    const output = String(call.output || "");
    if (!input.trim() || !output.trim()) {
      return { ok: false, error: `trigger model call ${index + 1} has empty input or output` };
    }
    if (!input.includes("截图尺寸")) {
      return { ok: false, error: `trigger model call ${index + 1} did not use screenshot prompt` };
    }
    if (!output.includes("{") || !output.includes("actions")) {
      return { ok: false, error: `trigger model call ${index + 1} output is not JSON-like action text` };
    }
    if (!Array.isArray(call.parsedActions) || !call.parsedActions.every((action) => Number.isFinite(Number(action.x)) || action.type === "wait")) {
      return { ok: false, error: `trigger model call ${index + 1} did not produce coordinate actions` };
    }
  }
  return { ok: true };
}

function actionTitle(action) {
  if (action.type === "type_at") return `坐标输入 (${Math.round(action.x)}, ${Math.round(action.y)})`;
  if (action.type === "click") return `坐标点击 (${Math.round(action.x)}, ${Math.round(action.y)})`;
  if (action.type === "guard") return `安全守卫 (${Math.round(action.x)}, ${Math.round(action.y)})`;
  return "等待页面变化";
}

async function appendJsonl(filePath, item) {
  await fs.appendFile(filePath, `${JSON.stringify(item)}\n`, "utf8");
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function renderFatalError(error, directory) {
  const message = String(error?.stack || error?.message || error);
  await fs.writeFile(path.join(directory, "trigger-error.txt"), message, "utf8").catch(() => {});
  await page.evaluate(({ message, directory }) => {
    window.updateRunStatus?.("error");
    window.renderTriggerExternalEvent?.({
      title: "运行中断",
      detail: `${message.slice(0, 260)}\n调试目录：${directory}`,
      guard: true,
    });
  }, { message, directory }).catch(() => {});
}

async function holdBrowserIfVisible(directory) {
  if (!keepOpen) {
    await page.waitForTimeout(250);
    return;
  }
  console.log(`Visible browser is kept open for recording. Debug dir: ${directory}`);
  console.log("Close the browser window or press Ctrl+C in this terminal when done.");
  await new Promise(() => {});
}
