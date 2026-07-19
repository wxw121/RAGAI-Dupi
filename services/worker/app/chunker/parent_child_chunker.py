from app.chunker.recursive_chunker import chunk_nodes
from app.models import DocumentNode, TextChunk

PARENT_SIZE_MULTIPLIER = 3


def build_parent_child_chunks(
    nodes: list[DocumentNode],
    child_chunk_size: int = 512,
    child_overlap: int = 64,
    strategy: str = "recursive",
    embed_fn=None,
) -> tuple[list[TextChunk], list[TextChunk]]:
    parent_chunk_size = max(child_chunk_size + 1, child_chunk_size * PARENT_SIZE_MULTIPLIER)
    parents = chunk_nodes(
        nodes,
        chunk_size=parent_chunk_size,
        chunk_overlap=0,
        strategy=strategy,
        embed_fn=embed_fn,
    )

    children: list[TextChunk] = []
    for parent_index, parent in enumerate(parents):
        parent.chunk_index = parent_index
        parent.metadata = {**parent.metadata, "chunk_role": "parent"}

        child_metadata = {
            key: value
            for key, value in parent.metadata.items()
            if key != "chunk_role"
        }
        parent_children = chunk_nodes(
            [DocumentNode(text=parent.content, metadata=child_metadata)],
            chunk_size=child_chunk_size,
            chunk_overlap=child_overlap,
            strategy=strategy,
            embed_fn=embed_fn,
        )
        for child in parent_children:
            child.chunk_index = len(parents) + len(children)
            child.metadata = {
                **child.metadata,
                "chunk_role": "child",
                "parent_chunk_id": parent.id,
            }
            children.append(child)

    return parents, children
