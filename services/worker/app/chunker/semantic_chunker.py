import uuid
import numpy as np

from app.chunker.recursive_chunker import count_tokens, recursive_split
from app.models import DocumentNode, TextChunk


def _cosine(a: np.ndarray, b: np.ndarray) -> float:
    denom = (np.linalg.norm(a) * np.linalg.norm(b))
    if denom == 0:
        return 0.0
    return float(np.dot(a, b) / denom)


def semantic_chunk_nodes(
    nodes: list[DocumentNode],
    target_chunk_size: int,
    embed_fn,
    similarity_threshold: float = 0.75,
) -> list[TextChunk]:
    chunks: list[TextChunk] = []
    index = 0

    for node in nodes:
        sentences = [s.strip() for s in node.text.replace("\n", "。").split("。") if s.strip()]
        if not sentences:
            continue

        embeddings = [np.array(embed_fn(s), dtype=np.float32) for s in sentences]
        groups: list[list[str]] = [[sentences[0]]]
        group_embs: list[np.ndarray] = [embeddings[0]]

        for i in range(1, len(sentences)):
            sim = _cosine(embeddings[i], group_embs[-1])
            candidate = "。".join(groups[-1] + [sentences[i]])
            if sim >= similarity_threshold and count_tokens(candidate) <= target_chunk_size:
                groups[-1].append(sentences[i])
                group_embs[-1] = (group_embs[-1] + embeddings[i]) / 2
            else:
                groups.append([sentences[i]])
                group_embs.append(embeddings[i])

        for group in groups:
            text = "。".join(group)
            for piece in recursive_split(text, target_chunk_size, 64):
                chunks.append(TextChunk(
                    id=str(uuid.uuid4()),
                    chunk_index=index,
                    content=piece,
                    token_count=count_tokens(piece),
                    metadata=dict(node.metadata),
                ))
                index += 1

    return chunks
