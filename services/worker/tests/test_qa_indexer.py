from app.models import TextChunk
from app import qa_indexer


def test_fetch_qa_candidates_posts_internal_contract(monkeypatch):
    source = TextChunk("source-1", 0, "Source body", 3, {"heading": "Intro"})
    response_payload = {
        "candidates": [
            {
                "sourceChunkId": "source-1",
                "question": "What is this?",
                "answer": "This is the answer.",
            }
        ]
    }
    client = FakeClient(response_payload)
    monkeypatch.setattr(qa_indexer.httpx, "Client", lambda **kwargs: client)
    monkeypatch.setattr(qa_indexer.settings, "api_base_url", "http://api")
    monkeypatch.setattr(qa_indexer.settings, "dupi_internal_key", "secret")

    candidates = qa_indexer.fetch_qa_candidates("kb-1", "doc-1", [source])

    assert candidates == response_payload["candidates"]
    assert client.calls == [
        (
            "/api/v1/internal/knowledge-bases/kb-1/qa-candidates",
            {
                "docId": "doc-1",
                "sources": [
                    {
                        "chunkId": "source-1",
                        "content": "Source body",
                        "metadata": {"heading": "Intro"},
                    }
                ],
            },
            {"X-Dupi-Internal-Key": "secret"},
        )
    ]


def test_fetch_qa_candidates_batches_long_documents(monkeypatch):
    sources = [
        TextChunk(f"source-{index}", index, f"Source {index}", 2, {})
        for index in range(17)
    ]
    client = BatchFakeClient()
    monkeypatch.setattr(qa_indexer.httpx, "Client", lambda **kwargs: client)

    candidates = qa_indexer.fetch_qa_candidates("kb-1", "doc-1", sources)

    assert [len(call[1]["sources"]) for call in client.calls] == [16, 1]
    assert [candidate["sourceChunkId"] for candidate in candidates] == [
        source.id for source in sources
    ]


def test_materialize_qa_chunks_preserves_source_provenance():
    source = TextChunk(
        "source-1",
        0,
        "Source body",
        3,
        {"heading": "Intro", "source": "guide.md", "chunk_role": "parent"},
    )
    candidates = [
        {
            "sourceChunkId": "source-1",
            "question": "What is this?",
            "answer": "This is the answer.",
        }
    ]

    chunks = qa_indexer.materialize_qa_chunks(candidates, [source], start_index=4)

    assert len(chunks) == 1
    chunk = chunks[0]
    assert chunk.chunk_index == 4
    assert chunk.content == "Question: What is this?\nAnswer: This is the answer."
    assert chunk.metadata == {
        "heading": "Intro",
        "source": "guide.md",
        "chunk_role": "qa",
        "source_chunk_id": "source-1",
        "qa_question": "What is this?",
        "qa_answer": "This is the answer.",
        "qa_source_kind": "parent",
    }


def test_generate_qa_chunks_returns_empty_when_api_fails(monkeypatch):
    source = TextChunk("source-1", 0, "Source body", 3, {})
    monkeypatch.setattr(
        qa_indexer,
        "fetch_qa_candidates",
        lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("api down")),
    )

    assert qa_indexer.generate_qa_chunks("kb-1", "doc-1", [source], 1) == []


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


class FakeClient:
    def __init__(self, payload):
        self.payload = payload
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def post(self, path, json, headers):
        self.calls.append((path, json, headers))
        return FakeResponse(self.payload)


class BatchFakeClient(FakeClient):
    def __init__(self):
        super().__init__({})

    def post(self, path, json, headers):
        self.calls.append((path, json, headers))
        return FakeResponse({
            "candidates": [
                {
                    "sourceChunkId": source["chunkId"],
                    "question": f"Question for {source['chunkId']}?",
                    "answer": "Answer.",
                }
                for source in json["sources"]
            ]
        })
