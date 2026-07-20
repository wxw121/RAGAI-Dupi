from pathlib import Path
import re


def normalize(path: Path, root: Path, tree: str, english: bool) -> None:
    text = path.read_text(encoding="utf-8-sig")
    text = re.sub(r"(?ms)^<!-- language-switch -->\s*.*?(?=^# |\Z)", "", text)
    text = re.sub(r"(?m)^\[(?:中文|English)\].*\n?", "", text)
    rel = path.relative_to(root / "docs" / tree).as_posix()
    up = "../" * len(rel.split("/"))
    other = "zh-CN" if english else "en"
    target = f"{up}{other}/{rel}"
    switch = f"<!-- language-switch -->\n[中文]({target}) | **English**" if english else f"<!-- language-switch -->\n[English]({target})"
    lines = text.lstrip("\ufeff\n").splitlines()
    if lines and lines[0].startswith("# "):
        output = "\n".join([lines[0], "", switch, ""] + lines[1:])
    else:
        output = switch + "\n\n" + "\n".join(lines)
    path.write_text(output.rstrip() + "\n", encoding="utf-8-sig")


root = Path.cwd().resolve()
for tree in ("en", "zh-CN"):
    directory = root / "docs" / tree
    for path in directory.rglob("*.md"):
        normalize(path, root, tree, tree == "en")

readme = root / "README.md"
text = readme.read_text(encoding="utf-8-sig")
text = re.sub(r"(?ms)^<!-- language-switch -->\s*.*?(?=^# |\Z)", "", text)
text = re.sub(r"(?m)^\[(?:中文|English)\].*\n?", "", text)
lines = text.lstrip("\ufeff\n").splitlines()
readme.write_text("\n".join([lines[0], "", "<!-- language-switch -->", "[中文](README.zh-CN.md) | **English**", ""] + lines[1:]).rstrip() + "\n", encoding="utf-8-sig")
