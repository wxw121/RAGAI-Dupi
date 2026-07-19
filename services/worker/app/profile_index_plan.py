from dataclasses import dataclass

from app.chunker.parent_child_chunker import build_parent_child_chunks
from app.chunker.recursive_chunker import chunk_nodes
from app.models import DocumentNode, TextChunk
from app.qa_indexer import generate_qa_chunks


@dataclass
class ProfileIndexPlan:
    persisted_chunks: list[TextChunk]
    v2_index_chunks: list[TextChunk]
    legacy_chunks: list[TextChunk]


def mark_scope(chunks: list[TextChunk], role: str, profile_scope: list[str]) -> None:
    for chunk in chunks:
        chunk.metadata = {
            **chunk.metadata,
            "chunk_role": role,
            "entry_kind": role,
            "profile_scope": list(profile_scope),
        }


def build_profile_index_plan(
    nodes: list[DocumentNode],
    chunk_size: int,
    chunk_overlap: int,
    strategy: str,
    embed_fn,
    kb_id: str,
    doc_id: str,
) -> ProfileIndexPlan:
    original_chunks = chunk_nodes(
        nodes,
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
        strategy=strategy,
        embed_fn=embed_fn,
    )
    parent_chunks, child_chunks = build_parent_child_chunks(
        nodes,
        child_chunk_size=chunk_size,
        child_overlap=chunk_overlap,
        strategy=strategy,
        embed_fn=embed_fn,
    )

    mark_scope(original_chunks, "original", ["classic", "qa-assisted"])
    mark_scope(parent_chunks, "parent", ["parent-child", "combined"])
    mark_scope(child_chunks, "child", ["parent-child", "combined"])

    original_qa = generate_qa_chunks(
        kb_id,
        doc_id,
        original_chunks,
        start_index=0,
        profile_scope=["qa-assisted"],
    )
    parent_qa = generate_qa_chunks(
        kb_id,
        doc_id,
        parent_chunks,
        start_index=0,
        profile_scope=["combined"],
    )

    persisted_chunks = [
        *original_chunks,
        *parent_chunks,
        *child_chunks,
        *original_qa,
        *parent_qa,
    ]
    for index, chunk in enumerate(persisted_chunks):
        chunk.chunk_index = index

    return ProfileIndexPlan(
        persisted_chunks=persisted_chunks,
        v2_index_chunks=[*original_chunks, *child_chunks, *original_qa, *parent_qa],
        legacy_chunks=original_chunks,
    )
