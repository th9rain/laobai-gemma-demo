import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const scenario = process.argv[2] || "all";
const headed = process.env.LAOBAI_HEADLESS === "0";
const viewport = { width: 390, height: 844 };
const stamp = new Date().toISOString().replace(/[:.]/g, "-");
const runRoot = path.join(repoRoot, "workflow-runs", stamp);
await fs.mkdir(runRoot, { recursive: true });

const browser = await chromium.launch({ headless: !headed });
const context = await browser.newContext({ viewport, deviceScaleFactor: 1, isMobile: true, hasTouch: true });

try {
  const results = [];
  if (scenario === "all" || scenario === "always-on") results.push(await runAlwaysOn());
  if (scenario === "all" || scenario === "trigger") results.push(await runTrigger());
  const report = { ok: results.every((item) => item.ok), protocol: "laobai-mobile-actions/v1", viewport, runRoot, results };
  await fs.writeFile(path.join(runRoot, "report.json"), JSON.stringify(report, null, 2), "utf8");
  console.log(JSON.stringify(report, null, 2));
  if (!report.ok) process.exitCode = 1;
} finally {
  if (headed) await new Promise((resolve) => setTimeout(resolve, 2500));
  await browser.close();
}

async function runAlwaysOn() {
  const page = await context.newPage();
  const dir = path.join(runRoot, "always-on");
  await fs.mkdir(dir, { recursive: true });
  const log = [];
  await page.goto(fileUrl("web/always-on-form.html"), { waitUntil: "domcontentloaded" });
  await shot(page, dir, "00-open");
  await modelShot(page, dir, "00-open-model-grid");

  // This replay uses the same visible interactions an Android accessibility/QPython
  // executor will use later. Locators are only the desktop test adapter.
  await fill(page, log, "#name", "李桂兰");
  await tap(page, log, "input[name=gender][value=女]");
  await fill(page, log, "#birth", "1953-03");
  await fill(page, log, "#idcard", "110108195303086526");
  await fill(page, log, "#phone", "13812342675");
  await select(page, log, "#education", "高中/中专");
  await scrollTo(page, log, "#district");
  await shot(page, dir, "01-after-scroll");
  await select(page, log, "#district", "海淀区");
  await fill(page, log, "#address", "中关村街道科育社区18号楼");
  await tap(page, log, "input[name=health][value=良好]");
  await tap(page, log, "input[name=disease][value=无]");
  await fill(page, log, "#diseaseDetail", "无");
  await scrollTo(page, log, "#emergencyName");
  await shot(page, dir, "02-emergency-contact");
  await fill(page, log, "#emergencyName", "王敏");
  await select(page, log, "#emergencyRelation", "子女");
  await fill(page, log, "#emergencyPhone", "13912345678");
  await tap(page, log, "#nextBtn", "下一步");
  await page.waitForTimeout(250);
  await shot(page, dir, "03-course");
  await tap(page, log, "input[name=course][value=智能手机基础班]");
  await tap(page, log, "input[name=timeSlot][value=周三上午]");
  await scrollTo(page, log, "#nextBtn");
  await tap(page, log, "#nextBtn", "下一步");
  await page.waitForTimeout(250);
  await shot(page, dir, "04-review-guard");

  const state = await page.evaluate(() => ({
    page: document.querySelector(".page.active")?.id,
    name: document.querySelector("#name")?.value,
    course: document.querySelector("[name=course]:checked")?.value,
    time: document.querySelector("[name=timeSlot]:checked")?.value,
    nextText: document.querySelector("#nextBtn")?.textContent?.trim(),
    successVisible: document.querySelector("#successModal")?.classList.contains("show"),
  }));
  const guard = { type: "guard", label: "提交报名", reason: "最终提交需要用户确认" };
  log.push(guard);
  await writeLog(dir, log);
  await page.close();
  return {
    scenario: "always-on",
    ok: state.page === "page3" && state.name === "李桂兰" && state.nextText === "提交报名" && !state.successVisible,
    stoppedBefore: "提交报名",
    state,
  };
}

