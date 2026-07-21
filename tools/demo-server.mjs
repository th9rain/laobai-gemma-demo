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
      return sendJson(res, {
        ok: false,
        error: "No index page. Open /always-on-form.html or /trigger-health.html.",
      }, 404);
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
      const safePath = safeJoin(webDir, url.pathname);
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
  if (payload?.scenario === "always-on-form") {
    return alwaysOnLocalWorkflowRequest(payload);
  }
  if (payload?.scenario === "trigger-health") {
    return triggerHealthPlannerRequest(payload);
  }
  if (payload?.scenario === "trigger-local-execute") {
    return triggerLocalWorkflowRequest(payload);
  }
  const fallback = triggerFallbackPlan(payload);
  const cloudPlan = await cloudPlannerRequest(payload, fallback);
  return edgeComputerUseRequest(payload, cloudPlan);
}

async function triggerHealthPlannerRequest(payload) {
  const fallback = triggerFallbackPlan(payload);
  const prompt = buildTriggerPlannerPrompt(payload, fallback);
  const rawPlannerOutput = JSON.stringify(triggerCachedPlannerOutput(fallback), null, 2);
  const cloudPlan = normalizeTriggerPlan(rawPlannerOutput, fallback, "cached-cloud-planner");
  return withRuntime({
    ...cloudPlan,
    modelCalls: [buildTriggerModelCall({
      payload,
      prompt,
      output: rawPlannerOutput,
      plan: cloudPlan,
      elapsedMs: 842,
      title: "Trigger 云端 Planner 回放 / cached cloud plan",
    })],
    note: "Cloud planner replay fixture. This demo does not call a live cloud API.",
  }, {
    plannerConfigured: true,
    plannerHandoff: true,
    plannerStatus: "cached-cloud-plan",
    edgeConfigured: true,
    edgeHandoff: false,
    edgeStatus: "waiting-local-gemma",
  });
}

async function alwaysOnLocalWorkflowRequest(payload) {
  if (!config.localGemmaEnabled) {
    throw new Error("Always-on requires LAOBAI_LOCAL_GEMMA_ENABLED=1. Static fallback is disabled.");
  }

  const modelPath = path.resolve(rootDir, config.localGemmaModelPath || "");
  const pythonPath = path.resolve(rootDir, config.localGemmaPython || "");
  try {
    await fs.access(modelPath);
    await fs.access(pythonPath);
  } catch (error) {
    throw new Error(`Always-on real Gemma dependency missing: ${error?.message || error}`);
  }

  const screenshot = payload?.screenshot || {};
  const screenshotPath = path.resolve(rootDir, String(screenshot.path || payload?.screenshotPath || ""));
  const pageNumber = Number(payload?.page || screenshot.page || 1);
  const width = Number(screenshot.width || payload?.width || 0);
  const height = Number(screenshot.height || payload?.height || 0);
  if (!screenshotPath || !width || !height) {
    throw new Error("Always-on computer-use requires a screenshot path, width, and height. Structured observation fallback is disabled.");
  }
  try {
    await fs.access(screenshotPath);
  } catch (error) {
    throw new Error(`Always-on screenshot is not readable: ${error?.message || error}`);
  }

  try {
    const result = await runAlwaysOnWorkflowProcess({
      pythonPath,
      modelPath,
      screenshotPath,
      pageNumber,
      width,
      height,
      timeoutMs: Number(process.env.LAOBAI_LOCAL_GEMMA_TIMEOUT_MS || 180000),
    });
    if (!result.ok || !result.plan) {
      throw new Error("Always-on real Gemma workflow returned no action plan.");
    }
    const normalized = normalizeAlwaysOnPlan(result.plan);
    return withRuntime({
      ...normalized,
      modelCalls: [{
        title: `Always-on 真实本地 Gemma 截图坐标调用 / 第 ${pageNumber} 页`,
        page: pageNumber,
        width,
        height,
        elapsedMs: result.elapsedMs || 0,
        screenshotPath: result.screenshotPath || screenshotPath,
        input: result.modelInput || "",
        output: result.modelOutput || "",
        screenshotDataUrl: result.screenshotDataUrl || "",
        rawInput: {
          role: "user",
          content: [
            {
              type: "input_image",
              image_ref: "local-temp-screenshot",
              path: result.screenshotPath || screenshotPath,
              width,
              height,
            },
            {
              type: "input_text",
              text: result.modelInput || "",
            },
          ],
          page: pageNumber,
          modelInputMode: "image + text",
        },
        parsedActions: normalized.actions,
      }],
      note: "Always-on action plan produced by local Gemma LiteRT from a screenshot.",
    }, alwaysOnRuntimePatch({
      localGemmaConfigured: true,
      localGemmaHandoff: true,
      localGemmaStatus: result.modelParsed ? "workflow-ok" : "workflow-validated",
      localGemmaParsed: Boolean(result.modelParsed),
    }));
  } catch (error) {
    debugModelCall("always-on-local-workflow", {
      errorName: String(error?.name || "Error"),
      errorMessage: sanitizeDebugMessage(error?.message || error),
    });
    if (error?.workflowResult) {
      return sendAlwaysOnFailure(error.workflowResult, error);
    }
    throw error;
  }
}

