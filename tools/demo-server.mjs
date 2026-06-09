import http from "node:http";
import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, "..");
const webDir = path.join(rootDir, "web");

const args = new Map();
for (let i = 2; i < process.argv.length; i += 1) {
  const arg = process.argv[i];
  if (arg.startsWith("--")) {
    args.set(arg.slice(2), process.argv[i + 1]?.startsWith("--") ? true : process.argv[++i] ?? true);
  }
}

const host = String(args.get("host") || process.env.LAOBAI_DEMO_HOST || "localhost");
const port = Number(args.get("port") || process.env.LAOBAI_DEMO_PORT || 4173);

const demoMap = {
  "always-on": "/always-on-form.html?autostart=1",
  trigger: "/trigger-health.html?autostart=1",
  form: "/always-on-form.html?autostart=1",
  health: "/trigger-health.html?autostart=1",
};

const config = await loadConfig();

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || "/", `http://localhost:${port}`);
    if (req.method === "GET" && url.pathname === "/") {
      return serveFile(res, path.join(webDir, "index.html"));
    }
    if (req.method === "GET" && url.pathname === "/api/public-config") {
      return sendJson(res, {
        plannerLabel: config.publicPlannerLabel,
        edgeLabel: config.publicEdgeLabel,
        plannerOnline: Boolean(config.plannerApiKey && config.plannerEndpoint),
      });
    }
    if (req.method === "POST" && url.pathname === "/api/plan") {
      const body = await readJson(req);
      const plan = await plannerRequest(body);
      return sendJson(res, plan);
    }
    if (req.method === "GET") {
      const safePath = safeJoin(webDir, url.pathname === "/" ? "index.html" : url.pathname);
      return serveFile(res, safePath);
    }
    res.writeHead(405);
    res.end("Method Not Allowed");
  } catch (error) {
    sendJson(res, { ok: false, error: String(error?.message || error) }, 500);
  }
});

server.listen(port, host, () => {
  const demo = String(args.get("demo") || "");
  const target = `http://${host}:${port}${demoMap[demo] || "/"}`;
  console.log(`LaoBai web demo server running: ${target}`);
  if (args.get("no-open") !== true) {
    openBrowser(target);
  }
});

async function loadConfig() {
  const defaults = {
    plannerEndpoint: process.env.LAOBAI_PLANNER_ENDPOINT || "",
    plannerModel: process.env.LAOBAI_PLANNER_MODEL || "cloud-planner-model",
    plannerApiKey: process.env.LAOBAI_PLANNER_API_KEY || "",
    edgeEndpoint: process.env.LAOBAI_EDGE_ENDPOINT || "",
    edgeModel: process.env.LAOBAI_EDGE_MODEL || "",
    edgeApiKey: process.env.LAOBAI_EDGE_API_KEY || "",
    localGemmaEnabled: process.env.LAOBAI_LOCAL_GEMMA_ENABLED === "1",
    localGemmaModelPath: process.env.LAOBAI_LOCAL_GEMMA_MODEL_PATH || "models/gemma-4-E4B-it.litertlm",
    localGemmaPython: process.env.LAOBAI_LOCAL_GEMMA_PYTHON || ".venv/Scripts/python.exe",
  };
  const publicLabels = {
    publicPlannerLabel: process.env.LAOBAI_PUBLIC_PLANNER_LABEL || "Gemma 4 30B Cloud Planner",
    publicEdgeLabel: process.env.LAOBAI_PUBLIC_EDGE_LABEL || "Gemma 4B Computer-Use",
  };
  const localPath = path.join(rootDir, "config.local.json");
  let merged = defaults;
  try {
    if (process.env.LAOBAI_SKIP_LOCAL_CONFIG !== "1") {
      const text = (await fs.readFile(localPath, "utf8")).replace(/^\uFEFF/, "");
      const local = JSON.parse(text);
      merged = { ...defaults, ...local };
    }
  } catch {
    merged = defaults;
  }
  return {
    ...merged,
    ...publicLabels,
    edgeEndpoint: merged.edgeEndpoint || merged.plannerEndpoint,
    edgeModel: effectiveEdgeModel(merged),
    edgeApiKey: merged.edgeApiKey || merged.plannerApiKey,
  };
}

