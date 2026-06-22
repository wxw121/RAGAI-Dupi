from pathlib import Path

from docx import Document as DocxDocument

from app.canonical.md_sanitizer import sanitize_markdown


def _heading_level(style_name: str) -> int:
    style = style_name.lower()
    if "heading 1" in style:
        return 1
    if "heading 2" in style:
        return 2
    if "heading 3" in style:
        return 3
    if "heading 4" in style:
        return 4
    if "heading" in style:
        return 2
    return 0


def docx_to_markdown(path: Path) -> str:
    doc = DocxDocument(path)
    lines: list[str] = []

    for para in doc.paragraphs:
        text = para.text.strip()
        if not text:
            continue
        style = (para.style.name if para.style else "") or ""
        level = _heading_level(style)
        if level > 0:
            lines.append(f"{'#' * min(level, 6)} {text}")
        else:
            lines.append(text)

    return sanitize_markdown("\n\n".join(lines))
