import { chromium } from "playwright";

const baseUrl = process.env.LAOBAI_EXTERNAL_BASE_URL || "http://127.0.0.1:4173";

const browser = await chromium.launch({
  headless: process.env.LAOBAI_HEADLESS !== "0",
});
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

try {
  await page.goto(`${baseUrl}/always-on-form.html?external=1`, { waitUntil: "domcontentloaded" });
  const modelCalls = [];

  for (let pass = 1; pass <= 3; pass += 1) {
    const observation = await page.evaluate(() => window.observeAlwaysOnForm());
    const plan = await requestPlan(observation);
    modelCalls.push(...(plan.modelCalls || []));
    for (const call of plan.modelCalls || []) {
      await page.evaluate((item) => window.renderExternalModelCall(item), call);
    }

    let shouldContinue = false;
    for (const action of plan.actions || []) {
      await executeAction(action);
      if (action.type === "click" && action.target === "next-button") {
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

  const state = await page.evaluate(() => ({
    name: document.getElementById("name")?.value,
    age: document.getElementById("age")?.value,
    phone: document.getElementById("phone")?.value,
    area: document.getElementById("area")?.value,
    contact: document.getElementById("contact")?.value,
    course: document.getElementById("course")?.value,
    learningGoal: document.getElementById("learning-goal")?.value,
    submitStillVisible: !document.getElementById("submit-button")?.hidden,
  }));
  const ioAudit = auditModelCalls(modelCalls);
  assert(ioAudit.ok, ioAudit.error);
  assert(state.name === "李桂兰", "name was not filled");
  assert(state.area === "北京市朝阳区望京街道", "page 2 area was not filled");
  assert(state.learningGoal.includes("识别诈骗短信"), "learning goal was not filled");

  console.log(JSON.stringify({
    ok: true,
    modelCallCount: modelCalls.length,
    modelCalls: modelCalls.map((call) => ({
      title: call.title,
      inputBytes: Buffer.byteLength(call.input || "", "utf8"),
      outputBytes: Buffer.byteLength(call.output || "", "utf8"),
      inputHasObservation: String(call.input || "").includes("北京市朝阳区社区智慧课堂报名表"),
      outputHasChinese: /[\u4e00-\u9fff]/.test(String(call.output || "")),
    })),
    ioAudit,
    state,
  }, null, 2));
} finally {
  await browser.close();
}

async function requestPlan(observation) {
  const response = await fetch(`${baseUrl}/api/plan`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      scenario: "always-on-form",
      observation,
    }),
  });
  if (!response.ok) {
    throw new Error(`Plan request failed: ${response.status}`);
  }
  return response.json();
}

async function executeAction(action) {
  const selector = `#${cssEscape(action.target)}`;
  if (action.type === "type") {
    const tagName = await page.locator(selector).evaluate((element) => element.tagName.toLowerCase());
    if (tagName === "select") {
      await page.locator(selector).selectOption({ label: action.value || "" });
    } else {
      await page.locator(selector).fill(action.value || "");
    }
    return;
  }
  if (action.type === "select") {
    const tagName = await page.locator(selector).evaluate((element) => element.tagName.toLowerCase());
    if (tagName === "select") {
      await page.locator(selector).selectOption({ label: action.value || "" });
    } else {
      await page.locator(selector).fill(action.value || "");
    }
    return;
  }
  if (action.type === "click") {
    await page.locator(selector).click();
    await page.waitForTimeout(400);
    return;
  }
  if (action.type === "guard") {
    return;
  }
  if (action.type === "wait") {
    await page.waitForTimeout(Number(action.value || 700));
  }
}

function cssEscape(value) {
  return String(value).replace(/([ #;?%&,.+*~':"!^$[\]()=>|/@])/g, "\\$1");
}

function auditModelCalls(calls) {
  if (!Array.isArray(calls) || calls.length !== 2) {
    return { ok: false, error: `expected exactly 2 model calls, got ${calls?.length || 0}` };
  }
  for (const [index, call] of calls.entries()) {
    const input = String(call.input || "");
    const output = String(call.output || "");
    const combined = `${call.title || ""}\n${input}\n${output}`;
    if (!input.trim() || !output.trim()) {
      return { ok: false, error: `model call ${index + 1} has empty input or output` };
    }
    if (hasMojibake(combined)) {
      return { ok: false, error: `model call ${index + 1} contains mojibake` };
    }
    if (!input.includes(`Current page number: ${index + 1}`)) {
      return { ok: false, error: `model call ${index + 1} is missing current page marker` };
    }
    if (!input.includes("北京市朝阳区社区智慧课堂报名表")) {
      return { ok: false, error: `model call ${index + 1} is missing Chinese screen observation` };
    }
    if (!output.includes("{") || !output.includes("actions") || !/[\u4e00-\u9fff]/.test(output)) {
      return { ok: false, error: `model call ${index + 1} output is not complete Chinese JSON-like text` };
    }
  }
  return { ok: true };
}

function hasMojibake(text) {
  return /�|鑰|濉|绔|鍖|涓|缁|妯|璇|鐢|�/.test(String(text || ""));
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}
