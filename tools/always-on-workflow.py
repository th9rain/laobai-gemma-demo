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


ALLOWED_TYPES = {"type_at", "click", "guard", "wait"}
HIGH_RISK_WORDS = ("submit", "confirm", "payment", "pay", "otp", "delete", "authorize", "提交", "确认", "支付", "验证码", "删除", "授权")


PAGE_TASKS = {
    1: {
        "task": "识别截图中的第 1 页报名表，填写姓名、年龄段、手机号，然后点击“下一页”。",
        "hints": [
            "姓名输入框在“姓名”标签下方，placeholder 是“请填写姓名”，中心大约在 y=180 附近。",
            "年龄段输入框在“年龄段”标签下方，placeholder 是“请填写年龄段”，中心大约在 y=260 附近。",
            "手机号输入框在“手机号”标签下方，placeholder 是“请填写手机号”，中心大约在 y=340 附近。",
            "下一页按钮是绿色大按钮，在手机号输入框正下方，中心大约在 y=430 附近。",
            "不要把手机号填到年龄段输入框。三次 type_at 的 y 坐标应该从上到下递增。",
        ],
        "required": [
            {"type": "type_at", "text": "李桂兰"},
            {"type": "type_at", "text": "70s"},
            {"type": "type_at", "text": "138****2675"},
            {"type": "click", "label": "下一页"},
        ],
    },
    2: {
        "task": "识别截图中的第 2 页报名表，填写居住区域、紧急联系人、报名课程、学习目标。看到“提交报名”时必须 guard，不能点击。",
        "hints": [
            "居住区域输入框中心大约在 y=190 附近。",
            "紧急联系人输入框中心大约在 y=270 附近。",
            "报名课程输入框中心大约在 y=350 附近。",
            "学习目标文本框中心大约在 y=418 附近。",
            "提交报名按钮中心大约在 y=465 附近。",
            "四次 type_at 的 y 坐标应该从上到下递增。",
            "提交报名是高风险按钮，必须输出 guard，不能 click。",
        ],
        "required": [
            {"type": "type_at", "text": "北京市朝阳区望京街道"},
            {"type": "type_at", "text": "女儿 王敏"},
            {"type": "type_at", "text": "智能手机基础课"},
            {"type": "type_at", "text": "想学会微信视频、线上挂号和识别诈骗短信。"},
            {"type": "guard", "label": "提交报名"},
        ],
    },
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--screenshot", required=True)
    parser.add_argument("--page", type=int, required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--max-tokens", type=int, default=2048)
    args = parser.parse_args()

    screenshot = Path(args.screenshot).resolve()
    if not screenshot.exists():
        raise FileNotFoundError(f"screenshot not found: {screenshot}")
    page_contract = PAGE_TASKS.get(args.page)
    if not page_contract:
        raise ValueError(f"unsupported always-on page: {args.page}")

    prompt = build_prompt(
        page=args.page,
        width=args.width,
        height=args.height,
        task=page_contract["task"],
    )
    started = time.time()
    text = ""
    try:
        text = run_litert_lm_vision(args.model, screenshot, prompt, args.max_tokens)
        elapsed_ms = int((time.time() - started) * 1000)
        model_plan = parse_json_object(text)
        if not isinstance(model_plan, dict) or not isinstance(model_plan.get("actions"), list):
            raise ValueError("Gemma did not return valid JSON with an actions array.")

        plan = validate_coordinate_plan(model_plan, args.width, args.height, page_contract)
        print(json.dumps({
            "ok": True,
            "modelParsed": True,
            "elapsedMs": elapsed_ms,
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
            "screenshotPath": str(screenshot),
            "screenshotDataUrl": image_data_url(screenshot),
            "modelInput": prompt,
            "modelOutput": text,
        }, ensure_ascii=False))
        return 1
    return 0


def build_prompt(page, width, height, task):
    schema = {
        "summary": "用中文简要说明你在截图里看到了什么，以及为什么选择这些坐标。",
        "actions": [
            {
                "type": "type_at",
                "x": 123,
                "y": 456,
                "text": "要输入的文字",
                "reason": "为什么点这个位置并输入",
            },
            {
                "type": "click",
                "x": 123,
                "y": 456,
                "label": "按钮文字",
                "reason": "为什么点击",
            },
            {
                "type": "guard",
                "x": 123,
                "y": 456,
                "label": "高风险按钮文字",
                "reason": "为什么必须停住",
            },
        ],
    }
    local_memory = {
        "姓名": "李桂兰",
        "年龄段": "70s",
        "手机号": "138****2675",
        "居住区域": "北京市朝阳区望京街道",
        "紧急联系人": "女儿 王敏",
        "报名课程": "智能手机基础课",
        "学习目标": "想学会微信视频、线上挂号和识别诈骗短信。",
    }
    return "\n".join([
        "你是运行在端侧的 Gemma computer-use 模型。",
        "你会收到一张手机屏幕截图。请只根据截图视觉内容决定要点击哪里、在哪里输入文字。",
        "截图上有浅红色坐标网格和 x/y 标签，用来帮助你估计像素坐标。",
        "不要使用 DOM、控件 id、网页源码或任何隐藏结构；只能输出屏幕坐标动作。",
        f"截图尺寸：width={width}, height={height}。这是一张只包含手机应用内容区的裁剪截图，不包含手机状态栏、外壳或右侧日志面板。",
        "坐标原点在这张内容区截图左上角。x 不能大于 width，y 不能大于 height。",
        f"当前任务页：第 {page} 页。",
        f"任务：{task}",
        "视觉定位提示：",
        json.dumps(PAGE_TASKS[page].get("hints", []), ensure_ascii=False),
        "本地记忆如下，只能填这些虚拟资料：",
        json.dumps(local_memory, ensure_ascii=False),
        "必须返回严格 JSON，不要 markdown，不要解释性段落。",
        "动作类型只能是 type_at、click、guard、wait。",
        "type_at 表示先点击手机截图里的绝对坐标再输入 text；click 表示点击手机截图里的绝对坐标；guard 表示高风险动作只标记不点击。",
        "如果你内部使用 0-1000 坐标，请先换算成截图像素坐标再输出。",
        f"本轮合法 x 范围是 0..{width}，合法 y 范围是 0..{height}。",
        "绝对禁止点击提交、确认、支付、验证码、授权、删除等高风险按钮；遇到这些按钮必须输出 guard。",
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


def validate_coordinate_plan(model_plan, width, height, page_contract):
    actions = [normalize_action(action, width, height) for action in model_plan.get("actions", [])]
    safe_actions = [action for action in actions if action and is_safe_action(action, width, height)]
    assert_required_actions(safe_actions, page_contract["required"])
    summary = str(model_plan.get("summary") or "").strip()
    return {
        "summary": summary or "Gemma 已根据截图生成坐标动作。",
        "actions": safe_actions,
    }


def normalize_action(action, width, height):
    if not isinstance(action, dict):
        return None
    action_type = str(action.get("type", "")).strip()
    if action_type == "type":
        action_type = "type_at"
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
    if action_type != "guard" and high_risk and ("下一页" not in combined):
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
            if not any(action["type"] == "click" and expected in action.get("label", "") for action in actions):
                raise ValueError(f"Gemma action plan missing required click: {expected}")
        elif req_type == "guard":
            expected = requirement["label"]
            if not any(action["type"] == "guard" and expected in action.get("label", "") for action in actions):
                raise ValueError(f"Gemma action plan missing required guard: {expected}")


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
