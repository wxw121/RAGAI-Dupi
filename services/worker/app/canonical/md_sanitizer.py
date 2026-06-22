import re


def sanitize_markdown(text: str) -> str:
    """Light cleanup for canonical Markdown."""
    if not text:
        return ""

    s = text.replace("\r\n", "\n").replace("\r", "\n")
    s = re.sub(r"[ \t]+\n", "\n", s)
    s = re.sub(r"\n{3,}", "\n\n", s)
    # Ensure ATX headings have a space after hashes
    s = re.sub(r"^(#{1,6})([^\s#].*)$", r"\1 \2", s, flags=re.MULTILINE)
    return s.strip()
