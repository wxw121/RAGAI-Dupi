import uuid
from typing import Iterable

import tiktoken

from app.models import DocumentNode, TextChunk

SEPARATORS = ["\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", " ", ""]


def count_tokens(text: str) -> int:
    try:
        enc = tiktoken.get_encoding("cl100k_base")
        return len(enc.encode(text))
    except Exception:
        return max(1, len(text) // 4)


def recursive_split(text: str, chunk_size: int, chunk_overlap: int) -> list[str]:
    """按优先级分隔符递归切分文本，并在相邻片段间保留重叠窗口。

    这里采用“递归降级切分”的设计思想：优先按段落、换行、句子等语义边界
    切分，只有没有合适分隔符时才退化为固定长度切片。chunk_overlap 用来
    减少边界信息丢失，是 RAG 分块中常见的上下文滑窗策略。
    """
    if count_tokens(text) <= chunk_size:
        return [text] if text.strip() else []

    chunks: list[str] = []
    for sep in SEPARATORS:
        if sep == "":
            step = max(1, chunk_size - chunk_overlap)
            chunks = [text[i:i + chunk_size] for i in range(0, len(text), step)]
            break
        if sep in text:
            parts = text.split(sep)
            current = ""
            for part in parts:
                candidate = (current + sep + part) if current else part
                if count_tokens(candidate) <= chunk_size:
                    current = candidate
                else:
                    if current.strip():
                        chunks.append(current.strip())
                    if count_tokens(part) > chunk_size and sep != "":
                        chunks.extend(recursive_split(part, chunk_size, chunk_overlap))
                        current = ""
                    else:
                        current = part
            if current.strip():
                chunks.append(current.strip())
            break
    else:
        chunks = [text[i:i + chunk_size] for i in range(0, len(text), chunk_size - chunk_overlap)]

    if chunk_overlap > 0 and len(chunks) > 1:
        overlapped = [chunks[0]]
        for i in range(1, len(chunks)):
            prev = overlapped[-1]
            overlap_text = prev[-chunk_overlap:] if len(prev) > chunk_overlap else prev
            overlapped.append((overlap_text + " " + chunks[i]).strip())
        chunks = overlapped

    return [c for c in chunks if c.strip()]


def chunk_nodes(
    nodes: list[DocumentNode],
    chunk_size: int = 512,
    chunk_overlap: int = 64,
    strategy: str = "recursive",
    embed_fn=None,
) -> list[TextChunk]:
    """根据指定策略把解析后的文档节点转换成 TextChunk 列表。

    该函数承担策略路由职责：semantic 分支延迟导入语义分块器，markdown
    和 recursive 复用 Markdown 感知分块，plain 分支保留最小递归切分能力。
    这种设计避免主流程绑定具体算法，便于按知识库配置切换分块策略。
    """
    if strategy == "semantic" and embed_fn is not None:
        from app.chunker.semantic_chunker import semantic_chunk_nodes
        return semantic_chunk_nodes(nodes, chunk_size, embed_fn)

    if strategy in ("markdown", "recursive"):
        from app.chunker.markdown_chunker import markdown_chunk_nodes
        return markdown_chunk_nodes(nodes, chunk_size, chunk_overlap)

    chunks: list[TextChunk] = []
    index = 0
    for node in nodes:
        heading = node.metadata.get("heading", "")
        prefix = f"{heading}\n" if heading and heading not in node.text[:50] else ""
        pieces = recursive_split(prefix + node.text, chunk_size, chunk_overlap)
        for piece in pieces:
            meta = dict(node.metadata)
            chunks.append(TextChunk(
                id=str(uuid.uuid4()),
                chunk_index=index,
                content=piece,
                token_count=count_tokens(piece),
                metadata=meta,
            ))
            index += 1
    return chunks
