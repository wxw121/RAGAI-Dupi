# dupi-RAG Product Overview

dupi-RAG is an enterprise knowledge-base service for Chinese and English content. It accepts PDF, DOCX, XLSX, Markdown, and plain-text files. Ingestion is asynchronous, and chat responses stream through Server-Sent Events (SSE).

The retrieval stack supports vector search and hybrid retrieval. Hybrid retrieval combines dense vectors with BM25 sparse matches, then can apply a reranker. The default result count is topK 5, while API requests may select up to topK 50.

Four retrieval profiles are available: Classic, Parent-Child, QA-assisted, and Combined. Classic uses the existing chunk index. Parent-Child retrieves small child chunks and returns parent context. QA-assisted indexes generated question-and-answer pairs with source provenance. Combined can use both Parent-Child and QA-assisted evidence.

Supported chunk strategies include recursive, semantic, Parent-Child, and QA-assisted processing. The standard recursive chunk size is 512 tokens with 64 tokens of overlap.

Knowledge-base access is controlled by ADMIN, OPS_ADMIN, and MEMBER roles. ADMIN manages accounts and policies, OPS_ADMIN handles operational recovery and governance, and MEMBER uses assigned knowledge bases.
