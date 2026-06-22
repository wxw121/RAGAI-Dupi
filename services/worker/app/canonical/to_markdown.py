from pathlib import Path

from app.canonical.docx_to_md import docx_to_markdown
from app.canonical.pdf_to_md import pdf_to_markdown
from app.canonical.text_to_md import text_to_markdown
from app.canonical.xlsx_to_md import xlsx_to_markdown


def canonicalize(path: Path, mime_type: str, file_name: str) -> str:
    """Convert supported document formats to canonical Markdown."""
    suffix = path.suffix.lower()

    if suffix == ".pdf" or "pdf" in mime_type:
        return pdf_to_markdown(path)
    if suffix in (".docx",) or "wordprocessingml" in mime_type:
        return docx_to_markdown(path)
    if suffix in (".xlsx", ".xls") or "spreadsheet" in mime_type:
        return xlsx_to_markdown(path)
    if suffix in (".txt", ".md", ".markdown") or mime_type.startswith("text/"):
        return text_to_markdown(path)

    raise ValueError(f"Unsupported file type for canonicalize: {file_name} ({mime_type})")
