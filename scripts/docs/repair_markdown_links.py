"""Repair repository-relative Markdown links after moving docs into language trees."""

from __future__ import annotations

import os
import re
from pathlib import Path


LINK = re.compile(r"(?P<prefix>\[[^\]]+\]\()(?P<target>[^)]+)(?P<suffix>\))")


def repair_file(root: Path, file: Path, tree: str) -> int:
    text = file.read_text(encoding="utf-8-sig")
    text = re.sub(r"\]（([^）]+)）", r"](\1)", text)
    if tree == "README":
        original = root / "README.md"
    else:
        original = root / "docs" / file.relative_to(root / "docs" / tree)
    original_dir = original.parent
    changed = 0

    def replace(match: re.Match[str]) -> str:
        nonlocal changed
        target = match.group("target")
        if target.startswith(("http://", "https://", "mailto:", "#")):
            return match.group(0)
        path, fragment = (target.split("#", 1) + [""])[:2]
        if not path or path.startswith("/"):
            return match.group(0)
        candidate = (original_dir / path).resolve()
        if not candidate.exists():
            root_candidate = (root / path).resolve()
            if root_candidate.exists():
                candidate = root_candidate
        try:
            candidate.relative_to(root.resolve())
        except ValueError:
            return match.group(0)
        if not candidate.exists():
            return match.group(0)
        rel = os.path.relpath(candidate, file.parent).replace(os.sep, "/")
        new_target = rel + ("#" + fragment if fragment else "")
        if new_target != target:
            changed += 1
        return match.group("prefix") + new_target + match.group("suffix")

    repaired = LINK.sub(replace, text)
    file.write_text(repaired, encoding="utf-8-sig")
    return changed


def main() -> None:
    root = Path.cwd().resolve()
    count = 0
    for tree in ("en", "zh-CN"):
        directory = root / "docs" / tree
        if not directory.exists():
            continue
        for file in directory.rglob("*.md"):
            count += repair_file(root, file, tree)
    if (root / "README.zh-CN.md").exists():
        count += repair_file(root, root / "README.zh-CN.md", "README")
    print(f"repaired_links={count}")


if __name__ == "__main__":
    main()
