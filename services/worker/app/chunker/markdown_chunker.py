import re
import uuid
from dataclasses import dataclass
from typing import Any

from app.chunker.recursive_chunker import count_tokens, recursive_split
from app.models import DocumentNode, TextChunk

HEADING_RE = re.compile(r"^(#{1,6})\s+(.+)$")
FENCE_RE = re.compile(r"^(`{3,})(\w*)?\s*$")
TABLE_SEP_RE = re.compile(r"^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$")


@dataclass
class Block:
    block_type: str  # prose | table | code
    text: str
    heading: str = ""


def _split_sections(md: str) -> list[tuple[str, str]]:
    """Split markdown by ATX headings. Returns (heading, body) pairs."""
    lines = md.split("\n")
    sections: list[tuple[str, str]] = []
    current_heading = ""
    current_lines: list[str] = []

    for line in lines:
        m = HEADING_RE.match(line)
        if m:
            if current_lines or current_heading:
                sections.append((current_heading, "\n".join(current_lines).strip()))
            current_heading = m.group(2).strip()
            current_lines = []
        else:
            current_lines.append(line)

    if current_lines or current_heading:
        sections.append((current_heading, "\n".join(current_lines).strip()))

    if not sections and md.strip():
        sections.append(("", md.strip()))

    return sections


def _is_table_row(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith("|") and stripped.endswith("|") and "|" in stripped[1:]


def _is_table_separator(line: str) -> bool:
    return TABLE_SEP_RE.match(line.strip()) is not None


def _split_section_blocks(body: str) -> list[Block]:
    """Split section body into atomic blocks: code fences, tables, prose."""
    if not body.strip():
        return []

    lines = body.split("\n")
    blocks: list[Block] = []
    i = 0

    while i < len(lines):
        line = lines[i]
        fence_m = FENCE_RE.match(line.strip())

        if fence_m:
            fence = fence_m.group(1)
            lang = fence_m.group(2) or ""
            code_lines = [line]
            i += 1
            while i < len(lines):
                code_lines.append(lines[i])
                if lines[i].strip().startswith(fence) and len(lines[i].strip()) >= len(fence):
                    i += 1
                    break
                i += 1
            blocks.append(Block(block_type="code", text="\n".join(code_lines)))
            continue

        if _is_table_row(line):
            table_lines = [line]
            i += 1
            while i < len(lines) and (_is_table_row(lines[i]) or _is_table_separator(lines[i])):
                table_lines.append(lines[i])
                i += 1
            blocks.append(Block(block_type="table", text="\n".join(table_lines)))
            continue

        prose_lines = []
        while i < len(lines):
            if FENCE_RE.match(lines[i].strip()) or _is_table_row(lines[i]):
                break
            prose_lines.append(lines[i])
            i += 1
        prose = "\n".join(prose_lines).strip()
        if prose:
            blocks.append(Block(block_type="prose", text=prose))

    return blocks


def _split_code_block(text: str, chunk_size: int) -> list[str]:
    lines = text.split("\n")
    if not lines:
        return []

    open_line = lines[0]
    close_fence = open_line.strip()[:3]
    inner = lines[1:-1] if len(lines) > 1 and lines[-1].strip().startswith(close_fence) else lines[1:]
    full_inner = "\n".join(inner)

    if count_tokens(text) <= chunk_size:
        return [text]

    parts: list[str] = []
    batch: list[str] = []
    for line in inner:
        candidate = "\n".join(batch + [line])
        open_block = f"{open_line}\n{candidate}\n{close_fence}"
        if count_tokens(open_block) <= chunk_size:
            batch.append(line)
        else:
            if batch:
                parts.append(f"{open_line}\n" + "\n".join(batch) + f"\n{close_fence}")
            batch = [line]
    if batch:
        parts.append(f"{open_line}\n" + "\n".join(batch) + f"\n{close_fence}")

    return parts if parts else [text]


def _split_table_block(text: str, chunk_size: int) -> list[str]:
    lines = [l for l in text.split("\n") if l.strip()]
    if len(lines) < 2:
        return [text]

    header = lines[0]
    sep = lines[1] if _is_table_separator(lines[1]) else None
    data_start = 2 if sep else 1
    data_rows = lines[data_start:]

    prefix_lines = [header]
    if sep:
        prefix_lines.append(sep)
    prefix = "\n".join(prefix_lines)
    prefix_tokens = count_tokens(prefix)

    if count_tokens(text) <= chunk_size:
        return [text]

    parts: list[str] = []
    batch: list[str] = []
    for row in data_rows:
        candidate = prefix + "\n" + "\n".join(batch + [row])
        if count_tokens(candidate) <= chunk_size:
            batch.append(row)
        else:
            if batch:
                parts.append(prefix + "\n" + "\n".join(batch))
            batch = [row]
    if batch:
        parts.append(prefix + "\n" + "\n".join(batch))

    return parts if parts else [text]


def _split_block(block: Block, heading: str, chunk_size: int, chunk_overlap: int) -> list[tuple[str, str]]:
    """Return list of (content, block_type) after size splitting."""
    if block.block_type == "code":
        pieces = _split_code_block(block.text, chunk_size)
        return [(p, "code") for p in pieces]

    if block.block_type == "table":
        pieces = _split_table_block(block.text, chunk_size)
        return [(p, "table") for p in pieces]

    # prose
    if count_tokens(block.text) <= chunk_size:
        return [(block.text, "prose")]

    pieces = recursive_split(block.text, chunk_size, chunk_overlap)
    return [(p, "prose") for p in pieces]


def _format_chunk_content(heading: str, text: str, block_type: str) -> str:
    if heading and block_type in ("table", "code"):
        level = "##"
        return f"{level} {heading}\n\n{text}"
    if heading and not text.startswith("#"):
        return f"## {heading}\n\n{text}"
    return text


def markdown_chunk_nodes(
    nodes: list[DocumentNode],
    chunk_size: int = 512,
    chunk_overlap: int = 64,
) -> list[TextChunk]:
    chunks: list[TextChunk] = []
    index = 0

    for node in nodes:
        base_meta: dict[str, Any] = dict(node.metadata)
        sections = _split_sections(node.text)

        for heading, body in sections:
            if not body and not heading:
                continue

            section_blocks = _split_section_blocks(body) if body else []
            if not section_blocks and heading:
                section_blocks = [Block(block_type="prose", text="", heading=heading)]

            for block in section_blocks:
                block.heading = heading
                split_pieces = _split_block(block, heading, chunk_size, chunk_overlap)

                for piece_text, piece_type in split_pieces:
                    if not piece_text.strip() and not heading:
                        continue
                    content = _format_chunk_content(heading, piece_text, piece_type)
                    if not content.strip():
                        continue

                    meta = dict(base_meta)
                    meta["heading"] = heading
                    meta["block_type"] = piece_type

                    chunks.append(
                        TextChunk(
                            id=str(uuid.uuid4()),
                            chunk_index=index,
                            content=content,
                            token_count=count_tokens(content),
                            metadata=meta,
                        )
                    )
                    index += 1

    return chunks
