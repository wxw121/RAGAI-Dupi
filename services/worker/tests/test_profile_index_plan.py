from app import profile_index_plan
from app.models import DocumentNode, TextChunk


def make_chunk(chunk_id: str, role: str | None = None) -> TextChunk:
    metadata = {"source": "guide.md"}
    if role:
        metadata["chunk_role"] = role
    return TextChunk(chunk_id, 0, f"content-{chunk_id}", 2, metadata)


def test_build_profile_index_plan_creates_all_profile_entries(monkeypatch):
    original = make_chunk("original")
    parent = make_chunk("parent", "parent")
    child = make_chunk("child", "child")
    original_qa = make_chunk("original-qa", "qa")
    parent_qa = make_chunk("parent-qa", "qa")

    monkeypatch.setattr(profile_index_plan, "chunk_nodes", lambda *args, **kwargs: [original])
    monkeypatch.setattr(
        profile_index_plan,
        "build_parent_child_chunks",
        lambda *args, **kwargs: ([parent], [child]),
    )

    def generate(kb_id, doc_id, sources, start_index, profile_scope):
        assert (kb_id, doc_id, start_index) == ("kb-1", "doc-1", 0)
        if sources == [original]:
            original_qa.metadata["profile_scope"] = profile_scope
            return [original_qa]
        parent_qa.metadata["profile_scope"] = profile_scope
        return [parent_qa]

    monkeypatch.setattr(profile_index_plan, "generate_qa_chunks", generate)

    plan = profile_index_plan.build_profile_index_plan(
        [DocumentNode("source")],
        chunk_size=100,
        chunk_overlap=10,
        strategy="recursive",
        embed_fn=None,
        kb_id="kb-1",
        doc_id="doc-1",
    )

    assert [chunk.metadata["chunk_role"] for chunk in plan.persisted_chunks] == [
        "original",
        "parent",
        "child",
        "qa",
        "qa",
    ]
    assert original.metadata["profile_scope"] == ["classic", "qa-assisted"]
    assert parent.metadata["profile_scope"] == ["parent-child", "combined"]
    assert child.metadata["profile_scope"] == ["parent-child", "combined"]
    assert original_qa.metadata["profile_scope"] == ["qa-assisted"]
    assert parent_qa.metadata["profile_scope"] == ["combined"]
    assert plan.v2_index_chunks == [original, child, original_qa, parent_qa]
    assert plan.legacy_chunks == [original]
    assert [chunk.chunk_index for chunk in plan.persisted_chunks] == list(range(5))


def test_build_profile_index_plan_keeps_base_entries_when_both_qa_calls_fail(monkeypatch):
    original = make_chunk("original")
    parent = make_chunk("parent", "parent")
    child = make_chunk("child", "child")
    qa_source_calls = []

    monkeypatch.setattr(profile_index_plan, "chunk_nodes", lambda *args, **kwargs: [original])
    monkeypatch.setattr(
        profile_index_plan,
        "build_parent_child_chunks",
        lambda *args, **kwargs: ([parent], [child]),
    )

    def fail_qa(kb_id, doc_id, sources):
        qa_source_calls.append([chunk.id for chunk in sources])
        raise RuntimeError("qa unavailable")

    monkeypatch.setattr("app.qa_indexer.fetch_qa_candidates", fail_qa)

    plan = profile_index_plan.build_profile_index_plan(
        [DocumentNode("source")],
        chunk_size=100,
        chunk_overlap=10,
        strategy="recursive",
        embed_fn=None,
        kb_id="kb-1",
        doc_id="doc-1",
    )

    assert qa_source_calls == [["original"], ["parent"]]
    assert [chunk.id for chunk in plan.persisted_chunks] == ["original", "parent", "child"]
    assert [chunk.id for chunk in plan.v2_index_chunks] == ["original", "child"]
