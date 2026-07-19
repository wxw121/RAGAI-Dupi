import logging
from typing import Any

from rank_bm25 import BM25Okapi

import httpx

from app.config import settings
from app.embedder import Embedder
from app.indexer import MilvusIndexer
from app.reranker import RerankerLifecycle
from app.retrieval.sparse import SparseMilvusAdapter

logger = logging.getLogger(__name__)

_reranker_lifecycle = RerankerLifecycle(settings.rerank_model, settings.rerank_cache_dir)


def get_reranker():
    """延迟加载 CrossEncoder 重排模型，并缓存不可用状态。

    重排模型较重且依赖可选包，因此这里使用懒加载加哨兵值的设计：
    第一次失败后缓存 False，后续请求不再重复导入，避免检索接口被可选
    依赖拖慢或刷屏记录相同告警。
    """
    return _reranker_lifecycle.get_model()


def tokenize(text: str) -> list[str]:
    return [t for t in text.lower().split() if t]


def weighted_rrf(routes: list[tuple[float, list[dict]]], k: int = 60) -> list[dict]:
    if k < 1:
        raise ValueError("RRF K must be at least 1")
    scores: dict[str, float] = {}
    items: dict[str, dict] = {}
    route_ranks: dict[str, dict[str, int]] = {}
    for route_index, (weight, hits) in enumerate(routes, start=1):
        if weight <= 0:
            raise ValueError("route weight must be positive")
        for rank, hit in enumerate(hits):
            chunk_id = hit["chunk_id"]
            scores[chunk_id] = scores.get(chunk_id, 0.0) + weight / (k + rank + 1)
            items[chunk_id] = hit
            route_ranks.setdefault(chunk_id, {})[f"route_{route_index}"] = rank + 1
    results = []
    for fusion_rank, (chunk_id, score) in enumerate(
        sorted(scores.items(), key=lambda item: item[1], reverse=True), start=1
    ):
        results.append({
            **items[chunk_id],
            "score": score,
            "fusion_score": score,
            "fusion_rank": fusion_rank,
            "route_ranks": route_ranks[chunk_id],
        })
    return results


def rrf_fusion(vector_hits: list[dict], bm25_hits: list[dict], k: int = 60) -> list[dict]:
    """使用 Reciprocal Rank Fusion 融合向量召回和 BM25 召回结果。

    RRF 是一种排序融合思想：不直接比较两种召回的原始分数，而按名次折算
    成稳定得分，因此适合把语义向量分数和关键词相关性分数合并。
    """
    scores: dict[str, float] = {}
    items: dict[str, dict] = {}
    for rank, hit in enumerate(vector_hits):
        chunk_id = hit["chunk_id"]
        scores[chunk_id] = scores.get(chunk_id, 0.0) + 1 / (k + rank + 1)
        items[chunk_id] = {**hit, "vector_rank": rank + 1}
    for rank, hit in enumerate(bm25_hits):
        chunk_id = hit["chunk_id"]
        scores[chunk_id] = scores.get(chunk_id, 0.0) + 1 / (k + rank + 1)
        items[chunk_id] = {**hit, **items.get(chunk_id, {}), "sparse_rank": rank + 1}
    results = []
    for rank, (chunk_id, score) in enumerate(
        sorted(scores.items(), key=lambda item: item[1], reverse=True), start=1
    ):
        results.append({
            **items[chunk_id],
            "score": score,
            "fusion_score": score,
            "fusion_rank": rank,
        })
    return results


