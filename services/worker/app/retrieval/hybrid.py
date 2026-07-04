import logging
from typing import Any

from rank_bm25 import BM25Okapi

import httpx

from app.config import settings
from app.embedder import Embedder
from app.indexer import MilvusIndexer

logger = logging.getLogger(__name__)

_reranker = None


def get_reranker():
    """延迟加载 CrossEncoder 重排模型，并缓存不可用状态。

    重排模型较重且依赖可选包，因此这里使用懒加载加哨兵值的设计：
    第一次失败后缓存 False，后续请求不再重复导入，避免检索接口被可选
    依赖拖慢或刷屏记录相同告警。
    """
    global _reranker
    if _reranker is None:
        try:
            from sentence_transformers import CrossEncoder
            _reranker = CrossEncoder("BAAI/bge-reranker-base")
        except Exception as exc:
            logger.warning("Reranker not available: %s", exc)
            _reranker = False
    return _reranker if _reranker is not False else None


def tokenize(text: str) -> list[str]:
    return [t for t in text.lower().split() if t]


def rrf_fusion(vector_hits: list[dict], bm25_hits: list[dict], k: int = 60) -> list[dict]:
    """使用 Reciprocal Rank Fusion 融合向量召回和 BM25 召回结果。

    RRF 是一种排序融合思想：不直接比较两种召回的原始分数，而按名次折算
    成稳定得分，因此适合把语义向量分数和关键词相关性分数合并。
    """
    scores: dict[str, float] = {}
    docs: dict[str, dict] = {}

    for rank, hit in enumerate(vector_hits):
        cid = hit["chunk_id"]
        scores[cid] = scores.get(cid, 0) + 1 / (k + rank + 1)
        docs[cid] = hit

    for rank, hit in enumerate(bm25_hits):
        cid = hit["chunk_id"]
        scores[cid] = scores.get(cid, 0) + 1 / (k + rank + 1)
        docs[cid] = hit

    fused = sorted(scores.items(), key=lambda x: x[1], reverse=True)
    results = []
    for cid, score in fused:
        item = dict(docs[cid])
        item["score"] = score
        results.append(item)
    return results


def bm25_search(corpus: list[dict], query: str, top_k: int) -> list[dict]:
    if not corpus:
        return []
    tokenized = [tokenize(c["content"]) for c in corpus]
    bm25 = BM25Okapi(tokenized)
    scores = bm25.get_scores(tokenize(query))
    ranked = sorted(zip(corpus, scores), key=lambda x: x[1], reverse=True)[:top_k]
    return [{**doc, "score": float(score)} for doc, score in ranked if score > 0]


def rerank_hits(query: str, hits: list[dict], top_k: int) -> list[dict]:
    model = get_reranker()
    if model is None or not hits:
        return hits[:top_k]

    pairs = [[query, h["content"]] for h in hits]
    scores = model.predict(pairs)
    ranked = sorted(zip(hits, scores), key=lambda x: x[1], reverse=True)
    results = []
    for hit, score in ranked[:top_k]:
        item = dict(hit)
        item["score"] = float(score)
        results.append(item)
    return results


def fetch_kb_corpus(kb_id: str) -> list[dict]:
    try:
        with httpx.Client(base_url=settings.api_base_url, timeout=30.0) as client:
            response = client.get(f"/api/v1/internal/knowledge-bases/{kb_id}/chunks")
            response.raise_for_status()
            return response.json()
    except Exception as exc:
        logger.warning("Failed to fetch corpus for kb %s: %s", kb_id, exc)
        return []


def hybrid_retrieve(
    kb_id: str,
    query: str,
    top_k: int,
    embedding_model: str,
    embedding_dimension: int,
    use_rerank: bool = False,
    corpus_fetcher=None,
) -> list[dict]:
    """执行混合检索主流程：向量召回、BM25 召回、RRF 融合、可选重排。

    这里体现的是典型 RAG 检索流水线设计：Embedder 负责查询向量化，
    MilvusIndexer 负责语义召回，BM25 补足关键词精确匹配，最后通过
    RRF 和可选 CrossEncoder 重排统一排序。
    """
    embedder = Embedder(model=embedding_model)
    indexer = MilvusIndexer(dimension=embedding_dimension)
    vector_hits = indexer.search(kb_id, embedder.embed(query), top_k * 2)

    corpus = corpus_fetcher(kb_id) if corpus_fetcher else fetch_kb_corpus(kb_id)

    bm25_hits = bm25_search(corpus, query, top_k * 2)
    fused = rrf_fusion(vector_hits, bm25_hits)[: top_k * 2]

    if use_rerank:
        return rerank_hits(query, fused, top_k)
    return fused[:top_k]
