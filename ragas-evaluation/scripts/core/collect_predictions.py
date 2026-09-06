from __future__ import annotations

import argparse
import asyncio
import os
import time
from pathlib import Path
from typing import Any

import httpx
from dotenv import load_dotenv

from common import append_jsonl, load_config, read_jsonl, resolve_path


VALID_RETRIEVAL_MODES = {"vector", "graph", "hybrid"}


def experiment_options(config: dict[str, Any]) -> tuple[str, bool]:
    mode = str(config.get("retrieval_mode", "")).strip().lower()
    if mode not in VALID_RETRIEVAL_MODES:
        raise ValueError("retrieval_mode must be one of: vector, graph, hybrid")
    use_reranker = config.get("use_reranker")
    if not isinstance(use_reranker, bool):
        raise ValueError("use_reranker must be true or false")
    return mode, use_reranker


def validate_applied_experiment(
    evaluation: dict[str, Any], retrieval_mode: str, use_reranker: bool,
    retrieval_only: bool = False, include_query_embedding: bool = False,
) -> None:
    snapshot = evaluation.get("configSnapshot") or {}
    if snapshot.get("retrievalMode") != retrieval_mode:
        raise RuntimeError(
            "Backend did not apply retrievalMode: "
            f"expected {retrieval_mode}, got {snapshot.get('retrievalMode')}"
        )
    if snapshot.get("useReranker") != use_reranker:
        raise RuntimeError(
            "Backend did not apply useReranker: "
            f"expected {use_reranker}, got {snapshot.get('useReranker')}"
        )
    if bool(snapshot.get("retrievalOnly")) != retrieval_only:
        raise RuntimeError("Backend retrievalOnly state does not match experiment")
    if bool(snapshot.get("queryEmbeddingIncluded")) != include_query_embedding:
        raise RuntimeError("Backend queryEmbeddingIncluded state does not match experiment")
    if include_query_embedding and not evaluation.get("queryEmbedding"):
        raise RuntimeError("Backend returned no query embedding for router training")
    expected_routes = {
        "vector": {"vector"},
        "graph": {"graph"},
        "hybrid": {"vector", "graph"},
    }[retrieval_mode]
    actual_routes = {str(route.get("route")) for route in evaluation.get("routes", [])}
    if actual_routes != expected_routes:
        raise RuntimeError(
            f"Backend routes mismatch: expected {sorted(expected_routes)}, "
            f"got {sorted(actual_routes)}"
        )
    rerank = evaluation.get("rerank") or {}
    if bool(rerank.get("configured")) != use_reranker:
        raise RuntimeError("Backend reranker configured state does not match experiment")
    if use_reranker and not bool(rerank.get("successful")):
        raise RuntimeError(
            "Reranker did not complete successfully: "
            f"{rerank.get('failureReason') or 'unknown failure'}"
        )
    if retrieval_only and retrieval_mode in {"graph", "hybrid"}:
        graph_trace = evaluation.get("graphTrace")
        if not isinstance(graph_trace, dict) or not graph_trace.get("status"):
            raise RuntimeError("Backend returned no graphTrace for retrieval-only diagnostics")


def load_ids_file(path: str | Path) -> set[str]:
    source = resolve_path(path)
    ids: set[str] = set()
    with source.open(encoding="utf-8-sig") as handle:
        for line in handle:
            value = line.strip()
            if value and not value.startswith("#"):
                ids.add(value)
    if not ids:
        raise ValueError(f"ids file is empty: {source}")
    return ids


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Collect isolated RAG answers for RAGAS")
    parser.add_argument("--config", default="config.json")
    parser.add_argument("--limit", type=int, help="Only process the first N pending rows")
    parser.add_argument("--id", action="append", dest="ids", help="Only process this question id; repeatable")
    parser.add_argument("--ids-file", help="Read question ids from a UTF-8 text file, one id per line")
    parser.add_argument("--retrieval-only", action="store_true", help="Skip answer generation and collect retrieval diagnostics")
    return parser.parse_args()


def unwrap_response(payload: dict[str, Any]) -> dict[str, Any]:
    if "success" in payload:
        if not payload.get("success"):
            raise RuntimeError(f"API error {payload.get('code')}: {payload.get('message')}")
        data = payload.get("data")
        if not isinstance(data, dict):
            raise RuntimeError("API returned no evaluation data")
        return data
    return payload