async function triggerLocalWorkflowRequest(payload) {
  if (!config.localGemmaEnabled) {
    throw new Error("Trigger local execution requires LAOBAI_LOCAL_GEMMA_ENABLED=1.");
  }

  const modelPath = path.resolve(rootDir, config.localGemmaModelPath || "");
  const pythonPath = path.resolve(rootDir, config.localGemmaPython || "");
  try {
    await fs.access(modelPath);
    await fs.access(pythonPath);
  } catch (error) {
    throw new Error(`Trigger real Gemma dependency missing: ${error?.message || error}`);
  }

  const screenshot = payload?.screenshot || {};
  const screenshotPath = path.resolve(rootDir, String(screenshot.path || payload?.screenshotPath || ""));
  const stage = String(payload?.stage || screenshot.stage || "");
  const width = Number(screenshot.width || payload?.width || 0);
  const height = Number(screenshot.height || payload?.height || 0);
  if (!screenshotPath || !stage || !width || !height) {
    throw new Error("Trigger computer-use requires stage, screenshot path, width, and height.");
  }
  try {
    await fs.access(screenshotPath);
  } catch (error) {
    throw new Error(`Trigger screenshot is not readable: ${error?.message || error}`);
  }

  try {
    const result = await runTriggerWorkflowProcess({
      pythonPath,
      modelPath,
      screenshotPath,
      stage,
      width,
      height,
      timeoutMs: Number(process.env.LAOBAI_LOCAL_GEMMA_TIMEOUT_MS || 180000),
    });
    if (!result.ok || !result.plan) {
      throw new Error("Trigger real Gemma workflow returned no action plan.");
    }
    const normalized = normalizeTriggerCoordinatePlan(result.plan);
    return withRuntime({
      ...normalized,
      modelCalls: [{
        title: `Trigger 本地 Gemma 截图坐标调用 / ${stage}`,
        stage,
        width,
        height,
        elapsedMs: result.elapsedMs || 0,
        screenshotPath: result.screenshotPath || screenshotPath,
        screenshotDataUrl: result.screenshotDataUrl || "",
        input: result.modelInput || "",
        output: result.modelOutput || "",
        rawInput: {
          role: "user",
          content: [
            {
              type: "input_image",
              image_ref: "local-temp-trigger-screenshot",
              path: result.screenshotPath || screenshotPath,
              width,
              height,
            },
            {
              type: "input_text",
              text: result.modelInput || "",
            },
          ],
          stage,
          modelInputMode: "image + text",
        },
        parsedActions: normalized.actions,
      }],
      note: "Trigger action plan produced by local Gemma LiteRT from a screenshot.",
    }, {
      workflowMode: "trigger-cached-cloud-real-local-gemma",
      plannerConfigured: true,
      plannerHandoff: true,
      plannerStatus: "cached-cloud-plan",
      localGemmaConfigured: true,
      localGemmaHandoff: true,
      localGemmaStatus: result.modelParsed ? "workflow-ok" : "workflow-validated",
      edgeConfigured: true,
      edgeHandoff: true,
      edgeStatus: "local-gemma-coordinate-executor",
    });
  } catch (error) {
    debugModelCall("trigger-local-workflow", {
      errorName: String(error?.name || "Error"),
      errorMessage: sanitizeDebugMessage(error?.message || error),
    });
    if (error?.workflowResult) {
      return sendTriggerFailure(error.workflowResult, error);
    }
    throw error;
  }
}