def bm25_search(
    corpus: list[dict],
    query: str,
    top_k: int,
    index_params: dict | None = None,
    search_params: dict | None = None,
) -> list[dict]:
    if not corpus:
        return []
    tokenized = [tokenize(c["content"]) for c in corpus]
    index_params = index_params or {}
    search_params = search_params or {}
    bm25 = BM25Okapi(
        tokenized,
        k1=float(index_params.get("bm25_k1", 1.5)),
        b=float(index_params.get("bm25_b", 0.75)),
        epsilon=float(index_params.get("bm25_epsilon", 0.25)),
    )
    scores = bm25.get_scores(tokenize(query))
    ranked = sorted(zip(corpus, scores), key=lambda x: x[1], reverse=True)
    drop_ratio = min(0.99, max(0.0, float(search_params.get("drop_ratio_search", 0.0))))
    keep_count = max(1, int(len(ranked) * (1.0 - drop_ratio))) if ranked else 0
    ranked = ranked[:keep_count][:top_k]
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
        item["rerank_score"] = float(score)
        item["rerank_rank"] = len(results) + 1
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
    vector_candidate_count: int | None = None,
    sparse_candidate_count: int | None = None,
    rrf_constant: int | None = None,
    rerank_candidate_limit: int | None = None,
    final_top_k: int | None = None,
    sparse_index_params: dict | None = None,
    sparse_search_params: dict | None = None,
    profile_version: int | None = None,
    allow_legacy_bm25_fallback: bool = False,
    shadow_profile_version: int | None = None,
    shadow_sparse_index_params: dict | None = None,
    shadow_sparse_search_params: dict | None = None,
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
    output_limit = final_top_k or top_k
    vector_limit = vector_candidate_count or top_k * 2
    sparse_limit = sparse_candidate_count or top_k * 2
    rerank_limit = rerank_candidate_limit or max(vector_limit, sparse_limit)
    rrf_k = rrf_constant or settings.rrf_k
    corpus = None

    def get_corpus():
        nonlocal corpus
        if corpus is None:
            corpus = corpus_fetcher(kb_id) if corpus_fetcher else fetch_kb_corpus(kb_id)
        return corpus

    def local_bm25(filtered_corpus):
        if sparse_index_params or sparse_search_params:
            return bm25_search(
                filtered_corpus,
                query,
                sparse_limit,
                sparse_index_params,
                sparse_search_params,
            )
        return bm25_search(filtered_corpus, query, sparse_limit)

    shadow_sparse_hits = None
    if shadow_profile_version is not None:
        shadow_sparse_hits = SparseMilvusAdapter(
            kb_id, shadow_profile_version, shadow_sparse_index_params
        ).search(kb_id, query, sparse_limit, shadow_sparse_search_params)

    if not profile_index_ready:
        indexer = MilvusIndexer(dimension=embedding_dimension)
        vector_hits = hydrate_vector_hits(
            indexer.search(kb_id, query_vector, vector_limit), get_corpus())
        filtered_corpus = filter_profile_corpus(get_corpus(), retrieval_profile, False)
        try:
            if profile_version is None:
                raise ValueError("Sparse retrieval requires a profile version")
            sparse_hits = SparseMilvusAdapter(kb_id, profile_version, sparse_index_params).search(
                kb_id, query, sparse_limit, sparse_search_params
            )
        except Exception as exc:
            if (
                profile_version is not None
                and not allow_legacy_bm25_fallback
                and corpus_fetcher is None
            ):
                raise
            logger.warning("Sparse retrieval unavailable; using temporary legacy BM25 fallback: %s", exc)
            sparse_hits = local_bm25(filtered_corpus)
        fused = rrf_fusion(
            vector_hits,
            sparse_hits,
            rrf_k,
        )[:max(vector_limit, sparse_limit)]
    else:
        indexer = MilvusIndexer(
            dimension=embedding_dimension,
            collection_name=settings.milvus_profile_collection,
            profile_schema=True,
        )
        if retrieval_profile == "combined":
            child_vector_hits = hydrate_vector_hits(indexer.search_profile(
                kb_id, query_vector, vector_limit, retrieval_profile, "child"), get_corpus())
            qa_vector_hits = hydrate_vector_hits(indexer.search_profile(
                kb_id, query_vector, vector_limit, retrieval_profile, "qa"), get_corpus())
            child_corpus = filter_profile_corpus(get_corpus(), retrieval_profile, True, "child")
            qa_corpus = filter_profile_corpus(get_corpus(), retrieval_profile, True, "qa")
            child_bm25_hits = local_bm25(child_corpus)
            qa_bm25_hits = local_bm25(qa_corpus)
            fused = weighted_rrf([
                (settings.combined_child_weight, child_vector_hits),
                (settings.combined_child_weight, child_bm25_hits),
                (settings.combined_qa_weight, qa_vector_hits),
                (settings.combined_qa_weight, qa_bm25_hits),
            ], rrf_k)[:max(vector_limit, sparse_limit)]
        elif retrieval_profile == "classic":
            vector_hits = hydrate_vector_hits(
                indexer.search_profile(
                    kb_id, query_vector, vector_limit, retrieval_profile, None
                ),
                get_corpus(),
            )
            try:
                if profile_version is None:
                    raise ValueError("Sparse retrieval requires a profile version")
                sparse_hits = SparseMilvusAdapter(
                    kb_id, profile_version, sparse_index_params
                ).search(kb_id, query, sparse_limit, sparse_search_params)
            except Exception as exc:
                if (
                    profile_version is not None
                    and not allow_legacy_bm25_fallback
                    and corpus_fetcher is None
                ):
                    raise
                logger.warning(
                    "Sparse retrieval unavailable; using temporary legacy BM25 fallback: %s",
                    exc,
                )
                sparse_hits = local_bm25(
                    filter_profile_corpus(get_corpus(), retrieval_profile, True)
                )
            fused = rrf_fusion(vector_hits, sparse_hits, rrf_k)[
                :max(vector_limit, sparse_limit)
            ]
        else:
            entry_kind = "child" if retrieval_profile == "parent-child" else None
            vector_hits = hydrate_vector_hits(indexer.search_profile(
                kb_id, query_vector, vector_limit, retrieval_profile, entry_kind), get_corpus())
            filtered_corpus = filter_profile_corpus(
                get_corpus(), retrieval_profile, True, entry_kind)
            fused = rrf_fusion(
                vector_hits,
                local_bm25(filtered_corpus),
                rrf_k,
            )[:max(vector_limit, sparse_limit)]

    if shadow_sparse_hits is not None:
        shadow_ranks = {hit["chunk_id"]: rank for rank, hit in enumerate(shadow_sparse_hits, start=1)}
        for hit in fused:
            hit["shadow_sparse_rank"] = shadow_ranks.get(hit["chunk_id"])
            if hit["shadow_sparse_rank"] is not None and hit.get("fusion_rank") is not None:
                hit["shadow_rank_delta"] = hit["fusion_rank"] - hit["shadow_sparse_rank"]

    if use_rerank:
        return attach_profile_metadata(
            rerank_hits(query, fused[:rerank_limit], output_limit), retrieval_profile
        )
    return attach_profile_metadata(fused[:output_limit], retrieval_profile)
