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
    """按 ATX 标题切分 Markdown，返回 (标题, 正文) 元组。

    这里采用“先按章节切、再按块切”的分层处理思想：章节标题会进入
    chunk 元数据，后续表格/代码/正文块再分别使用不同策略切分，避免
    检索结果丢失原文层级。
    """
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
    """把一个章节正文切成代码、表格、普通文本三类原子块。

    这是一个轻量的策略分发入口：代码块优先匹配围栏，表格保持整表结构，
    其余文本才按正文处理。这样可以避免递归切分把代码围栏或 Markdown
    表头拆坏，提升后续向量检索片段的可读性。
    """
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
    """按行切分超长代码块，并为每个片段补齐代码围栏。

    设计上把代码视为结构化块：即使内容被拆成多个 chunk，也保留开头
    语言标识和闭合围栏，避免前端渲染或 LLM 上下文中出现未闭合代码块。
    """
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
    """按数据行拆分超长表格，并在每个片段重复表头和分隔线。

    这里使用“表头复制”的信息保真策略：每个 chunk 都能独立表达列含义，
    避免检索命中表格中段时丢失字段上下文。
    """
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
    """根据块类型路由到对应切分策略，返回 (内容, 块类型)。

    该函数是分块器里的策略模式雏形：调用方只关心 Block，具体切分算法
    由 block_type 决定，方便后续继续扩展图片说明、引用块等新类型。
    """
    if block.block_type == "code":
        pieces = _split_code_block(block.text, chunk_size)
        return [(p, "code") for p in pieces]

    if block.block_type == "table":
        pieces = _split_table_block(block.text, chunk_size)
        return [(p, "table") for p in pieces]

    # 普通正文走递归切分策略，尽量保留段落和句子边界，表格/代码则已在上方专门处理。
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
    """把文档节点转换为可索引的 Markdown 感知 chunk。

    流程遵循 RAG 索引流水线的分层设计：节点元数据作为基础上下文，
    章节标题作为层级上下文，块类型作为检索特征写入 metadata。这样既能
    让 Milvus 保存统一 TextChunk，又能让召回结果保留 Markdown 语义。
    """
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
