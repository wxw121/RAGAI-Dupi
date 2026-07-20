"""Repair Markdown syntax commonly damaged by machine translation.

The repair is intentionally conservative: it only changes line prefixes,
paired technical quotes, and clearly unclosed Markdown delimiters. Fenced code
blocks are copied byte-for-byte.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path


HEADING_WITHOUT_SPACE = re.compile(r"^(#{1,6})([^ #].*)$")
BROKEN_BULLET = re.compile(r"^[-+](?![-+*\s])(.*)$")
BROKEN_EM_DASH_BULLET = re.compile(r"^[—–]{1,2}\s*(\S.*)$")
BROKEN_CHECKBOX = re.compile(r"^(\s*)-\[([ xX]?)\]\s*(.*)$")
BROKEN_BLOCKQUOTE = re.compile(r"^(\s*)[bB]>\s*(.*)$")
PLAIN_FIELD = re.compile(
    r"^(背景|决策|理由|影响范围|状态|日期|结论|验证|风险|范围)\s*[:：]\s*(.*)$"
)
ENGLISH_FIELD = re.compile(
    r"^\*\*(Goal|Architecture|Tech Stack|Files|Status|Date|Decision|Context|Rationale|Scope)\s*[:：]?\*\*\s*(.*)$"
)
FIELD_TRANSLATIONS = {
    "Goal": "目标",
    "Architecture": "架构",
    "Tech Stack": "技术栈",
    "Files": "文件",
    "Status": "状态",
    "Date": "日期",
    "Decision": "决策",
    "Context": "背景",
    "Rationale": "理由",
    "Scope": "范围",
}

# Youdao sometimes converts inline-code backticks into Chinese smart quotes.
# Requiring the correct opening/closing quote direction and a technical shape
# avoids touching ordinary Chinese quotations.
TECHNICAL_SMART_QUOTES = re.compile(
    r"‘\s*([^‘’\n]{0,160}(?:/|\\|\.|_|[A-Z][A-Z0-9_]{1,}|\b(?:GET|POST|PUT|PATCH|DELETE)\b)[^‘’\n]{0,160})\s*’"
)

def repair_line(line: str) -> str:
    match = HEADING_WITHOUT_SPACE.match(line)
    if match:
        line = f"{match.group(1)} {match.group(2)}"

    match = BROKEN_CHECKBOX.match(line)
    if match:
        checked = "x" if match.group(2).lower() == "x" else " "
        line = f"{match.group(1)}- [{checked}] {match.group(3)}"
    else:
        match = BROKEN_BULLET.match(line)
        if match:
            line = f"- {match.group(1)}"
        else:
            match = BROKEN_EM_DASH_BULLET.match(line)
            if match:
                line = f"- {match.group(1)}"

    match = BROKEN_BLOCKQUOTE.match(line)
    if match:
        line = f"{match.group(1)}> {match.group(2)}"

    match = PLAIN_FIELD.match(line)
    if match:
        line = f"**{match.group(1)}：** {match.group(2)}".rstrip()

    match = ENGLISH_FIELD.match(line)
    if match:
        line = f"**{FIELD_TRANSLATIONS[match.group(1)]}：** {match.group(2)}".rstrip()

    line = TECHNICAL_SMART_QUOTES.sub(
        lambda match: f"`{match.group(1).strip()}`", line
    )

    # Translation occasionally drops the closing marker from task labels.
    if re.match(r"^\s*- \[[ xX]\] \*\*", line):
        marker_count = len(re.findall(r"(?<!\\)\*\*", line))
        if marker_count % 2:
            line += "**"

    return line.rstrip()


def repair_line_until_stable(line: str) -> str:
    """Resolve adjacent damaged quote pairs without requiring repeated runs."""
    repaired = line
    for _ in range(8):
        next_value = repair_line(repaired)
        if next_value == repaired:
            return repaired
        repaired = next_value
    return repaired


def repair_file(path: Path, *, check: bool) -> tuple[bool, int]:
    original = path.read_text(encoding="utf-8-sig")
    output: list[str] = []
    in_fence = False
    changed_lines = 0

    for line in original.splitlines():
        if re.match(r"^\s*(```|~~~)", line):
            in_fence = not in_fence
            repaired = line.rstrip()
        elif in_fence:
            repaired = line
        else:
            repaired = repair_line_until_stable(line)
        changed_lines += repaired != line
        output.append(repaired)

    repaired_text = "\n".join(output).rstrip() + "\n"
    changed = repaired_text != original.lstrip("\ufeff")
    if changed and not check:
        path.write_text(repaired_text, encoding="utf-8")
    return changed, changed_lines


def markdown_files(root: Path) -> list[Path]:
    files = [root / "README.zh-CN.md"]
    files.extend(sorted((root / "docs" / "zh-CN").rglob("*.md")))
    return [path for path in files if path.exists()]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    changed_files = 0
    changed_lines = 0
    for path in markdown_files(args.root.resolve()):
        changed, count = repair_file(path, check=args.check)
        if changed:
            changed_files += 1
            changed_lines += count
            print(path.relative_to(args.root.resolve()))

    mode = "would change" if args.check else "changed"
    print(f"{mode}: {changed_files} files, {changed_lines} lines")
    if args.check and changed_files:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
