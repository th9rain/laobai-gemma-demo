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

  console.log(JSON.stringify({
    ok: true,
    modelCallCount: modelCalls.length,
    modelCalls: modelCalls.map((call) => ({
      title: call.title,
      inputBytes: Buffer.byteLength(call.input || "", "utf8"),
      outputBytes: Buffer.byteLength(call.output || "", "utf8"),
    })),
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
    await page.locator(selector).fill(action.value || "");
    return;
  }
  if (action.type === "select") {
    await page.locator(selector).selectOption({ label: action.value || "" });
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
