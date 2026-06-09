import http from "node:http";
import { spawn } from "node:child_process";

const port = Number(process.env.LAOBAI_DEMO_PORT || 49173 + Math.floor(Math.random() * 1000));
const base = `http://localhost:${port}`;

async function main() {
  const server = spawn(process.execPath, ["tools/demo-server.mjs", "--no-open"], {
    env: {
      ...process.env,
      LAOBAI_DEMO_PORT: String(port),
      LAOBAI_SKIP_LOCAL_CONFIG: "1",
      LAOBAI_PLANNER_ENDPOINT: "",
      LAOBAI_PLANNER_API_KEY: "",
      LAOBAI_EDGE_ENDPOINT: "",
      LAOBAI_EDGE_API_KEY: "",
      LAOBAI_PUBLIC_PLANNER_LABEL: "Cloud Planner Adapter",
      LAOBAI_PUBLIC_EDGE_LABEL: "Gemma 4B Computer-Use",
    },
    stdio: "ignore",
    detached: false,
  });
  try {
    await waitForServer();
    const publicConfig = await postJson("/api/public-config", null, "GET");
    assert(publicConfig.plannerLabel === "Cloud Planner Adapter", "planner label mismatch");
    assert(publicConfig.edgeLabel === "Gemma 4B Computer-Use", "edge label mismatch");

    const form = await postJson("/api/plan", {
      scenario: "always-on-form",
      observation: {
        pageTitle: "北京市朝阳区社区智慧课堂报名表",
        visibleFields: ["姓名", "年龄段", "手机号", "居住区域", "紧急联系人", "报名课程"],
      },
    });
    assert(form.runtime?.keyVisibleToBrowser === false, "form runtime exposes key state incorrectly");
    assert(form.runtime?.providerVisibleToBrowser === false, "form runtime exposes provider state incorrectly");
    assert(form.runtime?.modelNameVisibleToBrowser === false, "form runtime exposes model state incorrectly");
    assertAction(form, "type", "name");
    assertAction(form, "select", "course");
    assertAction(form, "guard", "submit-button");

    const health = await postJson("/api/plan", {
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
  } finally {
    server.kill();
  }
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
      await postJson("/api/public-config", null, "GET");
      return;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  }
  throw new Error("server did not start");
}

function postJson(path, body, method = "POST") {
  return new Promise((resolve, reject) => {
    const data = body == null ? "" : JSON.stringify(body);
    const req = http.request(
      `${base}${path}`,
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
          const text = Buffer.concat(chunks).toString("utf8");
          if (res.statusCode < 200 || res.statusCode > 299) {
            reject(new Error(`HTTP ${res.statusCode}: ${text}`));
            return;
          }
          resolve(JSON.parse(text));
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