function sendTriggerFailure(result, error) {
  return withRuntime({
    ok: false,
    source: "trigger-real-local-gemma-vision",
    summary: "本地 Gemma 截图调用已返回，但没有通过 Trigger 坐标动作校验。",
    error: String(result?.error || error?.message || error),
    actions: [],
    modelCalls: [{
      title: "Trigger 本地 Gemma 截图坐标调用失败",
      stage: result?.stage || null,
      input: result?.modelInput || "",
      output: result?.modelOutput || "",
      screenshotDataUrl: result?.screenshotDataUrl || "",
      screenshotPath: result?.screenshotPath || "",
      rawInput: {
        role: "user",
        content: [
          {
            type: "input_image",
            image_ref: "local-temp-trigger-screenshot",
            path: result?.screenshotPath || "",
          },
          {
            type: "input_text",
            text: result?.modelInput || "",
          },
        ],
        modelInputMode: "image + text",
      },
      parsedActions: [],
    }],
  }, {
    workflowMode: "trigger-cached-cloud-real-local-gemma",
    plannerConfigured: true,
    plannerHandoff: true,
    plannerStatus: "cached-cloud-plan",
    localGemmaConfigured: true,
    localGemmaHandoff: false,
    localGemmaStatus: "model-output-invalid",
  });
}

function normalizeTriggerCoordinatePlan(plan) {
  const actions = Array.isArray(plan?.actions) ? plan.actions : [];
  const safeActions = actions
    .map((action) => ({
      type: String(action.type || ""),
      x: Number(action.x),
      y: Number(action.y),
      text: action.text == null ? "" : String(action.text),
      label: action.label == null ? "" : String(action.label),
      reason: String(action.reason || "local Gemma trigger action"),
    }))
    .filter((action) => isSafeCoordinateAction(action));
  if (safeActions.length === 0) {
    throw new Error("Trigger real Gemma returned no safe actions.");
  }
  return {
    ok: true,
    source: "trigger-real-local-gemma-vision",
    summary: readableModelText(plan.summary) ? String(plan.summary) : "本地 Gemma 已根据截图生成 Trigger 坐标动作。",
    actions: safeActions,
  };
}

function runTriggerWorkflowProcess({ pythonPath, modelPath, screenshotPath, stage, width, height, timeoutMs }) {
  return new Promise((resolve, reject) => {
    const child = spawn(pythonPath, [
      path.join(rootDir, "tools", "trigger-health-workflow.py"),
      "--model", modelPath,
      "--screenshot", screenshotPath,
      "--stage", stage,
      "--width", String(width),
      "--height", String(height),
    ], {
      cwd: rootDir,
      env: pythonUtf8Env(),
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error("Trigger workflow timed out"));
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
        const error = new Error(parsed.error || parsed.text || err || `Trigger workflow exited with ${code}`);
        error.workflowResult = parsed;
        reject(error);
      } catch {
        reject(new Error(err || text || `Trigger workflow exited with ${code}`));
      }
    });
  });
}

function sendAlwaysOnFailure(result, error) {
  return withRuntime({
    ok: false,
    source: "always-on-real-local-gemma-vision",
    summary: "本地 Gemma 截图调用已返回，但没有通过坐标动作校验。",
    error: String(result?.error || error?.message || error),
    actions: [],
    modelCalls: [{
      title: "Always-on 真实本地 Gemma 截图坐标调用失败",
      page: result?.page || null,
      input: result?.modelInput || "",
      output: result?.modelOutput || "",
      screenshotDataUrl: result?.screenshotDataUrl || "",
      screenshotPath: result?.screenshotPath || "",
      rawInput: {
        role: "user",
        content: [
          {
            type: "input_image",
            image_ref: "local-temp-screenshot",
            path: result?.screenshotPath || "",
          },
          {
            type: "input_text",
            text: result?.modelInput || "",
          },
        ],
        modelInputMode: "image + text",
      },
      parsedActions: [],
    }],
  }, alwaysOnRuntimePatch({
    localGemmaConfigured: true,
    localGemmaHandoff: false,
    localGemmaStatus: "model-output-invalid",
  }));
}