async function runTrigger() {
  const page = await context.newPage();
  const dir = path.join(runRoot, "trigger");
  await fs.mkdir(dir, { recursive: true });
  const log = [];
  const replay = {
    source: "historical-cloud-plan-replay",
    model: "Gemma 32B dense (recorded fixture; no live call)",
    appointment: { hospital: "北京协和医院", department: "消化内科门诊", doctor: "李明 主任医师", date: "后天", time: "上午 10:00", patient: "李桂兰" },
    stopBefore: ["确认挂号", "支付", "验证码"],
  };
  await fs.writeFile(path.join(dir, "planner-replay.json"), JSON.stringify(replay, null, 2), "utf8");
  await page.goto(fileUrl("web/trigger-health.html"), { waitUntil: "domcontentloaded" });
  await shot(page, dir, "00-home");
  await modelShot(page, dir, "00-home-model-grid");
  for (const [selector, name] of [
    ["#appointmentEntry", "预约挂号"],
    ["#hospital-card", "北京协和医院"],
    ["#department-card", "消化内科门诊"],
    ["#doctor-card", "李明 主任医师"],
    ["#time-card", "后天 10:00"],
  ]) {
    await tap(page, log, selector, name);
    await page.waitForTimeout(name.includes("10:00") ? 600 : 180);
    await shot(page, dir, `${String(log.length).padStart(2, "0")}-${safeName(name)}`);
  }
  const guard = await elementAction(page, "#confirm-booking", "guard", { label: "确认挂号", reason: "确认挂号、支付和验证码必须由用户操作" });
  log.push(guard);
  await shot(page, dir, "06-order-guard");
  const state = await page.evaluate(() => ({
    page: document.querySelector(".page.active")?.id,
    patient: document.querySelector("#patient-card strong")?.textContent?.trim(),
    afterTomorrow: document.querySelector("#dateAfterTomorrow")?.textContent?.trim(),
    appointmentDate: document.querySelector("#appointmentDate")?.textContent?.trim(),
    confirmText: document.querySelector("#confirm-booking")?.textContent?.trim(),
    successVisible: document.querySelector("#successModal")?.classList.contains("show"),
  }));
  await writeLog(dir, log);
  await page.close();
  return {
    scenario: "trigger",
    ok: state.page === "orderPage" && state.patient === "李桂兰" && state.afterTomorrow && state.appointmentDate?.startsWith(state.afterTomorrow) && state.confirmText === "确认挂号" && !state.successVisible,
    planner: replay.source,
    stoppedBefore: "确认挂号",
    state,
  };
}

async function tap(page, log, selector, label = "") {
  const action = await elementAction(page, selector, "tap", { label });
  log.push(action);
  await page.locator(selector).click();
}

async function fill(page, log, selector, value) {
  const action = await elementAction(page, selector, "type_at", { text: value });
  log.push(action);
  await page.locator(selector).fill(value);
}

async function select(page, log, selector, value) {
  const action = await elementAction(page, selector, "select", { value });
  log.push(action);
  await page.locator(selector).selectOption({ label: value });
}

async function scrollTo(page, log, selector) {
  const before = await page.evaluate(() => scrollY);
  await page.locator(selector).scrollIntoViewIfNeeded();
  const after = await page.evaluate(() => scrollY);
  log.push({ type: "scroll", x1: 195, y1: 690, x2: 195, y2: 250, durationMs: 500, scrollYBefore: before, scrollYAfter: after, reason: `显示 ${selector}` });
}

async function elementAction(page, selector, type, extra = {}) {
  const box = await page.locator(selector).boundingBox();
  if (!box) throw new Error(`Element is not visible: ${selector}`);
  return { type, x: Math.round(box.x + box.width / 2), y: Math.round(box.y + box.height / 2), ...extra };
}

async function shot(page, dir, name) {
  await page.screenshot({ path: path.join(dir, `${name}.png`), fullPage: false });
}

async function modelShot(page, dir, name) {
  await page.evaluate(() => {
    const layer = document.createElement("div");
    layer.id = "__laobai_coordinate_grid";
    layer.style.cssText = "position:fixed;inset:0;z-index:2147483647;pointer-events:none;background-image:linear-gradient(rgba(210,40,40,.22) 1px,transparent 1px),linear-gradient(90deg,rgba(210,40,40,.22) 1px,transparent 1px);background-size:50px 50px;color:#b51f1f;font:9px sans-serif";
    for (let y = 0; y < innerHeight; y += 100) {
      const label = document.createElement("span");
      label.textContent = `y=${y}`;
      label.style.cssText = `position:absolute;left:2px;top:${y + 2}px;background:rgba(255,255,255,.8)`;
      layer.appendChild(label);
    }
    for (let x = 100; x < innerWidth; x += 100) {
      const label = document.createElement("span");
      label.textContent = `x=${x}`;
      label.style.cssText = `position:absolute;left:${x + 2}px;top:2px;background:rgba(255,255,255,.8)`;
      layer.appendChild(label);
    }
    document.body.appendChild(layer);
  });
  await shot(page, dir, name);
  await page.evaluate(() => document.querySelector("#__laobai_coordinate_grid")?.remove());
}

async function writeLog(dir, log) {
  await fs.writeFile(path.join(dir, "actions.json"), JSON.stringify({ protocol: "laobai-mobile-actions/v1", viewport, actions: log }, null, 2), "utf8");
}

function fileUrl(relative) {
  return new URL(relative.replaceAll("\\", "/"), `file:///${repoRoot.replaceAll("\\", "/")}/`).href;
}

function safeName(value) {
  return value.replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-|-$/g, "");
}
