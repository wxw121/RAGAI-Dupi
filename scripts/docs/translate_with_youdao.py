"""Translate repository Markdown locally with Youdao's text translation API.

The script never stores credentials in files. Set YOUDAO_APP_KEY and
YOUDAO_APP_SECRET in the shell before running it. Benchmark fixtures are
excluded automatically.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import time
import uuid
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen


API_URL = "https://openapi.youdao.com/v2/api"
ERROR_HINTS = {
    "108": "应用 ID 无效：请确认 YOUDAO_APP_KEY 来自有道控制台的应用 ID，并与 YOUDAO_APP_SECRET 属于同一应用；同时确认应用已绑定自然语言翻译服务。",
    "202": "签名校验失败：请确认 appKey、appSecret、系统时间和 UTF-8 编码没有被修改。",
    "401": "账户欠费：请在有道控制台充值或更换可用账户。",
    "411": "访问频率受限：增大 --delay，或等待后重试。",
    "412": "长请求过于频繁：增大 --delay，或等待后重试。",
}


class YoudaoError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def truncate(text: str) -> str:
    return text if len(text) <= 20 else text[:10] + str(len(text)) + text[-10:]


def youdao_translate(text: str, source: str, target: str, retries: int = 5) -> str:
    app_key = os.environ["YOUDAO_APP_KEY"]
    app_secret = os.environ["YOUDAO_APP_SECRET"]
    salt = str(uuid.uuid4())
    curtime = str(int(time.time()))
    sign_text = app_key + truncate(text) + salt + curtime + app_secret
    sign = hashlib.sha256(sign_text.encode("utf-8")).hexdigest()
    payload = urlencode(
        {
            "q": text,
            "from": source,
            "to": target,
            "appKey": app_key,
            "salt": salt,
            "sign": sign,
            "signType": "v3",
            "curtime": curtime,
        }
    ).encode("utf-8")
    request = Request(
        API_URL,
        data=payload,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    for attempt in range(retries + 1):
        try:
            with urlopen(request, timeout=60) as response:
                result = json.loads(response.read().decode("utf-8"))
            break
        except Exception:
            if attempt >= retries:
                raise
            time.sleep(2 ** attempt)
    if result.get("errorCode") in {"411", "412", 411, 412}:
        if retries <= 0:
            code = str(result.get("errorCode"))
            raise YoudaoError(code, f"Youdao error {code}: {ERROR_HINTS[code]} requestId={result.get('requestId')}")
        time.sleep(2 ** min(5, 5 - retries + 1))
        return youdao_translate(text, source, target, retries - 1)
    if result.get("errorCode") != "0":
        code = str(result.get("errorCode"))
        hint = ERROR_HINTS.get(code, "请参考有道 API 错误码文档。")
        raise YoudaoError(code, f"Youdao error {code}: {hint} requestId={result.get('requestId')}")
    if "translateResults" in result:
        return "\n".join(item["translation"] for item in result["translateResults"])
    return result["translation"][0]


def blocks(markdown: str):
    """Yield (is_code, block) while preserving fenced code blocks."""
    current: list[str] = []
    in_code = False
    for line in markdown.splitlines():
        if line.startswith("```"):
            if current:
                yield in_code, "\n".join(current)
                current = []
            in_code = not in_code
            yield True, line
        elif not in_code and not line.strip():
            if current:
                yield False, "\n".join(current)
                current = []
        else:
            current.append(line)
    if current:
        yield in_code, "\n".join(current)


def should_keep(block: str) -> bool:
    return (
        not block.strip()
        or re.search(r"\[中文\].*English|\*\*中文\*\*.*English", block) is not None
        or block.lstrip().startswith("<!-- language-switch")
    )


def protect_markdown(text: str) -> tuple[str, dict[str, str]]:
    protected: dict[str, str] = {}

    def save(match: re.Match[str]) -> str:
        token = f"__DU_PI_TOKEN_{len(protected):04d}__"
        protected[token] = match.group(0)
        return token

    # Protect inline code, link destinations, URLs, and table separators.
    text = re.sub(r"`[^`\n]+`", save, text)
    text = re.sub(r"(?<=\])\([^\n)]*\)", save, text)
    text = re.sub(r"https?://[^\s)]+", save, text)
    text = text.replace("|", " __DU_PI_PIPE__ ")
    return text, protected


def restore_markdown(text: str, protected: dict[str, str]) -> str:
    text = text.replace(" __DU_PI_PIPE__ ", "|")
    for token, original in protected.items():
        text = text.replace(token, original)
    return text


def add_switch(text: str, link: str, english: bool) -> str:
    if "<!-- language-switch -->" in text:
        return text
    switch = f"<!-- language-switch -->\n[English]({link})" if not english else f"<!-- language-switch -->\n[中文]({link}) | **English**"
    lines = text.splitlines()
    if lines and lines[0].startswith("# "):
        return "\n".join([lines[0], "", switch, ""] + lines[1:]).rstrip() + "\n"
    return switch + "\n\n" + text.lstrip()


def translate_file(source: Path, target: Path, source_lang: str, target_lang: str, cache: dict[str, str], delay: float, switch_link: str, only_cjk: bool = False) -> None:
    translated: list[str] = []
    for is_code, block in blocks(source.read_text(encoding="utf-8-sig")):
        if is_code or should_keep(block) or (only_cjk and not re.search(r"[\u3400-\u9fff]", block)):
            translated.append(block)
            continue
        key = hashlib.sha256(block.encode("utf-8")).hexdigest()
        if key not in cache:
            cache[key] = translate_resilient(block, source_lang, target_lang, delay)
            time.sleep(delay)
        translated.append(cache[key])
    target.parent.mkdir(parents=True, exist_ok=True)
    output = "\n\n".join(translated).rstrip() + "\n"
    target.write_text(add_switch(output, switch_link, english=False), encoding="utf-8-sig")


def translate_resilient(block: str, source_lang: str, target_lang: str, delay: float) -> str:
    prepared, protected = protect_markdown(block)
    try:
        return restore_markdown(youdao_translate(prepared, source_lang, target_lang), protected)
    except YoudaoError as exc:
        if exc.code not in {"103", "304"} or len(prepared) < 200:
            raise
        lines = prepared.splitlines()
        midpoint = max(1, len(lines) // 2)
        left = translate_resilient("\n".join(lines[:midpoint]), source_lang, target_lang, delay)
        time.sleep(delay)
        right = translate_resilient("\n".join(lines[midpoint:]), source_lang, target_lang, delay)
        return left + "\n" + right


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, default=None, help="Output directory; defaults to docs/zh-CN")
    parser.add_argument("--source", default="en")
    parser.add_argument("--target", default="zh-CHS")
    parser.add_argument("--delay", type=float, default=1.5, help="Seconds between requests; increase if Youdao returns 411/412")
    parser.add_argument("--cache", type=Path, default=Path(".youdao-translation-cache.json"))
    parser.add_argument("--dry-run", action="store_true", help="List files without calling the API")
    parser.add_argument("--write-english-tree", action="store_true", help="Copy source Markdown into docs/en before translating")
    parser.add_argument("--write-stubs", action="store_true", help="Replace legacy docs paths with links to both language trees")
    parser.add_argument("--source-tree", type=Path, default=None, help="Read Markdown from this directory instead of README.md and docs/")
    parser.add_argument("--in-place", action="store_true", help="Write translations back to the source files")
    parser.add_argument("--only-cjk", action="store_true", help="Translate only blocks containing CJK characters")
    args = parser.parse_args()

    if not args.dry_run and (not os.environ.get("YOUDAO_APP_KEY") or not os.environ.get("YOUDAO_APP_SECRET")):
        raise SystemExit("Set YOUDAO_APP_KEY and YOUDAO_APP_SECRET first")

    root = args.root.resolve()
    output = (args.output or root / "docs" / "zh-CN").resolve()
    cache_path = args.cache if args.cache.is_absolute() else root / args.cache
    cache = json.loads(cache_path.read_text(encoding="utf-8")) if cache_path.exists() else {}
    if args.source_tree:
        source_tree = (root / args.source_tree).resolve()
        sources = sorted(source_tree.rglob("*.md"))
    else:
        sources = [root / "README.md"] + sorted(
            p for p in (root / "docs").rglob("*.md") if "benchmarks" not in p.parts and "zh-CN" not in p.parts and "en" not in p.parts
        )

    for source in sources:
        if args.source_tree:
            relative = source.relative_to((root / args.source_tree).resolve())
            target = source if args.in_place else output / relative
        else:
            relative = Path("README.md") if source.name == "README.md" and source.parent == root else source.relative_to(root / "docs")
            target = root / "README.zh-CN.md" if relative == Path("README.md") else output / relative
        if args.write_english_tree and not args.source_tree:
            english_target = root / "docs" / "en" / relative
            english_target.parent.mkdir(parents=True, exist_ok=True)
            english_link = "../../README.zh-CN.md" if relative == Path("README.md") else ("../" * len(relative.parts)) + "zh-CN/" + relative.as_posix()
            english_target.write_text(add_switch(source.read_text(encoding="utf-8-sig"), english_link, english=True), encoding="utf-8-sig")
        switch_link = "README.md" if relative == Path("README.md") else ("../" * len(relative.parts)) + "en/" + relative.as_posix()
        print(f"{source.relative_to(root)} -> {target.relative_to(root)}")
        if not args.dry_run:
            translate_file(source, target, args.source, args.target, cache, args.delay, switch_link, args.only_cjk)
            cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")
        if args.write_stubs and relative != Path("README.md"):
            stub = f"# Moved: {relative.name}\n\nThis path is retained for compatibility.\n\n- [English](en/{relative.as_posix()})\n- [中文](zh-CN/{relative.as_posix()})\n"
            source.write_text(stub, encoding="utf-8")


if __name__ == "__main__":
    main()