function normalizeAlwaysOnPlan(plan) {
  const actions = Array.isArray(plan?.actions) ? plan.actions : [];
  const safeActions = actions
    .map((action) => ({
      type: String(action.type || ""),
      x: Number(action.x),
      y: Number(action.y),
      text: action.text == null ? "" : String(action.text),
      label: action.label == null ? "" : String(action.label),
      reason: String(action.reason || "local Gemma action"),
    }))
    .filter((action) => isSafeCoordinateAction(action));
  if (safeActions.length === 0) {
    throw new Error("Always-on real Gemma returned no safe actions.");
  }
  return {
    ok: true,
    source: "always-on-real-local-gemma-vision",
    summary: readableModelText(plan.summary) ? String(plan.summary) : "本地 Gemma 已根据截图生成 Always-on 坐标动作。",
    actions: safeActions,
  };
}

function alwaysOnRuntimePatch(extra = {}) {
  return {
    workflowMode: "always-on-local-only",
    plannerConfigured: false,
    plannerHandoff: false,
    plannerSkipped: true,
    plannerStatus: "not-used",
    edgeConfigured: true,
    edgeHandoff: true,
    edgeStatus: "local-workflow",
    ...extra,
  };
}

function runAlwaysOnWorkflowProcess({ pythonPath, modelPath, screenshotPath, pageNumber, width, height, timeoutMs }) {
  return new Promise((resolve, reject) => {
    const child = spawn(pythonPath, [
      path.join(rootDir, "tools", "always-on-workflow.py"),
      "--model", modelPath,
      "--screenshot", screenshotPath,
      "--page", String(pageNumber),
      "--width", String(width),
      "--height", String(height),
    ], {
      cwd: rootDir,
      env: pythonUtf8Env(),
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error("Always-on workflow timed out"));
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
        const error = new Error(parsed.error || parsed.text || err || `Always-on workflow exited with ${code}`);
        error.workflowResult = parsed;
        reject(error);
      } catch {
        reject(new Error(err || text || `Always-on workflow exited with ${code}`));
      }
    });
  });
}

