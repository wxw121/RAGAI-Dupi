from pathlib import Path

from app.canonical.md_sanitizer import sanitize_markdown


def pdf_to_markdown(path: Path) -> str:
    try:
        import pymupdf4llm

        md = pymupdf4llm.to_markdown(str(path))
        if md and md.strip():
            return sanitize_markdown(md)
    except Exception:
        pass

    # Fallback: plain text per page
    import fitz

    pages: list[str] = []
    with fitz.open(path) as doc:
        for page in doc:
            text = page.get_text("text").strip()
            if text:
                pages.append(text)

    return sanitize_markdown("\n\n".join(pages))
