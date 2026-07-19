from app.chunker.parent_child_chunker import build_parent_child_chunks
from app.models import DocumentNode


def test_build_parent_child_chunks_links_children_to_semantic_parents():
    text = "\n\n".join(
        [
            "Section one " + "alpha beta gamma delta " * 20,
            "Section two " + "epsilon zeta eta theta " * 20,
        ]
    )

    parents, children = build_parent_child_chunks(
        [DocumentNode(text=text, metadata={"source": "guide.md"})],
        child_chunk_size=24,
        child_overlap=4,
        strategy="plain",
    )

    parent_ids = {parent.id for parent in parents}
    assert parents
    assert len(children) > len(parents)
    assert all(parent.metadata["chunk_role"] == "parent" for parent in parents)
    assert all(child.metadata["chunk_role"] == "child" for child in children)
    assert all(child.metadata["parent_chunk_id"] in parent_ids for child in children)
    assert [chunk.chunk_index for chunk in [*parents, *children]] == list(
        range(len(parents) + len(children))
    )
