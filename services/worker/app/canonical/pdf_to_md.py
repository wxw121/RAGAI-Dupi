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

    # 降级策略：pymupdf4llm 不可用或没有解析出 Markdown 时，按页抽取纯文本。
    # 这里体现适配器思想：上层只需要 Markdown 字符串，不关心底层 PDF 解析库是哪一个。
    import fitz

    pages: list[str] = []
    with fitz.open(path) as doc:
        for page in doc:
            text = page.get_text("text").strip()
            if text:
                pages.append(text)

    return sanitize_markdown("\n\n".join(pages))
