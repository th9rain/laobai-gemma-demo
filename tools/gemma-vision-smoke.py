import argparse
import contextlib
import json
import os
import re
import sys
import time
from pathlib import Path


ALLOWED = {"tap", "type_at", "select", "scroll", "wait", "guard"}


def main():
    parser = argparse.ArgumentParser(description="Run one real local Gemma visual-action round.")
    parser.add_argument("--model", required=True)
    parser.add_argument("--screenshot", required=True)
    parser.add_argument("--task", required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--max-tokens", type=int, default=512)
    args = parser.parse_args()

    screenshot = Path(args.screenshot).resolve()
    if not screenshot.is_file():
        raise FileNotFoundError(screenshot)

    prompt = build_prompt(args.task, args.width, args.height)
    started = time.time()
    output = run_model(args.model, screenshot, prompt, args.max_tokens)
    parsed = parse_object(output)
    try:
        actions = validate(parsed, args.width, args.height)
    except Exception as exc:
        print(json.dumps({
            "ok": False,
            "error": str(exc),
            "elapsedMs": round((time.time() - started) * 1000),
            "rawOutput": output,
            "parsed": parsed,
        }, ensure_ascii=False))
        return 2
    result = {
        "ok": True,
        "model": str(Path(args.model).resolve()),
        "screenshot": str(screenshot),
        "elapsedMs": round((time.time() - started) * 1000),
        "rawOutput": output,
        "actions": actions,
    }
    print(json.dumps(result, ensure_ascii=False))
    return 0


def build_prompt(task, width, height):
    return f"""你是运行在安卓端侧的视觉操作模型。你只能根据当前截图决定下一步，不知道 DOM。
截图尺寸为 {width}x{height}，坐标原点为左上角。
当前任务：{task}
只返回一个严格 JSON 对象，不要 Markdown：
{{"summary":"看到的当前页面","actions":[{{"type":"tap|type_at|select|scroll|wait|guard","x":100,"y":200,"text":"可选","value":"可选","label":"可选","x1":195,"y1":690,"x2":195,"y2":250,"durationMs":500}}]}}
只规划当前屏幕能看到的一小步，最多 3 个动作。页面过长且目标不在屏幕内时输出 scroll；滑动或跳转后必须重新截图。
截图可能有每 50 像素一格的红色坐标网格以及 x/y 标签。x 必须在 0..{width}，y 必须在 0..{height}，不要输出 0..1000 的归一化坐标。
如果任务要求填写字段，必须使用 type_at 并把填写内容放在 text，不要只输出 tap。
提交报名、确认挂号、支付、验证码、授权和删除必须输出 guard，绝不能 tap。
"""


def run_model(model_path, screenshot_path, prompt, max_tokens):
    os.environ.setdefault("GLOG_minloglevel", "3")
    stderr_context = contextlib.nullcontext() if os.environ.get("LAOBAI_LITERT_DEBUG") == "1" else suppress_native_stderr()
    with stderr_context:
        from litert_lm import engine, interfaces

        model = engine.Engine(
            model_path,
            backend=interfaces.Backend.CPU(),
            vision_backend=interfaces.Backend.CPU(),
            max_num_tokens=max_tokens,
        )
        try:
            conversation = model.create_conversation(
                sampler_config=interfaces.SamplerConfig(temperature=0.0, top_k=1)
            )
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
            model.close()
    return response_text(response)


def response_text(response):
    if isinstance(response, str):
        return response
    if isinstance(response, dict):
        content = response.get("content", response.get("text", ""))
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            return "\n".join(
                item if isinstance(item, str) else str(item.get("text", item.get("content", "")))
                for item in content
            )
    return str(response)


def parse_object(text):
    raw = str(text).strip()
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", raw, re.DOTALL)
        if not match:
            raise ValueError("model did not return a JSON object")
        return json.loads(match.group(0))


def validate(parsed, width, height):
    if not isinstance(parsed, dict) or not isinstance(parsed.get("actions"), list):
        raise ValueError("model JSON has no actions array")
    result = []
    for action in parsed["actions"][:3]:
        if not isinstance(action, dict) or action.get("type") not in ALLOWED:
            continue
        kind = action["type"]
        if kind in {"tap", "type_at", "select", "guard"}:
            x, y = action.get("x"), action.get("y")
            if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
                continue
            if not (0 <= x <= width and 0 <= y <= height):
                continue
        if kind == "scroll":
            keys = ("x1", "y1", "x2", "y2")
            if not all(isinstance(action.get(key), (int, float)) for key in keys):
                continue
        combined = " ".join(str(action.get(key, "")) for key in ("label", "text", "value"))
        risky = any(word in combined.lower() for word in ("提交", "确认挂号", "支付", "验证码", "授权", "删除", "submit", "payment"))
        if risky and kind != "guard":
            continue
        result.append(action)
    if not result:
        raise ValueError("model returned no valid safe action")
    return result


@contextlib.contextmanager
def suppress_native_stderr():
    original = os.dup(2)
    try:
        with open(os.devnull, "w") as sink:
            os.dup2(sink.fileno(), 2)
            yield
    finally:
        os.dup2(original, 2)
        os.close(original)


if __name__ == "__main__":
    try:
        sys.exit(main() or 0)
    except Exception as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False))
        sys.exit(1)
