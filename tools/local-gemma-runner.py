import argparse
import json
import re
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--prompt-file", required=True)
    parser.add_argument("--max-tokens", type=int, default=4096)
    args = parser.parse_args()

    with open(args.prompt_file, "r", encoding="utf-8") as f:
        prompt = f.read()

    text = run_litert_lm(args.model, prompt, args.max_tokens)
    parsed = parse_json_object(text)
    if parsed is None:
        print(json.dumps({"ok": False, "text": text[:500]}, ensure_ascii=False))
        return 2

    print(json.dumps({"ok": True, "plan": parsed}, ensure_ascii=False))
    return 0


def run_litert_lm(model_path, prompt, max_tokens):
    try:
        from litert_lm import engine
        from litert_lm import interfaces
    except Exception as exc:
        raise RuntimeError(f"litert-lm import failed: {exc}") from exc

    sampler = interfaces.SamplerConfig(temperature=0.0, top_k=1)
    llm = engine.Engine(model_path, backend=interfaces.Backend.CPU(), max_num_tokens=max_tokens)
    try:
        conversation = llm.create_conversation(sampler_config=sampler)
        try:
            response = conversation.send_message(prompt)
        finally:
            conversation.close()
    finally:
        llm.close()

    if isinstance(response, dict):
        return response_to_text(response)
    return str(response)


def response_to_text(response):
    content = response.get("content", "")
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
    if "text" in response:
        return str(response["text"])
    return json.dumps(response, ensure_ascii=False)


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
