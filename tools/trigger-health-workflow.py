import argparse
import base64
import contextlib
import json
import os
import re
import sys
import time
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")


ALLOWED_TYPES = {"click", "type_at", "guard", "wait"}
HIGH_RISK_WORDS = ("confirm", "payment", "pay", "otp", "delete", "authorize", "确认", "支付", "验证码", "删除", "授权")


STAGE_TASKS = {
    "home": {
        "task": "识别手机桌面，点击“京医通”App 图标。",
        "required": [{"type": "click", "label": "京医通"}],
        "hints": [
            "京医通图标是红橙色 App 图标，图标内有“京”字，位于屏幕下半部分的应用网格左侧。",
            "本轮只需要点击京医通图标，不要点击老白浮窗。",
        ],
    },
    "hospital": {
        "task": "在京医通挂号页面依次选择北京协和医院、消化内科、李明主任医师。",
        "required": [
            {"type": "click", "label": "北京协和医院"},
            {"type": "click", "label": "消化内科"},
            {"type": "click", "label": "李明 主任医师"},
        ],
        "hints": [
            "页面顶部是京医通导航栏。",
            "北京协和医院、消化内科、李明主任医师是列表前三行。",
            "每次 click 坐标应落在对应列表行内部。",
        ],
    },
    "time": {
        "task": "继续选择后天上午 10:00 的号源。",
        "required": [
            {"type": "click", "label": "后天 上午 10:00", "tokens": ["后天", "10"]},
        ],
        "hints": [
            "后天上午 10:00 是列表中的时间行。",
            "不要输出身份证号、完整手机号或真实病历。",
        ],
    },
    "patient": {
        "task": "只处理“就诊人”这一行：点击左侧有“人”字图标、标题为“就诊人”的列表行，并把就诊人设为李桂兰（70多岁，手机号 138****2675）。",
        "required": [
            {"type": "patient", "tokens": ["就诊人"]},
        ],
        "hints": [
            "就诊人是白色列表中的最后一行，左侧是灰绿色方块，里面有白色“人”字。",
            "请点击这一整行的中心，label 写“就诊人”。",
            "不要点击底部红色“确认挂号 / 支付 / 验证码”按钮，也不要点击安全边界说明文字。",
            "如果需要填写，请用 type_at 在就诊人行中心输入脱敏就诊人信息。",
            "只允许使用脱敏信息：李桂兰（70多岁，手机号 138****2675）。",
        ],
    },
    "guard": {
        "task": "识别确认挂号 / 支付 / 验证码按钮，输出 guard，不能点击。",
        "required": [{"type": "guard", "label": "确认挂号"}],
        "hints": [
            "确认挂号 / 支付 / 验证码按钮在底部工具栏。",
            "这是高风险动作，必须输出 guard，不能 click。",
        ],
    },
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--screenshot", required=True)
    parser.add_argument("--stage", required=True, choices=sorted(STAGE_TASKS))
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--max-tokens", type=int, default=2048)
    args = parser.parse_args()

    screenshot = Path(args.screenshot).resolve()
    if not screenshot.exists():
      raise FileNotFoundError(f"screenshot not found: {screenshot}")
    contract = STAGE_TASKS[args.stage]
    prompt = build_prompt(args.stage, args.width, args.height, contract)
    started = time.time()
    text = ""
    try:
        text = run_litert_lm_vision(args.model, screenshot, prompt, args.max_tokens)
        elapsed_ms = int((time.time() - started) * 1000)
        model_plan = parse_json_object(text)
        if not isinstance(model_plan, dict) or not isinstance(model_plan.get("actions"), list):
            raise ValueError("Gemma did not return valid JSON with an actions array.")
        plan = validate_coordinate_plan(model_plan, args.width, args.height, contract)
        print(json.dumps({
            "ok": True,
            "modelParsed": True,
            "elapsedMs": elapsed_ms,
            "stage": args.stage,
            "screenshotPath": str(screenshot),
            "screenshotDataUrl": image_data_url(screenshot),
            "modelInput": prompt,
            "modelOutput": text,
            "plan": plan,
        }, ensure_ascii=False))
    except Exception as exc:
        print(json.dumps({
            "ok": False,
            "error": str(exc),
            "stage": args.stage,
            "screenshotPath": str(screenshot),
            "screenshotDataUrl": image_data_url(screenshot),
            "modelInput": prompt,
            "modelOutput": text,
        }, ensure_ascii=False))
        return 1
    return 0


def build_prompt(stage, width, height, contract):
    appointment = {
        "医院": "北京协和医院",
        "科室": "消化内科",
        "医生": "李明 主任医师",
        "时间": "后天 上午 10:00",
        "就诊人": "李桂兰（70多岁，手机号 138****2675）",
    }
    schema = {
        "summary": "用中文简要说明你看到了什么，以及为什么选择这些坐标。",
        "actions": [
            {"type": "click", "x": 123, "y": 456, "label": "目标文字", "reason": "为什么点击"},
            {"type": "type_at", "x": 123, "y": 456, "text": "要输入的文字", "label": "目标说明", "reason": "为什么输入"},
            {"type": "guard", "x": 123, "y": 456, "label": "高风险按钮文字", "reason": "为什么必须停住"},
        ],
    }
    return "\n".join([
        "你是运行在端侧的 Gemma computer-use 模型。",
        "你会收到一张手机屏幕截图。请只根据截图视觉内容决定要点击哪里、在哪里输入文字。",
        "不要使用 DOM、控件 id、网页源码或隐藏结构；只能输出屏幕坐标动作。",
        f"截图尺寸：width={width}, height={height}。坐标原点在截图左上角。",
        f"当前阶段：{stage}",
        f"本轮任务：{contract['task']}",
        "云端 Planner 已经给出脱敏挂号计划：",
        json.dumps(appointment, ensure_ascii=False),
        "视觉定位提示：",
        json.dumps(contract.get("hints", []), ensure_ascii=False),
        "隐私和安全边界：不得输出身份证号、完整手机号、验证码、支付信息或完整病历；确认挂号、支付、验证码必须 guard。",
        "动作类型只能是 click、type_at、guard、wait。",
        "如果你内部使用 0-1000 坐标，请先换算成截图像素坐标再输出。",
        f"合法 x 范围是 0..{width}，合法 y 范围是 0..{height}。",
        "必须返回严格 JSON，不要 markdown，不要解释性段落。",
        "JSON schema example:",
        json.dumps(schema, ensure_ascii=False),
    ])


def run_litert_lm_vision(model_path, screenshot_path, prompt, max_tokens):
    os.environ.setdefault("GLOG_minloglevel", "3")
    with suppress_native_stderr():
        try:
            from litert_lm import engine
            from litert_lm import interfaces
        except Exception as exc:
            raise RuntimeError(f"litert-lm import failed: {exc}") from exc

        sampler = interfaces.SamplerConfig(temperature=0.0, top_k=1)
        llm = engine.Engine(
            model_path,
            backend=interfaces.Backend.CPU(),
            vision_backend=interfaces.Backend.CPU(),
            max_num_tokens=max_tokens,
        )
        try:
            conversation = llm.create_conversation(sampler_config=sampler)
            try:
                response = conversation.send_message({
                    "role": "user",
                    "content": [
                        {"type": "image", "path": str(screenshot_path)},
                        {"type": "text", "text": prompt},
                    ],
                })
            finally:
                conversation.close()
        finally:
            llm.close()
    return response_to_text(response)


@contextlib.contextmanager
def suppress_native_stderr():
    original_stderr_fd = os.dup(2)
    try:
        with open(os.devnull, "w") as devnull:
            os.dup2(devnull.fileno(), 2)
            yield
    finally:
        os.dup2(original_stderr_fd, 2)
        os.close(original_stderr_fd)


def response_to_text(response):
    content = response.get("content", "") if isinstance(response, dict) else response
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                if isinstance(item.get("text"), str):
                    parts.append(item["text"])
                elif isinstance(item.get("content"), str):
                    parts.append(item["content"])
        return "\n".join(parts)
    if isinstance(response, dict) and "text" in response:
        return str(response["text"])
    return json.dumps(response, ensure_ascii=False)


def validate_coordinate_plan(model_plan, width, height, contract):
    actions = [normalize_action(action, width, height) for action in model_plan.get("actions", [])]
    safe_actions = [action for action in actions if action and is_safe_action(action, width, height)]
    assert_required_actions(safe_actions, contract["required"])
    summary = str(model_plan.get("summary") or "").strip()
    return {
        "summary": summary or "Gemma 已根据截图生成 Trigger 坐标动作。",
        "actions": safe_actions,
    }


def normalize_action(action, width, height):
    if not isinstance(action, dict):
        return None
    action_type = str(action.get("type", "")).strip()
    if action_type == "tap":
        action_type = "click"
    raw_x = to_number(action.get("x"))
    raw_y = to_number(action.get("y"))
    x, x_note = normalize_coordinate(raw_x, width)
    y, y_note = normalize_coordinate(raw_y, height)
    normalized = {
        "type": action_type,
        "x": x,
        "y": y,
        "rawX": raw_x,
        "rawY": raw_y,
        "text": "" if action.get("text") is None else str(action.get("text")),
        "label": "" if action.get("label") is None else str(action.get("label")),
        "reason": "" if action.get("reason") is None else str(action.get("reason")),
    }
    notes = [note for note in (x_note, y_note) if note]
    if notes:
        normalized["coordinateNote"] = "; ".join(notes)
    if action_type == "wait":
        normalized["ms"] = int(to_number(action.get("ms")) or 600)
    return normalized


def is_safe_action(action, width, height):
    action_type = action["type"]
    if action_type not in ALLOWED_TYPES:
        return False
    if action_type != "wait":
        if action["x"] is None or action["y"] is None:
            return False
        if not (0 <= action["x"] <= width and 0 <= action["y"] <= height):
            return False
    combined = f"{action.get('label', '')} {action.get('text', '')} {action.get('reason', '')}".lower()
    high_risk = any(word.lower() in combined for word in HIGH_RISK_WORDS)
    if action_type != "guard" and high_risk:
        return False
    if action_type == "type_at" and not action.get("text", "").strip():
        return False
    return True


def assert_required_actions(actions, required):
    for requirement in required:
        req_type = requirement["type"]
        if req_type == "type_at":
            expected = requirement["text"]
            if not any(action["type"] == "type_at" and expected in action.get("text", "") for action in actions):
                raise ValueError(f"Gemma action plan missing required text input: {expected}")
        elif req_type == "click":
            expected = requirement["label"]
            tokens = requirement.get("tokens")
            if tokens:
                matched = any(
                    action["type"] == "click" and text_matches_tokens(action_text(action), tokens)
                    for action in actions
                )
            else:
                matched = any(action["type"] == "click" and expected in action_text(action) for action in actions)
            if not matched:
                raise ValueError(f"Gemma action plan missing required click: {expected}")
        elif req_type == "guard":
            expected = requirement["label"]
            if not any(action["type"] == "guard" and expected in action_text(action) for action in actions):
                raise ValueError(f"Gemma action plan missing required guard: {expected}")
        elif req_type == "patient":
            tokens = requirement.get("tokens", [])
            if not any(
                action["type"] in {"click", "type_at"} and (
                    text_matches_tokens(action_text(action), tokens)
                    or "李桂兰" in action.get("text", "")
                    or "人" in action.get("label", "")
                )
                for action in actions
            ):
                raise ValueError("Gemma action plan missing required patient selection/fill.")


def action_text(action):
    return f"{action.get('label', '')} {action.get('text', '')} {action.get('reason', '')}"


def text_matches_tokens(text, tokens):
    normalized = re.sub(r"\s+", "", str(text or ""))
    return all(str(token).replace(" ", "") in normalized for token in tokens)


def to_number(value):
    try:
        return float(value)
    except Exception:
        return None


def normalize_coordinate(value, upper):
    if value is None:
        return None, ""
    if 0 <= value <= upper:
        return value, ""
    if upper < value <= 1000:
        normalized = round(value / 1000 * upper, 2)
        return normalized, f"{value} normalized from 0-1000 to {normalized}"
    return value, ""


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


def image_data_url(path):
    data = Path(path).read_bytes()
    return "data:image/png;base64," + base64.b64encode(data).decode("ascii")


if __name__ == "__main__":
    raise SystemExit(main())