function effectiveEdgeModel(configData) {
  const value = String(configData.edgeModel || "").trim();
  if (value && value !== "your-edge-model") return value;
  return String(configData.plannerModel || "").trim();
}

async function plannerRequest(payload) {
  const fallback = fallbackPlan(payload);
  const cloudPlan = await cloudPlannerRequest(payload, fallback);
  return edgeComputerUseRequest(payload, cloudPlan);
}

async function cloudPlannerRequest(payload, fallback) {
  if (!config.plannerApiKey || !config.plannerEndpoint) {
    return withRuntime({
      ...fallback,
      source: "edge-policy",
      note: "Local policy fallback. Configure config.local.json to enable cloud planner.",
    }, {
      plannerConfigured: false,
      plannerHandoff: false,
      plannerStatus: "local-policy",
    });
  }

  const prompt = buildPlannerPrompt(payload, fallback);
  const plan = await invokePlanningModel({
    endpoint: config.plannerEndpoint,
    model: config.plannerModel,
    apiKey: config.plannerApiKey,
    prompt,
    fallback,
    timeoutMs: Number(process.env.LAOBAI_PLANNER_TIMEOUT_MS || 20000),
    successSource: "cloud-planner",
    fallbackSource: "edge-policy",
    unavailableNote: "Planner handoff unavailable; safe edge policy used.",
  });
  return withRuntime(plan, {
    plannerConfigured: true,
    plannerHandoff: plan.source === "cloud-planner",
    plannerStatus: plan.source === "cloud-planner" ? "handoff-ok" : "safe-fallback",
  });
}

async function edgeComputerUseRequest(payload, cloudPlan) {
  if (config.localGemmaEnabled) {
    const localPlan = await localGemmaComputerUseRequest(payload, cloudPlan);
    if (localPlan.source === "local-gemma-computer-use") {
      return localPlan;
    }
  }

  if (!config.edgeApiKey || !config.edgeEndpoint || !config.edgeModel) {
    return withRuntime({
      ...cloudPlan,
      note: "Browser executor used for GUI actions after planner validation.",
    }, {
      edgeConfigured: false,
      edgeHandoff: false,
      edgeStatus: "browser-executor",
    });
  }

  const prompt = buildEdgePrompt(payload, cloudPlan);
  const plan = await invokePlanningModel({
    endpoint: config.edgeEndpoint,
    model: config.edgeModel,
    apiKey: config.edgeApiKey,
    prompt,
    fallback: cloudPlan,
    timeoutMs: Number(process.env.LAOBAI_EDGE_TIMEOUT_MS || 20000),
    successSource: "edge-computer-use",
    fallbackSource: cloudPlan.source || "edge-policy",
    unavailableNote: "Edge computer-use handoff unavailable; safe action policy used.",
  });
  return withRuntime(plan, {
    edgeConfigured: true,
    edgeHandoff: plan.source === "edge-computer-use",
    edgeStatus: plan.source === "edge-computer-use" ? "handoff-ok" : "safe-fallback",
  });
}

