# bge-reranker-base 部署说明

This project invokes `bge-reranker-base` through an OpenAI-compatible rerank endpoint:

```text
POST {baseUrl}/v1/rerank
Authorization: Bearer {apiKey}  # optional
Content-Type: application/json

{
  "model": "bge-reranker-base",
  "query": "user question",
  "documents": ["candidate 1", "candidate 2"],
  "top_n": 5
}
```

The response must include an indexed score list:

```json
{
  "results": [
    {"index": 1, "relevance_score": 0.96},
    {"index": 0, "relevance_score": 0.74}
  ]
}
```

## Application configuration

1. Deploy `BAAI/bge-reranker-base` with a server that supports the endpoint above, such as Xinference or vLLM.
2. Add a model platform whose `baseUrl` points to that server, for example `http://127.0.0.1:9997`.
3. Add an enabled AI model with `type=rerank`, `name=bge-reranker-base`, and that platform name.
4. Set `rerankModelId` and `rerankTopN` (recommended `5`) when editing a knowledge base.
5. Run `db_migration/012_bge_rerank.sql` before using the new fields on an existing database.

When no rerank model is selected, or when its endpoint is temporarily unavailable, retrieval remains available and uses deduplicated context without reranking.
