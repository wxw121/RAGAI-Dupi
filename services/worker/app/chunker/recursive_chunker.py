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
    if count_tokens(text) <= chunk_size:
        return [text] if text.strip() else []

    chunks: list[str] = []
    for sep in SEPARATORS:
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
    if strategy == "semantic" and embed_fn is not None:
        from app.chunker.semantic_chunker import semantic_chunk_nodes
        return semantic_chunk_nodes(nodes, chunk_size, embed_fn)

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