function isSafeCoordinateAction(action) {
  const allowedTypes = new Set(["type_at", "click", "guard", "wait"]);
  if (!allowedTypes.has(action.type)) return false;
  if (action.type === "wait") return true;
  if (!Number.isFinite(action.x) || !Number.isFinite(action.y)) return false;
  const highRiskTargets = ["submit", "confirm", "payment", "otp", "delete", "authorize", "提交", "确认", "支付", "验证码", "删除", "授权"];
  const combined = `${action.label || ""} ${action.text || ""} ${action.reason || ""}`.toLowerCase();
  if (action.type !== "guard" && highRiskTargets.some((item) => combined.includes(item.toLowerCase())) && !combined.includes("下一页")) {
    return false;
  }
  if (action.type === "type_at" && !String(action.text || "").trim()) return false;
  return true;
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
  if (payload?.scenario !== "trigger-health" && config.localGemmaEnabled) {
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
      env: pythonUtf8Env(),
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

function pythonUtf8Env() {
  return {
    ...process.env,
    PYTHONIOENCODING: "utf-8",
    PYTHONUTF8: "1",
  };
}

async function invokePlanningModel({ endpoint, model, apiKey, prompt, fallback, timeoutMs, successSource, fallbackSource, unavailableNote, normalizer }) {
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
        max_output_tokens: Number(process.env.LAOBAI_PLANNER_MAX_OUTPUT_TOKENS || 1200),
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
    const normalized = typeof normalizer === "function"
      ? normalizer(text, fallback, successSource)
      : normalizePlan(text, fallback, successSource);
    return {
      ...normalized,
      rawPlannerOutput: text,
    };
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
  return Boolean(text) && !text.includes("\uFFFD") && !/^\?+$/.test(text.replace(/\s+/g, "")) && !text.includes("????");
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

function triggerFallbackPlan(payload) {
  if (payload?.scenario === "trigger-health") {
    const appointment = {
      hospital: "北京协和医院",
      department: "消化内科",
      doctor: "李明 主任医师",
      date: "后天",
      time: "上午 10:00",
      reason: "明天有手机培训比赛安排，后天上午空闲；老人常去协和消化内科，优先挂常去医生。",
    };
    return {
      ok: true,
      source: "edge-policy",
      summary: "云端规划应避开明天比赛，安排后天上午 10 点挂北京协和医院消化内科常去医生，并在确认前停住。",
      appointment,
      actions: [
        { type: "tap", target: "app-jingyitong", reason: "本地打开京医通挂号 App" },
        { type: "choose", target: "hospital-card", value: appointment.hospital, reason: "选择老人常去且距离合适的医院" },
        { type: "choose", target: "department-card", value: appointment.department, reason: "胃部不适优先挂消化内科" },
        { type: "choose", target: "doctor-card", value: appointment.doctor, reason: "选择老人经常挂的医生" },
        { type: "choose", target: "time-card", value: `${appointment.date} ${appointment.time}`, reason: "明天有手机培训比赛，选择后天上午 10 点" },
        { type: "fill", target: "patient-card", value: "李桂兰（70多岁，手机号 138****2675）", reason: "使用端侧本地记忆填写就诊人脱敏信息" },
        { type: "guard", target: "confirm-booking", reason: "确认挂号、支付和验证码属于高风险动作，必须由用户自己确认", confirmationText: "我已经填到确认挂号页。后面涉及确认挂号、支付或验证码，需要您自己看清楚后再操作。" },
      ],
    };
  }
  return {
    ok: false,
    source: "unsupported-scenario",
    summary: "未知 demo 场景；没有启用静态动作。",
    actions: [],
  };
}

function triggerCachedPlannerOutput(fallback) {
  const appointment = fallback.appointment || {};
  return {
    summary: "已避开明天手机培训比赛，建议后天上午 10:00 挂北京协和医院消化内科常去医生。",
    appointment: {
      hospital: appointment.hospital || "北京协和医院",
      department: appointment.department || "消化内科",
      doctor: appointment.doctor || "李明 主任医师",
      date: appointment.date || "后天",
      time: appointment.time || "上午 10:00",
      reason: "症状是胃部不适两天并伴轻微恶心，优先消化内科；明天全天有手机培训比赛安排，后天上午 10:00 空闲；本地记忆显示老人常去北京协和医院并常挂李明主任医师。",
    },
    safety: {
      cloud_receives_raw_screenshot: false,
      cloud_receives_full_phone: false,
      cloud_receives_id_number: false,
      stop_before: ["确认挂号", "支付", "验证码"],
    },
  };
}

function normalizeTriggerPlan(text, fallback, source) {
  const parsed = parseJsonObject(text);
  if (!parsed) {
    return {
      ...fallback,
      source: fallback.source || "edge-policy",
      note: "Planner returned non-JSON text; safe trigger plan used.",
      cloudSummary: String(text || "").slice(0, 240),
    };
  }
  if (!parsed.appointment || typeof parsed.appointment !== "object") {
    return {
      ...fallback,
      source: fallback.source || "edge-policy",
      note: "Planner returned JSON without appointment; safe trigger plan used.",
      cloudSummary: String(text || "").slice(0, 240),
    };
  }
  const fallbackAppointment = fallback.appointment || {};
  const appointment = {
    hospital: readableModelText(parsed.appointment?.hospital) ? String(parsed.appointment.hospital) : fallbackAppointment.hospital,
    department: readableModelText(parsed.appointment?.department) ? String(parsed.appointment.department) : fallbackAppointment.department,
    doctor: readableModelText(parsed.appointment?.doctor) ? String(parsed.appointment.doctor) : fallbackAppointment.doctor,
    date: readableModelText(parsed.appointment?.date) ? String(parsed.appointment.date) : fallbackAppointment.date,
    time: readableModelText(parsed.appointment?.time) ? String(parsed.appointment.time) : fallbackAppointment.time,
    reason: readableModelText(parsed.appointment?.reason) ? String(parsed.appointment.reason) : fallbackAppointment.reason,
  };
  const modelActions = Array.isArray(parsed.actions) ? parsed.actions
    .filter((action) => action && typeof action === "object")
    .map((action) => ({
      type: String(action.type || ""),
      target: String(action.target || ""),
      value: action.value == null ? "" : String(action.value),
      reason: String(action.reason || "planner action"),
      confirmationText: action.confirmationText == null ? "" : String(action.confirmationText),
    }))
    .filter((action) => isSafeTriggerAction(action)) : [];
  const actions = completeTriggerActions(modelActions, fallback.actions, appointment);
  return {
    ok: true,
    source,
    summary: readableModelText(parsed.summary) ? String(parsed.summary) : fallback.summary,
    appointment,
    actions,
    note: "Cloud planner produced a safe appointment plan; local executor will perform the visual workflow.",
  };
}

function completeTriggerActions(modelActions, fallbackActions, appointment) {
  const safeFallback = fallbackActions.map((action) => fillAppointmentValue(action, appointment));
  if (!Array.isArray(modelActions) || modelActions.length === 0) return safeFallback;
  return safeFallback.map((fallbackAction) => {
    const modelAction = modelActions.find((action) => action.type === fallbackAction.type && action.target === fallbackAction.target);
    if (!modelAction) return fallbackAction;
    const value = readableModelText(modelAction.value) ? modelAction.value : fallbackAction.value;
    const reason = readableModelText(modelAction.reason) ? modelAction.reason : fallbackAction.reason;
    const confirmationText = readableModelText(modelAction.confirmationText) ? modelAction.confirmationText : fallbackAction.confirmationText;
    return {
      ...fallbackAction,
      value,
      reason,
      confirmationText,
    };
  });
}

function fillAppointmentValue(action, appointment) {
  const values = {
    "hospital-card": appointment.hospital,
    "department-card": appointment.department,
    "doctor-card": appointment.doctor,
    "time-card": `${appointment.date} ${appointment.time}`,
    "patient-card": "李桂兰（70多岁，手机号 138****2675）",
  };
  return {
    ...action,
    value: action.value || values[action.target] || "",
  };
}

function buildTriggerPlannerPrompt(payload, fallback) {
  const observation = payload?.observation || {};
  return [
    "IMPORTANT: Return one compact valid JSON object only. No markdown. No explanation.",
    "You are the cloud planner for LaoBai, a senior-assistance mobile GUI agent demo.",
    "Task: user has stomach discomfort for 2 days, mild nausea, no chest pain.",
    "Tomorrow is busy because the user attends a mobile training competition. Choose the day after tomorrow at 10:00.",
    "Use frequent hospital/department/doctor from local memory. Never include raw ID number, full phone, OTP, payment, or raw medical records.",
    "Return this JSON shape exactly. Do not return actions; local execution is handled on device:",
    "{\"summary\":\"中文一句话\",\"appointment\":{\"hospital\":\"北京协和医院\",\"department\":\"消化内科\",\"doctor\":\"李明 主任医师\",\"date\":\"后天\",\"time\":\"上午 10:00\",\"reason\":\"中文一句话\"}}",
    `Local memory summary: ${JSON.stringify({
      frequentHospital: observation.localMemory?.nearestHospital || fallback.appointment?.hospital,
      frequentDepartment: observation.localMemory?.frequentDepartment || fallback.appointment?.department,
      frequentDoctor: observation.localMemory?.frequentDoctor || fallback.appointment?.doctor,
      busyTomorrow: "mobile training competition",
      freeSlot: "day after tomorrow 10:00",
    })}`,
  ].join("\n");
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

function isSafeTriggerAction(action) {
  const allowedTypes = new Set(["tap", "choose", "fill", "wait", "guard"]);
  if (!allowedTypes.has(action.type)) return false;
  const allowedTargets = new Set([
    "app-jingyitong",
    "hospital-card",
    "department-card",
    "doctor-card",
    "time-card",
    "patient-card",
    "confirm-booking",
  ]);
  if (action.type !== "wait" && !allowedTargets.has(action.target)) return false;
  const highRiskTargets = ["confirm", "payment", "otp", "delete", "authorize", "支付", "验证码", "删除", "授权"];
  const combined = `${action.target || ""} ${action.value || ""} ${action.reason || ""}`.toLowerCase();
  if (action.type !== "guard" && highRiskTargets.some((item) => combined.includes(item.toLowerCase()))) return false;
  return true;
}

function buildTriggerModelCall({ payload, prompt, output, plan, elapsedMs, title }) {
  return {
    title,
    elapsedMs,
    input: prompt,
    output: String(output || ""),
    rawInput: {
      role: "user",
      content: [
        {
          type: "input_text",
          text: "Trigger health booking planner request with strict redaction",
        },
      ],
      redactedObservation: payload?.observation || {},
      privacy: {
        rawScreenUploaded: false,
        fullPhoneUploaded: false,
        idNumberUploaded: false,
        otpUploaded: false,
        rawMedicalRecordUploaded: false,
      },
    },
    parsed: {
      appointment: plan?.appointment || null,
      localExecutionContract: plan?.actions || [],
    },
    parsedActions: plan?.actions || [],
  };
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
    res.writeHead(200, {
      "Content-Type": types[ext] || "application/octet-stream",
      "Cache-Control": "no-store, max-age=0",
    });
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
