from app.chunker import markdown_chunker
from app.chunker.markdown_chunker import markdown_chunk_nodes
from app.chunker.recursive_chunker import chunk_nodes, count_tokens, recursive_split
from app.models import DocumentNode


def test_count_tokens_falls_back_when_tiktoken_fails(monkeypatch):
    monkeypatch.setattr(
        "app.chunker.recursive_chunker.tiktoken.get_encoding",
        lambda _: (_ for _ in ()).throw(RuntimeError("boom")),
    )

    assert count_tokens("abcdefgh") == 2
    assert count_tokens("") == 1


def test_recursive_split_keeps_overlap_and_drops_blank_chunks(monkeypatch):
    monkeypatch.setattr("app.chunker.recursive_chunker.count_tokens", lambda text: max(1, len(text)))

    chunks = recursive_split("aaa bbb ccc ddd", chunk_size=7, chunk_overlap=3)

    assert chunks[0] == "aaa bbb"
    assert chunks[1].startswith("bbb")
    assert all(chunk.strip() for chunk in chunks)


def test_recursive_split_handles_blank_small_text_and_character_fallback(monkeypatch):
    monkeypatch.setattr("app.chunker.recursive_chunker.count_tokens", lambda text: max(1, len(text)))

    assert recursive_split("   ", chunk_size=10, chunk_overlap=0) == []
    assert recursive_split("abc", chunk_size=10, chunk_overlap=0) == ["abc"]
    assert recursive_split("aaa\nbbb\nccc", chunk_size=3, chunk_overlap=1) == ["aaa", "a bbb", "b ccc"]


def test_chunk_nodes_routes_markdown_and_plain_recursive(monkeypatch):
    calls = {}

    def fake_markdown(nodes, chunk_size, chunk_overlap):
        calls["markdown"] = (nodes, chunk_size, chunk_overlap)
        return ["markdown-result"]

    monkeypatch.setattr("app.chunker.markdown_chunker.markdown_chunk_nodes", fake_markdown)
    assert chunk_nodes([DocumentNode("text")], strategy="markdown") == ["markdown-result"]
    assert calls["markdown"][1:] == (512, 64)

    monkeypatch.setattr("app.chunker.recursive_chunker.count_tokens", lambda text: max(1, len(text)))
    chunks = chunk_nodes(
        [DocumentNode("Heading body text", {"heading": "Heading"})],
        chunk_size=9,
        chunk_overlap=0,
        strategy="plain",
    )
    assert chunks
    assert chunks[0].chunk_index == 0
    assert chunks[0].metadata["heading"] == "Heading"


def test_chunk_nodes_routes_semantic_when_embedder_is_available(monkeypatch):
    monkeypatch.setattr("app.chunker.semantic_chunker.semantic_chunk_nodes", lambda nodes, size, fn: ["semantic"])

    assert chunk_nodes([DocumentNode("a")], strategy="semantic", embed_fn=lambda text: [1.0]) == ["semantic"]


def test_markdown_chunk_nodes_preserves_sections_tables_and_code(monkeypatch):
    monkeypatch.setattr(markdown_chunker, "count_tokens", lambda text: max(1, len(text) // 5))
    md = """# Title

Intro paragraph with enough text to split into smaller prose pieces.

| A | B |
| --- | --- |
| 1 | 2 |
| 3 | 4 |

```bash
echo one
echo two
```
"""

    chunks = markdown_chunk_nodes([DocumentNode(md, {"source": "doc.md"})], chunk_size=20, chunk_overlap=2)

    assert [c.chunk_index for c in chunks] == list(range(len(chunks)))
    assert {c.metadata["block_type"] for c in chunks} >= {"prose", "table", "code"}
    assert all(c.metadata["heading"] == "Title" for c in chunks)
    assert any(c.content.startswith("## Title") and "| A | B |" in c.content for c in chunks)
    assert any("```bash" in c.content for c in chunks)


def test_markdown_helpers_cover_edge_cases(monkeypatch):
    monkeypatch.setattr(markdown_chunker, "count_tokens", lambda text: len(text))

    assert markdown_chunker._split_sections("plain") == [("", "plain")]
    assert markdown_chunker._split_section_blocks("") == []
    assert markdown_chunker._split_code_block("```python\nprint(1)\nprint(2)\n```", 12)
    assert markdown_chunker._split_table_block("| A |\n| --- |\n| 1 |\n| 2 |", 12)
    assert markdown_chunker._format_chunk_content("", "body", "prose") == "body"


def test_markdown_helpers_cover_unclosed_code_heading_only_and_plain_heading(monkeypatch):
    monkeypatch.setattr(markdown_chunker, "count_tokens", lambda text: len(text))
    blocks = markdown_chunker._split_section_blocks("```python\nprint(1)\nprint(2)")

    assert blocks[0].block_type == "code"
    assert markdown_chunker._split_code_block("```python\n" + ("x\n" * 8), 12)
    assert markdown_chunker._split_table_block("| A |\n| 1 |\n| 222222222222 |", 8)
    assert markdown_chunker._split_block(markdown_chunker.Block("table", "| A |\n| --- |\n| 1 |"), "H", 8, 0)[0][1] == "table"
    assert markdown_chunker._split_block(markdown_chunker.Block("code", "```\na\n```"), "H", 8, 0)[0][1] == "code"
    assert markdown_chunker._format_chunk_content("Heading", "# Already headed", "prose") == "# Already headed"

    chunks = markdown_chunk_nodes([DocumentNode("# Empty", {"source": "doc.md"})], chunk_size=20, chunk_overlap=0)
    assert chunks[0].content.strip() == "## Empty"
    assert chunks[0].metadata["block_type"] == "prose"


def test_markdown_helpers_cover_small_blocks_and_empty_documents(monkeypatch):
    monkeypatch.setattr(markdown_chunker, "count_tokens", lambda text: 1)

    code = "```python\nprint(1)\n```"
    table = "| A |\n| --- |\n| 1 |"
    prose = markdown_chunker.Block("prose", "short text")

    assert markdown_chunker._split_sections("") == [("", "")]
    assert markdown_chunker._split_code_block(code, 10) == [code]
    assert markdown_chunker._split_table_block("not a table", 10) == ["not a table"]
    assert markdown_chunker._split_table_block(table, 10) == [table]
    assert markdown_chunker._split_block(prose, "", 10, 0) == [("short text", "prose")]
    assert markdown_chunk_nodes([DocumentNode("", {"source": "empty.md"})]) == []


def test_recursive_split_recurses_long_parts_and_prefixes_missing_heading(monkeypatch):
    monkeypatch.setattr("app.chunker.recursive_chunker.count_tokens", lambda text: max(1, len(text)))

    chunks = recursive_split("abcdefghij klm", chunk_size=4, chunk_overlap=0)
    assert "klm" in chunks

    node_chunks = chunk_nodes(
        [DocumentNode("body", {"heading": "Title"})],
        chunk_size=20,
        chunk_overlap=0,
        strategy="plain",
    )
    assert node_chunks[0].content.startswith("Title\n")