async def main() -> None:
    args = parse_args()
    load_dotenv(resolve_path(".env"))
    config = load_config(args.config)
    retrieval_mode, use_reranker = experiment_options(config)
    retrieval_only = args.retrieval_only or bool(config.get("retrieval_only", False))
    include_query_embedding = bool(config.get("include_query_embedding", False))
    token = os.getenv("ZHIMESH_ADMIN_TOKEN", "").strip()
    if not token:
        raise SystemExit("ZHIMESH_ADMIN_TOKEN is missing; copy .env.example to .env and fill it")

    dataset = read_jsonl(config["dataset"])
    ids = set(args.ids or [])
    if args.ids_file:
        ids.update(load_ids_file(args.ids_file))
    if ids:
        dataset = [row for row in dataset if row.get("id") in ids]
        found = {str(row.get("id")) for row in dataset}
        missing = sorted(ids - found)
        if missing:
            raise SystemExit(f"Unknown question ids: {', '.join(missing)}")
    output_path = resolve_path(config.get("predictions", "output/ragas_predictions.jsonl"))
    completed: set[str] = set()
    if output_path.exists():
        completed = {
            str(row.get("id")) for row in read_jsonl(output_path)
            if row.get("collection_status") == "success"
        }
    pending = [row for row in dataset if str(row.get("id")) not in completed]
    if args.limit is not None:
        pending = pending[: args.limit]
    print(f"dataset={len(dataset)}, completed={len(completed)}, pending={len(pending)}, retrieval_only={retrieval_only}")
    if not pending:
        return

    concurrency = max(1, min(int(config.get("concurrency", 1)), 4))
    retries = max(0, int(config.get("retries", 1)))
    timeout = httpx.Timeout(float(config.get("timeout_seconds", 90)))
    endpoint = (
        str(config.get("base_url", "http://localhost:9999")).rstrip("/")
        + "/admin/rag-evaluation/ask/"
        + str(config["kb_uuid"])
    )
    semaphore = asyncio.Semaphore(concurrency)
    output_lock = asyncio.Lock()

    async with httpx.AsyncClient(timeout=timeout, headers={"Authorization": token}) as client:
        async def collect(row: dict[str, Any]) -> None:
            question_id = str(row["id"])
            request = {
                "questionId": question_id,
                "question": row["question"],
                "answerModelId": int(config["answer_model_id"]),
                "temperature": float(config.get("temperature", 0.0)),
                "retrievalMode": retrieval_mode,
                "useReranker": use_reranker,
                "retrievalOnly": retrieval_only,
                "includeQueryEmbedding": include_query_embedding,
            }
            started_at = time.perf_counter()
            last_error = ""
            async with semaphore:
                for attempt in range(retries + 1):
                    try:
                        response = await client.post(endpoint, json=request)
                        response.raise_for_status()
                        evaluation = unwrap_response(response.json())
                        validate_applied_experiment(
                            evaluation, retrieval_mode, use_reranker, retrieval_only,
                            include_query_embedding,
                        )
                        result = {
                            **row,
                            **evaluation,
                            "id": question_id,
                            "collection_status": "success",
                            "collection_attempts": attempt + 1,
                            "retrieval_only": retrieval_only,
                            "collector_elapsed_ms": round((time.perf_counter() - started_at) * 1000),
                        }
                        async with output_lock:
                            append_jsonl(output_path, result)
                        print(f"[OK] {question_id} ({result['collector_elapsed_ms']} ms)")
                        return
                    except Exception as exc:  # retry network, HTTP, and malformed response failures
                        last_error = f"{type(exc).__name__}: {exc}"
                        if attempt < retries:
                            await asyncio.sleep(1.5 * (attempt + 1))
            failed = {
                **row,
                "id": question_id,
                "collection_status": "error",
                "collection_attempts": retries + 1,
                "collector_elapsed_ms": round((time.perf_counter() - started_at) * 1000),
                "collection_error": last_error,
            }
            async with output_lock:
                append_jsonl(output_path, failed)
            print(f"[ERROR] {question_id}: {last_error}")

        await asyncio.gather(*(collect(row) for row in pending))


if __name__ == "__main__":
    asyncio.run(main())
