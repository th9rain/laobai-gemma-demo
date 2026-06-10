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


PAGE_WORKFLOWS = {
    1: {
        "summary": "Always-on 本地 workflow 已识别第 1 页基础信息表单，准备填写并进入下一页。",
        "actions": [
            {"type": "type", "target": "name", "value": "李桂兰", "reason": "填写端侧本地记忆中的姓名"},
            {"type": "type", "target": "age", "value": "70s", "reason": "填写端侧本地记忆中的年龄段"},
            {"type": "type", "target": "phone", "value": "138****2675", "reason": "只填写脱敏手机号"},
            {"type": "click", "target": "next-button", "value": "", "reason": "第一页低风险字段填写完成，进入第二页继续观察"},
        ],
    },
    2: {
        "summary": "Always-on 本地 workflow 已识别第 2 页课程信息，准备填写并停在提交前。",
        "actions": [
            {"type": "type", "target": "area", "value": "北京市朝阳区望京街道", "reason": "填写端侧本地记忆中的居住区域"},
            {"type": "type", "target": "contact", "value": "女儿 王敏", "reason": "填写端侧本地记忆中的紧急联系人"},
            {"type": "select", "target": "course", "value": "智能手机基础课", "reason": "选择端侧本地记忆中的偏好课程"},
            {"type": "type", "target": "learning-goal", "value": "想学会微信视频、线上挂号和识别诈骗短信。", "reason": "填写更长的学习目标，展示模型处理多行文本输入"},
            {"type": "guard", "target": "submit-button", "value": "", "reason": "提交报名属于高风险动作，必须停在提交前"},
        ],
    },
}

FIXED_WORKFLOW = {
    "summary": "Always-on 本地 workflow 已识别社区报名表，准备填写常用信息并停在提交前。",
    "actions": [
        {"type": "type", "target": "name", "value": "李桂兰", "reason": "填写端侧本地记忆中的姓名"},
        {"type": "type", "target": "age", "value": "70s", "reason": "填写端侧本地记忆中的年龄段"},
        {"type": "type", "target": "phone", "value": "138****2675", "reason": "只填写脱敏手机号"},
        {"type": "type", "target": "area", "value": "北京市朝阳区望京街道", "reason": "填写端侧本地记忆中的居住区域"},
        {"type": "type", "target": "contact", "value": "女儿 王敏", "reason": "填写端侧本地记忆中的紧急联系人"},
        {"type": "select", "target": "course", "value": "智能手机基础课", "reason": "选择端侧本地记忆中的偏好课程"},
        {"type": "type", "target": "learning-goal", "value": "想学会微信视频、线上挂号和识别诈骗短信。", "reason": "填写学习目标"},
        {"type": "guard", "target": "submit-button", "value": "", "reason": "提交报名属于高风险动作，必须停在提交前"},
    ],
}

ALLOWED_TYPES = {"click", "type", "select", "wait", "guard"}
HIGH_RISK_TARGETS = ("submit", "confirm", "payment", "otp", "delete", "authorize")

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
    plan = merge_with_fixed_workflow(model_plan if model_parsed else {}, observation)

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
    page_contract = PAGE_WORKFLOWS.get(page, FIXED_WORKFLOW)
    return "\n\n".join([
        "You are Gemma 4B Computer-Use running fully on device.",
        "Task: multi-step Always-on form filling workflow for a senior assistance demo.",
        "No cloud planner is available. Do not ask for cloud help.",
        "Return ONLY strict JSON. Do not include markdown.",
        "Schema: {\"summary\":\"short Chinese status\",\"actions\":[{\"type\":\"click|type|select|guard\",\"target\":\"element id\",\"value\":\"value\",\"reason\":\"Chinese reason\"}]}",
        "You must reason from the current visible page only. The user will observe a new page after any next-page click.",
        f"Current page number: {page}",
        "Use exactly these visible element ids in order:",
        json.dumps([item["target"] for item in page_contract["actions"]], ensure_ascii=False),
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


def merge_with_fixed_workflow(model_plan, observation):
    fixed_workflow = PAGE_WORKFLOWS.get(current_page(observation), FIXED_WORKFLOW)
    model_actions = model_plan.get("actions", [])
    merged_actions = []
    for fixed in fixed_workflow["actions"]:
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
        "summary": summary if readable(summary) else fixed_workflow["summary"],
        "actions": merged_actions,
    }


def current_page(observation):
    try:
        return int(observation.get("currentPage") or observation.get("page") or 1)
    except Exception:
        return 1


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