async function localGemmaComputerUseRequest(payload, cloudPlan) {
  const modelPath = path.resolve(rootDir, config.localGemmaModelPath || "");
  const pythonPath = path.resolve(rootDir, config.localGemmaPython || "");
  try {
    await fs.access(modelPath);
    await fs.access(pythonPath);
  } catch {
    return withRuntime({
      ...cloudPlan,
      note: "Local Gemma model or Python runner is not ready.",
    }, {
      localGemmaConfigured: true,
      localGemmaHandoff: false,
      localGemmaStatus: "not-ready",
    });
  }

  const prompt = buildLocalGemmaPrompt(payload, cloudPlan);
  const promptPath = path.join(os.tmpdir(), `laobai-gemma-prompt-${Date.now()}-${Math.random().toString(16).slice(2)}.txt`);
  await fs.writeFile(promptPath, prompt, "utf8");
  try {
    const result = await runLocalGemmaProcess({
      pythonPath,
      modelPath,
      promptPath,
      timeoutMs: Number(process.env.LAOBAI_LOCAL_GEMMA_TIMEOUT_MS || 180000),
    });
    if (!result.ok || !result.plan) {
      return withRuntime({
        ...cloudPlan,
        note: "Local Gemma returned non-JSON output; safe action policy used.",
      }, {
        localGemmaConfigured: true,
        localGemmaHandoff: false,
        localGemmaStatus: "non-json",
      });
    }
    const normalized = normalizePlan(JSON.stringify(result.plan), cloudPlan, "local-gemma-computer-use");
    return withRuntime(normalized, {
      localGemmaConfigured: true,
      localGemmaHandoff: true,
      localGemmaStatus: "handoff-ok",
      edgeConfigured: true,
      edgeHandoff: true,
      edgeStatus: "local-gemma-handoff",
    });
  } catch (error) {
    debugModelCall("local-gemma-computer-use", {
      errorName: String(error?.name || "Error"),
      errorMessage: sanitizeDebugMessage(error?.message || error),
    });
    return withRuntime({
      ...cloudPlan,
      note: "Local Gemma handoff failed; safe action policy used.",
    }, {
      localGemmaConfigured: true,
      localGemmaHandoff: false,
      localGemmaStatus: "failed",
    });
  } finally {
    fs.unlink(promptPath).catch(() => {});
  }
}

function runLocalGemmaProcess({ pythonPath, modelPath, promptPath, timeoutMs }) {
  return new Promise((resolve, reject) => {
    const child = spawn(pythonPath, [
      path.join(rootDir, "tools", "local-gemma-runner.py"),
      "--model", modelPath,
      "--prompt-file", promptPath,
    ], {
      cwd: rootDir,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error("Local Gemma runner timed out"));
    }, timeoutMs);
    const stdout = [];
    const stderr = [];
    child.stdout.on("data", (chunk) => stdout.push(chunk));
    child.stderr.on("data", (chunk) => stderr.push(chunk));
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      const text = Buffer.concat(stdout).toString("utf8").trim();
      const err = Buffer.concat(stderr).toString("utf8").trim();
      try {
        const parsed = JSON.parse(text || "{}");
        if (code === 0 && parsed.ok) {
          resolve(parsed);
          return;
        }
        reject(new Error(parsed.error || parsed.text || err || `Local Gemma exited with ${code}`));
      } catch {
        reject(new Error(err || text || `Local Gemma exited with ${code}`));
      }
    });
  });
}

async function invokePlanningModel({ endpoint, model, apiKey, prompt, fallback, timeoutMs, successSource, fallbackSource, unavailableNote }) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(endpoint, {
      method: "POST",
      signal: controller.signal,
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model,
        input: [
          {
            role: "user",
            content: [{ type: "input_text", text: prompt }],
          },
        ],
      }),
    });
    clearTimeout(timer);
    const raw = await response.text();
    debugModelCall(successSource, {
      httpStatus: response.status,
      ok: response.ok,
      responseBytes: Buffer.byteLength(raw, "utf8"),
    });
    if (!response.ok) {
      return {
        ...fallback,
        source: fallbackSource,
        note: unavailableNote,
      };
    }
    const text = extractPlannerText(raw);
    debugModelCall(successSource, {
      extractedTextBytes: Buffer.byteLength(text, "utf8"),
      hasJsonObject: Boolean(parseJsonObject(text)),
    });
    return normalizePlan(text, fallback, successSource);
  } catch (error) {
    clearTimeout(timer);
    debugModelCall(successSource, {
      errorName: String(error?.name || "Error"),
      errorMessage: sanitizeDebugMessage(error?.message || error),
    });
    return {
      ...fallback,
      source: fallbackSource,
      note: unavailableNote,
    };
  }
}

