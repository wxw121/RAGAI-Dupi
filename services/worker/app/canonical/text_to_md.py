from pathlib import Path

from app.canonical.md_sanitizer import sanitize_markdown


def text_to_markdown(path: Path) -> str:
    text = path.read_text(encoding="utf-8", errors="ignore")
    return sanitize_markdown(text)
