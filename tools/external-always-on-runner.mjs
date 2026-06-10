import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { chromium } from "playwright";

const baseUrl = process.env.LAOBAI_EXTERNAL_BASE_URL || "http://127.0.0.1:4173";
const runDir = path.join(os.tmpdir(), `laobai-computer-use-${Date.now()}-${Math.random().toString(16).slice(2)}`);
await fs.mkdir(runDir, { recursive: true });

const browser = await chromium.launch({
  headless: process.env.LAOBAI_HEADLESS !== "0",
});
const page = await browser.newPage({ viewport: { width: 1280, height: 1000 }, deviceScaleFactor: 1 });
let keepOpen = process.env.LAOBAI_HEADLESS === "0";

try {
  await page.goto(`${baseUrl}/always-on-form.html?external=1`, { waitUntil: "domcontentloaded" });
  await page.waitForSelector("[data-computer-use-surface]");
  await page.evaluate(() => {
    window.drawCoordinateGrid?.();
    window.updateRunStatus?.("running");
    window.updateIoStatus?.("0 calls");
  });

  const modelCalls = [];
  for (let pass = 1; pass <= 3; pass += 1) {
    const currentPage = await getCurrentFormPage();
    const screenshot = await capturePhoneScreenshot(currentPage, pass);
    await page.evaluate((event) => window.renderExternalEvent?.(event), {
      title: `第 ${pass} 次截图`,
      detail: `已截取手机屏幕 PNG：${screenshot.width}x${screenshot.height}，准备交给本地 Gemma。`,
    });

    const plan = await requestPlan({
      page: currentPage,
      screenshot,
      task: currentPage === 1
        ? "填写第一页基础信息并点击下一页"
        : "填写第二页课程信息并停在提交前",
    });
    await fs.writeFile(path.join(runDir, `always-on-pass-${pass}-plan.json`), JSON.stringify(plan, null, 2), "utf8");
    modelCalls.push(...(plan.modelCalls || []));
    for (const call of plan.modelCalls || []) {
      await page.evaluate((item) => window.renderExternalModelCall?.(item), call);
    }

    const phoneBox = await getPhoneBoxInViewport();
    await fs.writeFile(path.join(runDir, `always-on-pass-${pass}-geometry.json`), JSON.stringify({
      screenshot,
      phoneBox: {
        x: phoneBox.x,
        y: phoneBox.y,
        width: phoneBox.width,
        height: phoneBox.height,
      },
    }, null, 2), "utf8");
    let shouldContinue = false;
    for (const action of plan.actions || []) {
      await resetScrollForCoordinateExecution(screenshot);
      const execution = await executeCoordinateAction(action, screenshot);
      await appendJsonl(path.join(runDir, `always-on-pass-${pass}-execution.jsonl`), execution);
      if (action.type === "click" && String(action.label || "").includes("下一页")) {
        await page.waitForTimeout(700);
        const afterClickPage = await getCurrentFormPage();
        if (afterClickPage <= currentPage) {
          await page.evaluate((event) => window.renderExternalEvent?.(event), {
            title: "翻页未发生",
            detail: "Gemma 返回了“下一页”点击坐标，但实际页面仍停留在第 1 页。",
            guard: true,
          });
          shouldContinue = false;
          break;
        }
        shouldContinue = true;
        break;
      }
      if (action.type === "guard") {
        shouldContinue = false;
        break;
      }
    }
    if (!shouldContinue) break;
  }

  const state = await readFormState();
  const ioAudit = auditModelCalls(modelCalls);
  assert(ioAudit.ok, ioAudit.error);
  assert(state.name === "李桂兰", "name was not filled");
  assert(state.phone === "138****2675", "phone was not filled");
  assert(state.area === "北京市朝阳区望京街道", "page 2 area was not filled");
  assert(state.learningGoal.includes("识别诈骗短信"), "learning goal was not filled");
  assert(state.submitStillVisible === true, "submit button should remain visible and unclicked");

  console.log(JSON.stringify({
    ok: true,
    runDir,
    finalScreenshot: path.join(runDir, "always-on-final-page.png"),
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
    path: path.join(runDir, "always-on-final-page.png"),
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

async function capturePhoneScreenshot(currentPage, pass) {
  const locator = page.locator("[data-computer-use-surface]");
  await page.waitForTimeout(120);
  const box = await locator.boundingBox();
  if (!box) throw new Error("phone screen box not found");
  const screenshotPath = path.join(runDir, `always-on-pass-${pass}-page-${currentPage}.png`);
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
    page: currentPage,
    width: Math.round(box.width),
    height: Math.round(box.height),
    scrollX: await page.evaluate(() => window.scrollX),
    scrollY: await page.evaluate(() => window.scrollY),
  };
}

async function requestPlan(payload) {
  const response = await fetch(`${baseUrl}/api/plan`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      scenario: "always-on-form",
      ...payload,
    }),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Plan request failed: ${response.status} ${text}`);
  }
  const plan = JSON.parse(text);
  if (!plan.ok) {
    for (const call of plan.modelCalls || []) {
      await page.evaluate((item) => window.renderExternalModelCall?.(item), call);
    }
    await page.evaluate((event) => window.renderExternalEvent?.(event), {
      title: "模型输出未通过校验",
      detail: plan.error || "Gemma 没有返回可执行坐标动作。",
      guard: true,
    });
    throw new Error(plan.error || "Always-on model output did not pass validation.");
  }
  return plan;
}

async function executeCoordinateAction(action, screenshot) {
  await page.evaluate((event) => window.renderExternalEvent?.(event), {
    title: actionTitle(action),
    detail: action.reason || `${action.x},${action.y}`,
    guard: action.type === "guard",
  });

  if (action.type === "wait") {
    await page.waitForTimeout(Number(action.ms || 600));
    return;
  }
  const phoneBox = await getPhoneBoxInViewport();
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
    await page.waitForTimeout(900);
    return { action, pageX: x, pageY: y, before, after: await inspectPoint(x, y), currentPage: await getCurrentFormPage() };
  }
  if (action.type === "click") {
    await page.mouse.click(x, y);
    await page.waitForTimeout(350);
    return { action, pageX: x, pageY: y, before, after: await inspectPoint(x, y), currentPage: await getCurrentFormPage() };
  }
  if (action.type === "type_at") {
    await page.mouse.click(x, y);
    const activeInfo = await page.evaluate(() => {
      const el = document.activeElement;
      return {
        tag: el?.tagName?.toLowerCase() || "",
        type: el?.getAttribute?.("type") || "",
      };
    });
    if (activeInfo.tag === "select") {
      await page.keyboard.press("Enter").catch(() => {});
      await selectVisibleOption(action.text);
    } else {
      await page.keyboard.press(process.platform === "darwin" ? "Meta+A" : "Control+A");
      await page.keyboard.type(action.text || "", { delay: 18 });
    }
    await page.waitForTimeout(350);
    return { action, pageX: x, pageY: y, before, after: await inspectPoint(x, y), currentPage: await getCurrentFormPage() };
  }
  return { action, pageX: x, pageY: y, before, after: await inspectPoint(x, y), currentPage: await getCurrentFormPage() };
}

async function resetScrollForCoordinateExecution(screenshot = {}) {
  await page.evaluate(({ scrollX = 0, scrollY = 0 }) => {
    window.scrollTo(scrollX, scrollY);
    const active = document.activeElement;
    if (active && typeof active.blur === "function") active.blur();
  }, {
    scrollX: Number(screenshot.scrollX || 0),
    scrollY: Number(screenshot.scrollY || 0),
  });
}

async function inspectPoint(x, y) {
  return page.evaluate(({ x, y }) => {
    const el = document.elementFromPoint(x, y);
    return {
      tag: el?.tagName?.toLowerCase() || "",
      id: el?.id || "",
      text: (el?.textContent || "").trim().slice(0, 80),
      value: "value" in (el || {}) ? el.value : "",
      classes: el?.className || "",
    };
  }, { x, y });
}

async function appendJsonl(filePath, item) {
  await fs.appendFile(filePath, `${JSON.stringify(item)}\n`, "utf8");
}

async function selectVisibleOption(text) {
  const selected = await page.evaluate((value) => {
    const el = document.activeElement;
    if (!el || el.tagName !== "SELECT") return false;
    const option = Array.from(el.options).find((item) => item.textContent.trim() === value || item.value === value);
    if (!option) return false;
    el.value = option.value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  }, text);
  if (!selected) {
    await page.keyboard.type(text || "", { delay: 18 });
  }
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

async function getPhoneBox() {
  const box = await page.locator("[data-computer-use-surface]").boundingBox();
  if (!box) throw new Error("computer-use surface box not found");
  return box;
}

async function getPhoneBoxInViewport() {
  const locator = page.locator("[data-computer-use-surface]");
  await page.waitForTimeout(80);
  const box = await locator.boundingBox();
  if (!box) throw new Error("computer-use surface box not found");
  return box;
}

async function getCurrentFormPage() {
  return page.evaluate(() => document.getElementById("form-page-2")?.hidden ? 1 : 2);
}

async function readFormState() {
  return page.evaluate(() => ({
    name: document.getElementById("name")?.value,
    age: document.getElementById("age")?.value,
    phone: document.getElementById("phone")?.value,
    area: document.getElementById("area")?.value,
    contact: document.getElementById("contact")?.value,
    course: document.getElementById("course")?.value,
    learningGoal: document.getElementById("learning-goal")?.value,
    submitStillVisible: !document.getElementById("submit-button")?.hidden,
  }));
}

function auditModelCalls(calls) {
  if (!Array.isArray(calls) || calls.length < 2) {
    return { ok: false, error: `expected at least 2 model calls, got ${calls?.length || 0}` };
  }
  if (calls.length > 3) {
    return { ok: false, error: `expected no more than 3 model calls, got ${calls.length}` };
  }
  for (const [index, call] of calls.entries()) {
    const input = String(call.input || "");
    const output = String(call.output || "");
    if (!input.trim() || !output.trim()) {
      return { ok: false, error: `model call ${index + 1} has empty input or output` };
    }
    if (!input.includes("截图尺寸")) {
      return { ok: false, error: `model call ${index + 1} did not use screenshot prompt` };
    }
    if (!output.includes("{") || !output.includes("actions")) {
      return { ok: false, error: `model call ${index + 1} output is not JSON-like action text` };
    }
    if (!Array.isArray(call.parsedActions) || !call.parsedActions.every((action) => Number.isFinite(Number(action.x)) || action.type === "wait")) {
      return { ok: false, error: `model call ${index + 1} did not produce coordinate actions` };
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

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function renderFatalError(error, directory) {
  const message = String(error?.stack || error?.message || error);
  await fs.writeFile(path.join(directory, "always-on-error.txt"), message, "utf8").catch(() => {});
  await page.evaluate(({ message, directory }) => {
    window.updateRunStatus?.("error");
    window.renderExternalEvent?.({
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