function debugModelCall(stage, detail) {
  if (process.env.LAOBAI_DEBUG_MODEL !== "1") return;
  console.error(JSON.stringify({
    stage,
    ...detail,
  }));
}

function sanitizeDebugMessage(message) {
  return String(message || "")
    .replace(/Bearer\s+[A-Za-z0-9._-]+/g, "Bearer [hidden]")
    .replace(/https?:\/\/\S+/g, "[url-hidden]")
    .slice(0, 180);
}

function buildPlannerPrompt(payload, fallback) {
  return [
    "You are the cloud planner for a senior-assistance GUI agent demo.",
    "Return ONLY strict JSON. Do not include markdown.",
    "Schema: {\"summary\":\"short Chinese status\",\"actions\":[{\"type\":\"click|type|select|wait|guard\",\"target\":\"element id\",\"value\":\"optional\",\"reason\":\"Chinese reason\"}]}",
    "Rules: never submit, pay, send OTP, authorize, or delete; use guard before high-risk buttons.",
    "The edge computer-use executor will perform only the returned safe actions on a simulated phone UI.",
    `Scenario: ${payload?.scenario || "unknown"}`,
    `Observation: ${JSON.stringify(payload?.observation || {}, null, 2)}`,
    `Recommended fallback actions: ${JSON.stringify(fallback.actions, null, 2)}`,
  ].join("\n\n");
}

function buildEdgePrompt(payload, plan) {
  return [
    "You are the Computer-Use adapter for a senior-assistance GUI agent demo.",
    "Return ONLY strict JSON. Do not include markdown.",
    "Schema: {\"summary\":\"short Chinese status\",\"actions\":[{\"type\":\"click|type|select|wait|guard\",\"target\":\"element id\",\"value\":\"optional\",\"reason\":\"Chinese reason\"}]}",
    "Task: validate and return the safe GUI action list below using the same element ids.",
    "Do not add explanations outside JSON.",
    "Never click submit, confirm, payment, OTP, authorize, or delete targets. Use guard for those.",
    `Scenario: ${payload?.scenario || "unknown"}`,
    `Candidate actions: ${JSON.stringify(plan.actions || [], null, 2)}`,
  ].join("\n\n");
}

function buildLocalGemmaPrompt(payload, plan) {
  return [
    "You are Gemma 4B Computer-Use running locally on device.",
    "Return ONLY strict JSON. Do not include markdown.",
    "Schema: {\"summary\":\"short Chinese status\",\"actions\":[{\"type\":\"click|type|select|wait|guard\",\"target\":\"element id\",\"value\":\"optional\",\"reason\":\"Chinese reason\"}]}",
    "You control a simulated mobile phone HTML UI. Validate and return the safe GUI action list below using the same element ids.",
    "Never click submit, confirm, payment, OTP, authorize, or delete targets. Use guard for those.",
    `Scenario: ${payload?.scenario || "unknown"}`,
    `Candidate actions: ${JSON.stringify(plan.actions || [], null, 2)}`,
  ].join("\n\n");
}

function extractPlannerText(raw) {
  try {
    const json = JSON.parse(raw);
    if (Array.isArray(json.output)) {
      return json.output
        .flatMap((item) => Array.isArray(item.content) ? item.content : [])
        .map((part) => part.text || "")
        .filter(Boolean)
        .join("\n");
    }
    if (typeof json.output_text === "string") return json.output_text;
    if (typeof json.text === "string") return json.text;
  } catch {
    return raw;
  }
  return raw;
}

function normalizePlan(text, fallback, source) {
  const parsed = parseJsonObject(text);
  if (!parsed || !Array.isArray(parsed.actions)) {
    return {
      ...fallback,
      source: fallback.source || "edge-policy",
      note: "Planner returned narrative text; safe action policy used.",
      cloudSummary: text.slice(0, 220),
    };
  }
  const safeActions = parsed.actions
    .map((action) => ({
      type: String(action.type || ""),
      target: String(action.target || ""),
      value: action.value == null ? "" : String(action.value),
      reason: String(action.reason || "planner action"),
    }))
    .filter((action) => isSafeAction(action));
  const completedActions = completeDemoActions(safeActions, fallback.actions);
  return {
    ok: true,
    source,
    summary: readableModelText(parsed.summary) ? String(parsed.summary) : fallback.summary,
    actions: completedActions,
    note: "Model adapter produced safe GUI actions.",
    runtime: fallback.runtime || {},
  };
}

