import argparse
import importlib.util
import json
import re
from pathlib import Path


def load_local_gemma_runner():
    runner_path = Path(__file__).with_name("local-gemma-runner.py")
    spec = importlib.util.spec_from_file_location("local_gemma_runner", runner_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


run_litert_lm = load_local_gemma_runner().run_litert_lm


FIXED_WORKFLOW = {
    "summary": "Always-on 本地 workflow 已识别社区报名表，准备填写常用信息并停在提交前。",
    "actions": [
        {"type": "type", "target": "name", "value": "李桂兰", "reason": "填写端侧本地记忆中的姓名"},
        {"type": "type", "target": "age", "value": "70s", "reason": "填写端侧本地记忆中的年龄段"},
        {"type": "type", "target": "phone", "value": "138****2675", "reason": "只填写脱敏手机号"},
        {"type": "type", "target": "area", "value": "北京市朝阳区望京街道", "reason": "填写端侧本地记忆中的居住区域"},
        {"type": "type", "target": "contact", "value": "女儿 王敏", "reason": "填写端侧本地记忆中的紧急联系人"},
        {"type": "select", "target": "course", "value": "智能手机基础课", "reason": "选择端侧本地记忆中的偏好课程"},
        {"type": "guard", "target": "submit-button", "value": "", "reason": "提交报名属于高风险动作，必须停在提交前"},
    ],
}

ALLOWED_TYPES = {"click", "type", "select", "wait", "guard"}
HIGH_RISK_TARGETS = ("submit", "confirm", "payment", "otp", "delete", "authorize")

DEFAULT_OBSERVATION = {
    "device": "mobile-portrait-sim",
    "pageTitle": "北京市朝阳区社区智慧课堂报名表",
    "visibleFields": ["姓名", "年龄段", "手机号", "居住区域", "紧急联系人", "报名课程"],
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
    plan = merge_with_fixed_workflow(model_plan if model_parsed else {})

    print(json.dumps({
        "ok": True,
        "modelParsed": model_parsed,
        "plan": plan,
    }, ensure_ascii=False))
    return 0


def read_observation(path):
    if not path:
        return DEFAULT_OBSERVATION
    with open(path, "r", encoding="utf-8-sig") as f:
        return json.load(f)


def build_prompt(observation):
    return "\n\n".join([
        "You are Gemma 4B Computer-Use running fully on device.",
        "Task: fixed Always-on form filling workflow for a senior assistance demo.",
        "No cloud planner is available. Do not ask for cloud help.",
        "Return ONLY strict JSON. Do not include markdown.",
        "Schema: {\"summary\":\"short Chinese status\",\"actions\":[{\"type\":\"type|select|guard\",\"target\":\"element id\",\"value\":\"value\",\"reason\":\"Chinese reason\"}]}",
        "Use exactly these element ids in this order: name, age, phone, area, contact, course, submit-button.",
        "Never click submit-button. Use guard for submit-button.",
        "Use this local memory:",
        json.dumps({
            "name": "李桂兰",
            "age": "70s",
            "phone": "138****2675",
            "area": "北京市朝阳区望京街道",
            "contact": "女儿 王敏",
            "course": "智能手机基础课",
        }, ensure_ascii=False),
        f"Screen observation: {json.dumps(observation, ensure_ascii=False)}",
    ])


def merge_with_fixed_workflow(model_plan):
    model_actions = model_plan.get("actions", [])
    merged_actions = []
    for fixed in FIXED_WORKFLOW["actions"]:
        candidate = find_matching_action(model_actions, fixed)
        if not candidate or not is_safe_action(candidate):
            merged_actions.append(fixed)
            continue
        merged_actions.append({
            **fixed,
            "type": candidate.get("type", fixed["type"]),
            "target": candidate.get("target", fixed["target"]),
            "reason": candidate.get("reason") if readable(candidate.get("reason")) else fixed["reason"],
        })

    summary = model_plan.get("summary")
    return {
        "summary": summary if readable(summary) else FIXED_WORKFLOW["summary"],
        "actions": merged_actions,
    }


def find_matching_action(actions, fixed):
    for action in actions:
        if action.get("type") == fixed["type"] and action.get("target") == fixed["target"]:
            return action
    return None


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
