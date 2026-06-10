import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")


def load_local_gemma_runner():
    runner_path = Path(__file__).with_name("local-gemma-runner.py")
    spec = importlib.util.spec_from_file_location("local_gemma_runner", runner_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


run_litert_lm = load_local_gemma_runner().run_litert_lm


PAGE_CONTRACTS = {
    1: {
        "required_targets": ["name", "age", "phone", "next-button"],
        "allowed_targets": ["name", "age", "phone", "next-button"],
    },
    2: {
        "required_targets": ["area", "contact", "course", "learning-goal", "submit-button"],
        "allowed_targets": ["area", "contact", "course", "learning-goal", "submit-button"],
    },
}

ALLOWED_TYPES = {"click", "type", "select", "wait", "guard"}
HIGH_RISK_TARGETS = ("submit", "confirm", "payment", "otp", "delete", "authorize")
SELECT_TARGETS = {"course"}

DEFAULT_OBSERVATION = {
    "device": "mobile-portrait-sim",
    "pageTitle": "北京市朝阳区社区智慧课堂报名表",
    "currentPage": 1,
    "visibleFields": ["姓名", "年龄段", "手机号", "下一页"],
    "privacy": {
        "rawScreenUploaded": False,
        "redaction": "strict",
        "sensitiveFields": ["phone"],
    },
    "goal": "填写常用报名信息，并停在提交报名前",
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--observation-file", default="")
    parser.add_argument("--max-tokens", type=int, default=4096)
    args = parser.parse_args()

    observation = read_observation(args.observation_file)

    prompt = build_prompt(observation)
    text = run_litert_lm(args.model, prompt, args.max_tokens)
    model_plan = parse_json_object(text)
    model_parsed = isinstance(model_plan, dict) and isinstance(model_plan.get("actions"), list)
    if not model_parsed:
        raise ValueError("Gemma did not return valid JSON with an actions array.")
    plan = validate_model_plan(model_plan, observation)

    print(json.dumps({
        "ok": True,
        "modelParsed": model_parsed,
        "modelInput": prompt,
        "modelOutput": text,
        "plan": plan,
    }, ensure_ascii=False))
    return 0


def read_observation(path):
    if not path:
        return DEFAULT_OBSERVATION
    with open(path, "r", encoding="utf-8-sig") as f:
        return json.load(f)


def build_prompt(observation):
    page = current_page(observation)
    page_contract = PAGE_CONTRACTS.get(page)
    if not page_contract:
        raise ValueError(f"Unsupported form page: {page}")
    return "\n\n".join([
        "You are Gemma 4B Computer-Use running fully on device.",
        "Task: multi-step Always-on form filling for a senior assistance app.",
        "No cloud planner is available. Do not ask for cloud help.",
        "Return ONLY strict JSON. Do not include markdown.",
        "Schema: {\"summary\":\"short Chinese status\",\"actions\":[{\"type\":\"click|type|select|guard\",\"target\":\"element id\",\"value\":\"value\",\"reason\":\"Chinese reason\"}]}",
        "You must reason from the current visible page only. The user will observe a new page after any next-page click.",
        f"Current page number: {page}",
        "Visible element ids:",
        json.dumps(page_contract["allowed_targets"], ensure_ascii=False),
        "Required element ids that should appear in the action plan:",
        json.dumps(page_contract["required_targets"], ensure_ascii=False),
        f"You must return exactly {len(page_contract['required_targets'])} actions, one for each required element id, in the same order.",
        "For text inputs use type. For native dropdowns use select. For next-button use click. For submit-button use guard.",
        "Never click submit-button. Use guard for submit-button.",
        "Use this local memory:",
        json.dumps({
            "name": "李桂兰",
            "age": "70s",
            "phone": "138****2675",
            "area": "北京市朝阳区望京街道",
            "contact": "女儿 王敏",
            "course": "智能手机基础课",
            "learning_goal": "想学会微信视频、线上挂号和识别诈骗短信。",
        }, ensure_ascii=False),
        "Return a slightly detailed Chinese summary and Chinese reasons so the operator can inspect the model output.",
        f"Screen observation: {json.dumps(observation, ensure_ascii=False)}",
    ])


def validate_model_plan(model_plan, observation):
    page_contract = PAGE_CONTRACTS.get(current_page(observation))
    model_actions = [
        normalize_action(action)
        for action in model_plan.get("actions", [])
    ]
    safe_actions = [
        action for action in model_actions
        if action and action["target"] in page_contract["allowed_targets"] and is_safe_action(action)
    ]
    required_targets = set(page_contract["required_targets"])
    returned_targets = {action["target"] for action in safe_actions}
    missing_targets = required_targets - returned_targets
    if missing_targets:
        raise ValueError(f"Gemma action plan missing required targets: {', '.join(sorted(missing_targets))}")
    summary = model_plan.get("summary")
    return {
        "summary": summary if readable(summary) else "本地 Gemma 已生成 Always-on GUI 动作。",
        "actions": safe_actions,
    }


def normalize_action(action):
    if not isinstance(action, dict):
        return None
    action_type = str(action.get("type", ""))
    target = str(action.get("target", ""))
    if target in SELECT_TARGETS and action_type == "type":
        action_type = "select"
    return {
        "type": action_type,
        "target": target,
        "value": "" if action.get("value") is None else str(action.get("value")),
        "reason": str(action.get("reason", "")),
    }


def current_page(observation):
    try:
        return int(observation.get("currentPage") or observation.get("page") or 1)
    except Exception:
        return 1


def is_safe_action(action):
    action_type = str(action.get("type", ""))
    target = str(action.get("target", "")).lower()
    if action_type not in ALLOWED_TYPES:
        return False
    if action_type != "guard" and any(item in target for item in HIGH_RISK_TARGETS):
        return False
    return True


def readable(value):
    text = str(value or "").strip()
    return bool(text) and "\ufffd" not in text


def parse_json_object(text):
    raw = str(text or "").strip()
    try:
        return json.loads(raw)
    except Exception:
        pass
    match = re.search(r"\{.*\}", raw, re.DOTALL)
    if not match:
        return None
    try:
        return json.loads(match.group(0))
    except Exception:
        return None


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False))
        raise SystemExit(1)