function completeDemoActions(modelActions, fallbackActions) {
  if (!Array.isArray(modelActions) || modelActions.length === 0) return fallbackActions;
  return fallbackActions.map((fallbackAction) => {
    const modelAction = modelActions.find((action) => sameActionSlot(action, fallbackAction));
    if (!modelAction || actionNeedsFallback(modelAction)) return fallbackAction;
    return {
      ...fallbackAction,
      type: modelAction.type,
      target: modelAction.target,
      reason: readableModelText(modelAction.reason) ? modelAction.reason : fallbackAction.reason,
    };
  });
}

function sameActionSlot(action, fallbackAction) {
  return action.type === fallbackAction.type && action.target === fallbackAction.target;
}

function actionNeedsFallback(action) {
  return (action.type === "type" || action.type === "select") && !String(action.value || "").trim();
}

function readableModelText(value) {
  const text = String(value || "").trim();
  return Boolean(text) && !text.includes("\uFFFD");
}

function withRuntime(plan, patch) {
  return {
    ...plan,
    runtime: {
      privacyScope: "redacted-structured-observation",
      keyVisibleToBrowser: false,
      providerVisibleToBrowser: false,
      modelNameVisibleToBrowser: false,
      ...(plan.runtime || {}),
      ...patch,
    },
  };
}

function parseJsonObject(text) {
  const trimmed = String(text || "").trim();
  try {
    return JSON.parse(trimmed);
  } catch {
    const start = trimmed.indexOf("{");
    const end = trimmed.lastIndexOf("}");
    if (start >= 0 && end > start) {
      try {
        return JSON.parse(trimmed.slice(start, end + 1));
      } catch {
        return null;
      }
    }
    return null;
  }
}

function fallbackPlan(payload) {
  if (payload?.scenario === "trigger-health") {
    return {
      ok: true,
      source: "edge-policy",
      summary: "本地端侧策略推荐消化内科，并停在确认挂号前。",
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
    source: "edge-policy",
    summary: "端侧 Computer-Use 策略识别报名表，并停在提交前。",
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

function isSafeAction(action) {
  const allowedTypes = new Set(["click", "type", "select", "wait", "guard"]);
  if (!allowedTypes.has(action.type)) return false;
  const highRiskTargets = ["submit", "confirm", "payment", "otp", "delete", "authorize"];
  const target = action.target.toLowerCase();
  if (action.type !== "guard" && highRiskTargets.some((item) => target.includes(item))) {
    return false;
  }
  return true;
}

async function serveFile(res, filePath) {
  const ext = path.extname(filePath).toLowerCase();
  const types = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
  };
  try {
    const content = await fs.readFile(filePath);
    res.writeHead(200, { "Content-Type": types[ext] || "application/octet-stream" });
    res.end(content);
  } catch {
    res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("Not found");
  }
}

function safeJoin(base, requestPath) {
  const clean = decodeURIComponent(requestPath).replace(/^[/\\]+/, "");
  const target = path.resolve(base, clean);
  if (!target.startsWith(base)) {
    throw new Error("Invalid path");
  }
  return target;
}

function sendJson(res, data, status = 200) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(data));
}

async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString("utf8");
  return text ? JSON.parse(text) : {};
}

function openBrowser(url) {
  const command = process.platform === "win32" ? "powershell.exe" : process.platform === "darwin" ? "open" : "xdg-open";
  const args = process.platform === "win32"
    ? ["-NoProfile", "-Command", `Start-Process '${url}'`]
    : [url];
  const child = spawn(command, args, { detached: true, stdio: "ignore" });
  child.unref();
}
