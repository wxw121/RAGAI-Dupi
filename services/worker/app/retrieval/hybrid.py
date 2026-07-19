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


def weighted_rrf(routes: list[tuple[float, list[dict]]], k: int = 60) -> list[dict]:
    if k < 1:
        raise ValueError("RRF K must be at least 1")
    scores: dict[str, float] = {}
    items: dict[str, dict] = {}
    for weight, hits in routes:
        if weight <= 0:
            raise ValueError("route weight must be positive")
        for rank, hit in enumerate(hits):
            chunk_id = hit["chunk_id"]
            scores[chunk_id] = scores.get(chunk_id, 0.0) + weight / (k + rank + 1)
            items[chunk_id] = hit
    return [
        {**items[chunk_id], "score": score}
        for chunk_id, score in sorted(
            scores.items(),
            key=lambda item: item[1],
            reverse=True,
        )
    ]


def rrf_fusion(vector_hits: list[dict], bm25_hits: list[dict], k: int = 60) -> list[dict]:
    """使用 Reciprocal Rank Fusion 融合向量召回和 BM25 召回结果。

    RRF 是一种排序融合思想：不直接比较两种召回的原始分数，而按名次折算
    成稳定得分，因此适合把语义向量分数和关键词相关性分数合并。
    """
    return weighted_rrf([(1.0, vector_hits), (1.0, bm25_hits)], k)


def bm25_search(corpus: list[dict], query: str, top_k: int) -> list[dict]:
    if not corpus:
        return []
    tokenized = [tokenize(c["content"]) for c in corpus]
    bm25 = BM25Okapi(tokenized)
    scores = bm25.get_scores(tokenize(query))
    ranked = sorted(zip(corpus, scores), key=lambda x: x[1], reverse=True)[:top_k]
    return [{**doc, "score": float(score)} for doc, score in ranked if score > 0]


def filter_profile_corpus(
    corpus: list[dict],
    retrieval_profile: str,
    profile_index_ready: bool,
    entry_kind: str | None = None,
) -> list[dict]:
    filtered = []
    for item in corpus:
        metadata = item.get("metadata") or {}
        kind = str(metadata.get("entry_kind") or metadata.get("chunk_role") or "original").lower()
        if kind == "original_chunk":
            kind = "original"
        if not profile_index_ready:
            in_scope = retrieval_profile == "classic" and kind == "original"
        else:
            scope = {str(profile).lower() for profile in metadata.get("profile_scope", [])}
            in_scope = retrieval_profile in scope
        if not in_scope or kind == "parent":
            continue
        if entry_kind is not None and kind != entry_kind:
            continue
        filtered.append(item)
    return filtered



def attach_profile_metadata(hits: list[dict], retrieval_profile: str) -> list[dict]:
    if retrieval_profile == "classic":
        return hits
    results = []
    for hit in hits:
        item = dict(hit)
        metadata = dict(item.get("metadata") or {})
        metadata["retrieval_profile"] = retrieval_profile
        item["metadata"] = metadata
        results.append(item)
    return results


def hydrate_vector_hits(hits: list[dict], corpus: list[dict]) -> list[dict]:
    corpus_by_id = {str(item.get("chunk_id")): item for item in corpus}
    hydrated = []
    for hit in hits:
        source = corpus_by_id.get(str(hit.get("chunk_id")))
        if source is None:
            hydrated.append(hit)
            continue
        item = {**source, **hit}
        metadata = dict(source.get("metadata") or {})
        metadata.update(hit.get("metadata") or {})
        item["metadata"] = metadata
        hydrated.append(item)
    return hydrated

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
        headers = {}
        if settings.dupi_internal_key:
            headers["X-Dupi-Internal-Key"] = settings.dupi_internal_key
        with httpx.Client(base_url=settings.api_base_url, timeout=30.0) as client:
            response = client.get(
                f"/api/v1/internal/knowledge-bases/{kb_id}/chunks",
                headers=headers,
            )
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
    retrieval_profile: str = "classic",
    profile_index_ready: bool = False,
    corpus_fetcher=None,
) -> list[dict]:
    """执行混合检索主流程：向量召回、BM25 召回、RRF 融合、可选重排。

    这里体现的是典型 RAG 检索流水线设计：Embedder 负责查询向量化，
    MilvusIndexer 负责语义召回，BM25 补足关键词精确匹配，最后通过
    RRF 和可选 CrossEncoder 重排统一排序。
    """
    if not profile_index_ready and retrieval_profile != "classic":
        raise ValueError("Profile index is not ready for non-classic retrieval")

    embedder = Embedder(model=embedding_model)
    query_vector = embedder.embed(query)
    route_limit = top_k * 2
    corpus = corpus_fetcher(kb_id) if corpus_fetcher else fetch_kb_corpus(kb_id)

    if not profile_index_ready:
        indexer = MilvusIndexer(dimension=embedding_dimension)
        vector_hits = hydrate_vector_hits(
            indexer.search(kb_id, query_vector, route_limit), corpus)
        filtered_corpus = filter_profile_corpus(corpus, retrieval_profile, False)
        fused = rrf_fusion(
            vector_hits,
            bm25_search(filtered_corpus, query, route_limit),
            settings.rrf_k,
        )[:route_limit]
    else:
        indexer = MilvusIndexer(
            dimension=embedding_dimension,
            collection_name=settings.milvus_profile_collection,
            profile_schema=True,
        )
        if retrieval_profile == "combined":
            child_vector_hits = hydrate_vector_hits(indexer.search_profile(
                kb_id, query_vector, route_limit, retrieval_profile, "child"), corpus)
            qa_vector_hits = hydrate_vector_hits(indexer.search_profile(
                kb_id, query_vector, route_limit, retrieval_profile, "qa"), corpus)
            child_corpus = filter_profile_corpus(corpus, retrieval_profile, True, "child")
            qa_corpus = filter_profile_corpus(corpus, retrieval_profile, True, "qa")
            child_bm25_hits = bm25_search(child_corpus, query, route_limit)
            qa_bm25_hits = bm25_search(qa_corpus, query, route_limit)
            fused = weighted_rrf([
                (settings.combined_child_weight, child_vector_hits),
                (settings.combined_child_weight, child_bm25_hits),
                (settings.combined_qa_weight, qa_vector_hits),
                (settings.combined_qa_weight, qa_bm25_hits),
            ], settings.rrf_k)[:route_limit]
        else:
            entry_kind = "child" if retrieval_profile == "parent-child" else None
            vector_hits = hydrate_vector_hits(indexer.search_profile(
                kb_id, query_vector, route_limit, retrieval_profile, entry_kind), corpus)
            filtered_corpus = filter_profile_corpus(
                corpus, retrieval_profile, True, entry_kind)
            fused = rrf_fusion(
                vector_hits,
                bm25_search(filtered_corpus, query, route_limit),
                settings.rrf_k,
            )[:route_limit]

    if use_rerank:
        return attach_profile_metadata(rerank_hits(query, fused, top_k), retrieval_profile)
    return attach_profile_metadata(fused[:top_k], retrieval_profile)
