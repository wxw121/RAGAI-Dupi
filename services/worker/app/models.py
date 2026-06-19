from dataclasses import dataclass, field
from typing import Any


@dataclass
class DocumentNode:
    text: str
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass
class TextChunk:
    id: str
    chunk_index: int
    content: str
    token_count: int
    metadata: dict[str, Any] = field(default_factory=dict)
    milvus_id: str | None = None
