import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";

const port = Number(process.env.LAOBAI_DEMO_PORT || await getFreePort());
const base = `http://localhost:${port}`;

async function main() {
  const stdoutPath = path.join(os.tmpdir(), `laobai-verify-${port}.out`);
  const stderrPath = path.join(os.tmpdir(), `laobai-verify-${port}.err`);
  const stdout = fs.openSync(stdoutPath, "w");
  const stderr = fs.openSync(stderrPath, "w");
  const server = spawn(process.execPath, ["tools/demo-server.mjs", "--no-open"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      LAOBAI_DEMO_PORT: String(port),
      LAOBAI_SKIP_LOCAL_CONFIG: "1",
      LAOBAI_PLANNER_ENDPOINT: "",
      LAOBAI_PLANNER_API_KEY: "",
      LAOBAI_EDGE_ENDPOINT: "",
      LAOBAI_EDGE_API_KEY: "",
      LAOBAI_LOCAL_GEMMA_ENABLED: "1",
      LAOBAI_LOCAL_GEMMA_MODEL_PATH: "models/gemma-4-E4B-it.litertlm",
      LAOBAI_LOCAL_GEMMA_PYTHON: ".venv/Scripts/python.exe",
      LAOBAI_PUBLIC_PLANNER_LABEL: "Gemma 4 30B Cloud Planner",
      LAOBAI_PUBLIC_EDGE_LABEL: "Gemma 4B Computer-Use",
    },
    stdio: ["ignore", stdout, stderr],
    detached: false,
  });

  try {
    await waitForServer();
    const publicConfig = await requestJson("/api/public-config", null, "GET");
    assert(publicConfig.plannerLabel === "Gemma 4 30B Cloud Planner", "planner label mismatch");
    assert(publicConfig.edgeLabel === "Gemma 4B Computer-Use", "edge label mismatch");

    const rejectedAlwaysOn = await requestTextExpectError("/api/plan", {
      scenario: "always-on-form",
      observation: {
        pageTitle: "北京市朝阳区社区智慧课堂报名表",
        currentPage: 1,
        visibleFields: ["姓名", "年龄段", "手机号", "下一页"],
      },
    });
    assert(
      rejectedAlwaysOn.includes("screenshot path") || rejectedAlwaysOn.includes("screenshot"),
      "always-on structured observation request should be rejected",
    );

    const health = await requestJson("/api/plan", {
      scenario: "trigger-health",
      observation: {
        userRequest: "我胃不舒服，帮我挂号",
        symptomSummary: "胃不舒服，持续两天，轻微恶心，无胸痛",
      },
    });
    assert(health.runtime?.keyVisibleToBrowser === false, "health runtime exposes key state incorrectly");
    assert(health.runtime?.privacyScope, "health runtime missing privacy scope");
    assertAction(health, "click", "ask-button");
    assertAction(health, "select", "department");
    assertAction(health, "guard", "confirm-button");

    console.log("Demo verification passed.");
  } catch (error) {
    const logs = [
      fs.existsSync(stdoutPath) ? fs.readFileSync(stdoutPath, "utf8").trim() : "",
      fs.existsSync(stderrPath) ? fs.readFileSync(stderrPath, "utf8").trim() : "",
    ].filter(Boolean).join("\n");
    if (logs) console.error(logs);
    throw error;
  } finally {
    server.kill();
    fs.closeSync(stdout);
    fs.closeSync(stderr);
  }
}

function getFreePort() {
  return new Promise((resolve, reject) => {
    const probe = http.createServer();
    probe.listen(0, "127.0.0.1", () => {
      const address = probe.address();
      const port = typeof address === "object" && address ? address.port : 0;
      probe.close(() => resolve(port));
    });
    probe.on("error", reject);
  });
}

function assertAction(plan, type, target) {
  const ok = Array.isArray(plan.actions) && plan.actions.some((action) => action.type === type && action.target === target);
  assert(ok, `missing action ${type}:${target}`);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function waitForServer() {
  const started = Date.now();
  while (Date.now() - started < 8000) {
    try {
      await requestJson("/api/public-config", null, "GET");
      return;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  }
  throw new Error("server did not start");
}

function requestJson(pathname, body, method = "POST") {
  return request(pathname, body, method).then(({ statusCode, text }) => {
    if (statusCode < 200 || statusCode > 299) {
      throw new Error(`HTTP ${statusCode}: ${text}`);
    }
    return JSON.parse(text);
  });
}

function requestTextExpectError(pathname, body) {
  return request(pathname, body, "POST").then(({ statusCode, text }) => {
    if (statusCode >= 400) return text;
    throw new Error(`expected HTTP error but got ${statusCode}: ${text}`);
  });
}

function request(pathname, body, method = "POST") {
  return new Promise((resolve, reject) => {
    const data = body == null ? "" : JSON.stringify(body);
    const req = http.request(
      `${base}${pathname}`,
      {
        method,
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(data),
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          resolve({
            statusCode: res.statusCode || 0,
            text: Buffer.concat(chunks).toString("utf8"),
          });
        });
      },
    );
    req.on("error", reject);
    if (data) req.write(data);
    req.end();
  });
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
