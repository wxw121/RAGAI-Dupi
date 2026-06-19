# dupi-RAG 示例文档

dupi-RAG 是一个企业级 RAG 知识库引擎，支持 PDF、Word、Excel 等文档上传与智能问答。

## 核心能力

1. **文档摄入**：异步解析、分块、向量化并写入 Milvus。
2. **向量检索**：基于语义相似度召回相关片段。
3. **混合检索（V2）**：BM25 + 向量 RRF 融合，可选 Rerank 二次排序。
4. **流式问答**：SSE 推送检索引用与 LLM 增量输出。

## 分块策略

- `recursive`：按段落与递归字符切分（V1 默认）
- `semantic`：基于句子 Embedding 相似度的语义分块（V2）

## 技术栈

- Java Spring Boot API
- Python Worker
- Milvus + PostgreSQL + Redis + MinIO
