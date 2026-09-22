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
VALID_RETRIEVAL_ROUTES = {"vector", "graph", "bm25"}
EXPECTED_EXPERIMENT_ROUTES = {
    "vector": {"vector"},
    "graph": {"graph"},
    "hybrid": {"vector", "graph"},
    "three_route": {"vector", "graph", "bm25"},
}
ENV_FALLBACK_KEYS = {
    "base_url": "ZHIMESH_API_BASE_URL",
    "kb_uuid": "ZHIMESH_KB_UUID",
    "answer_model_id": "ZHIMESH_ANSWER_MODEL_ID",
    "dataset": "RAGAS_DATASET",
}
BM25_NOT_READY_HINT = (
    "BM25 索引未就绪：请先在服务器上对该知识库的所有文档构建全文索引，再重跑本组实验"
)
BM25_GLOBAL_ERROR_HINT = (
    "后端把具体错误归类为全局异常（B0003）：若该实验请求了 bm25 路由，最常见原因是"
    "全文索引未构建完成或 BM25 未启用——请确认该知识库所有文档的全文索引已构建、"
    "ZHIMESH_BM25_ENABLED=true，并结合后端日志确认具体原因"
)


def normalize_retrieval_routes(raw: Any) -> list[str]:
    if not isinstance(raw, list) or not raw:
        raise ValueError("retrieval_routes must be a non-empty list of route names")
    routes: list[str] = []
    for item in raw:
        route = str(item).strip().lower()
        if route not in VALID_RETRIEVAL_ROUTES:
            raise ValueError("retrieval_routes entries must be one of: vector, graph, bm25")
        if route in routes:
            raise ValueError(f"retrieval_routes contains duplicate route: {route}")
        routes.append(route)
    return routes


def experiment_options(
    config: dict[str, Any],
) -> tuple[str | None, list[str], bool, bool]:
    intent_routing = config.get("intent_routing", False)
    if not isinstance(intent_routing, bool):
        raise ValueError("intent_routing must be true or false")
    mode = str(config.get("retrieval_mode") or "").strip().lower()
    routes_raw = config.get("retrieval_routes")
    if intent_routing:
        # Intent mode: the backend's intent recognizer decides the retrieval
        # routes, so the config must carry none of the explicit routing keys.
        # The request must also omit retrievalMode entirely — an empty string
        # would parse as HYBRID on the backend and trip its mutual-exclusion
        # guard.
        if mode or routes_raw is not None:
            raise ValueError(
                "intent_routing, retrieval_mode, and retrieval_routes are mutually "
                "exclusive; configure exactly one of the three (intent mode requires "
                "retrieval_mode to be null/empty and retrieval_routes to be null)"
            )
        use_reranker = config.get("use_reranker")
        if not isinstance(use_reranker, bool):
            raise ValueError("use_reranker must be true or false")
        return None, [], use_reranker, True
    if mode and routes_raw is not None:
        raise ValueError(
            "retrieval_mode and retrieval_routes are mutually exclusive; "
            "configure exactly one of them"
        )
    if routes_raw is None and mode not in VALID_RETRIEVAL_MODES:
        raise ValueError("retrieval_mode must be one of: vector, graph, hybrid")
    use_reranker = config.get("use_reranker")
    if not isinstance(use_reranker, bool):
        raise ValueError("use_reranker must be true or false")
    if routes_raw is None:
        return mode, [], use_reranker, False
    return None, normalize_retrieval_routes(routes_raw), use_reranker, False


def validate_applied_experiment(
    evaluation: dict[str, Any], retrieval_mode: str | None, use_reranker: bool,
    retrieval_only: bool = False, include_query_embedding: bool = False,
    retrieval_routes: list[str] | None = None, intent_routing: bool = False,
) -> None:
    snapshot = evaluation.get("configSnapshot") or {}
    effective_routes: set[str] = set()
    if intent_routing:
        # Intent mode: the request carried no explicit routing keys, so we
        # validate the backend's own routing decision echo instead of an
        # explicit request echo.
        if snapshot.get("intentRouting") is not True:
            raise RuntimeError(
                "Backend did not apply intent routing: configSnapshot.intentRouting "
                "is missing or not true — the deployed backend predates the "
                "intentRouting field (it silently fell back to HYBRID) or the flag "
                "did not take effect; deploy the new backend before running the "
                "intent experiment"
            )
        intent_echo = evaluation.get("intent")
        if not isinstance(intent_echo, dict):
            raise RuntimeError("Backend returned no intent decision echo")
        missing_intent_keys = [
            key for key in (
                "intent", "proposedRoutes", "effectiveRoutes", "fallback", "reason",
            )
            if key not in intent_echo
        ]
        if missing_intent_keys:
            raise RuntimeError(
                "Backend intent echo is missing required fields: "
                + ", ".join(missing_intent_keys)
            )
        if intent_echo.get("reason") == "intent routing is disabled":
            raise RuntimeError(
                "Server intent routing is disabled: set "
                "ZHIMESH_INTENT_ROUTING_ENABLED=true on the server — "
                "the whole intent experiment group is invalid"
            )
        effective_raw = intent_echo.get("effectiveRoutes")
        if not isinstance(effective_raw, list):
            raise RuntimeError("Backend intent echo has a non-list effectiveRoutes")
        effective_routes = {str(route).strip().lower() for route in effective_raw}
        # An empty effective set is a legal outcome (NO_RAG), but only the
        # no_rag decision may drop every route.
        if not effective_routes and intent_echo.get("intent") != "no_rag":
            raise RuntimeError(
                f"Backend returned empty effectiveRoutes for intent "
                f"{intent_echo.get('intent')!r}; only no_rag may drop every route"
            )
    elif retrieval_routes:
        snapshot_routes = snapshot.get("retrievalRoutes")
        if not isinstance(snapshot_routes, list) or not snapshot_routes:
            raise RuntimeError(
                "Backend did not echo configSnapshot.retrievalRoutes for the routes experiment"
            )
        applied_routes = {str(route).strip().lower() for route in snapshot_routes}
        expected_snapshot_routes = set(retrieval_routes)
        if applied_routes != expected_snapshot_routes:
            raise RuntimeError(
                "Backend did not apply retrievalRoutes: expected "
                f"{sorted(expected_snapshot_routes)}, got {sorted(applied_routes)}"
            )
    elif snapshot.get("retrievalMode") != retrieval_mode:
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
    if intent_routing:
        # The expected set is dynamic: whatever routes the backend decided
        # must be exactly the routes it executed (lowercase-normalized).
        expected_routes = effective_routes
        actual_routes = {
            str(route.get("route")).strip().lower()
            for route in evaluation.get("routes", [])
        }
        if actual_routes != expected_routes:
            raise RuntimeError(
                "Intent routing echo mismatch: executed routes "
                f"{sorted(actual_routes)} != intent.effectiveRoutes "
                f"{sorted(expected_routes)}"
            )
    else:
        if retrieval_routes:
            expected_routes = set(retrieval_routes)
        else:
            expected_routes = EXPECTED_EXPERIMENT_ROUTES[retrieval_mode]
        actual_routes = {str(route.get("route")) for route in evaluation.get("routes", [])}
        if actual_routes != expected_routes:
            raise RuntimeError(
                f"Backend routes mismatch: expected {sorted(expected_routes)}, "
                f"got {sorted(actual_routes)}"
            )
    # A requested route must finish cleanly. The backend reports per-route
    # status as success/empty/timeout/error; empty is a healthy no-candidate
    # run, while timeout/error mean the sample silently lost that route.
    route_rows = {str(route.get("route")): route for route in evaluation.get("routes", [])}
    for route_name in sorted(expected_routes):
        row = route_rows.get(route_name) or {}
        status = str(row.get("status") or "").lower()
        if status in {"timeout", "error"}:
            detail = row.get("errorType") or row.get("errorMessage") or "unknown"
            raise RuntimeError(
                f"Retrieval route {route_name} did not finish cleanly: "
                f"status={status}, detail={detail}"
            )
    rerank = evaluation.get("rerank") or {}
    if bool(rerank.get("configured")) != use_reranker:
        raise RuntimeError("Backend reranker configured state does not match experiment")
    if use_reranker and not bool(rerank.get("successful")):
        raise RuntimeError(
            "Reranker did not complete successfully: "
            f"{rerank.get('failureReason') or 'unknown failure'}"
        )
    if retrieval_only and "graph" in expected_routes:
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


def bm25_not_ready_hint(exc: Exception, bm25_requested: bool = False) -> str:
    """Return a BM25 hint when a backend error looks like a missing full-text index.

    The backend's global exception handler replaces the specific BM25 readiness
    message with a generic B0003 response, so a requested-bm25 experiment that
    fails with the generic code also gets a (conditional) hint.
    """
    if isinstance(exc, httpx.HTTPStatusError) and exc.response is not None:
        try:
            text = exc.response.text or ""
        except Exception:
            text = ""
    else:
        text = str(exc)
    lowered = text.lower()
    if "bm25" in lowered and "not ready" in lowered:
        return BM25_NOT_READY_HINT
    if bm25_requested and ("b0003" in lowered or "全局异常" in text):
        return BM25_GLOBAL_ERROR_HINT
    return ""


def apply_env_fallbacks(config: dict[str, Any]) -> None:
    """Fill null or missing deployment keys from the environment (.env already loaded)."""
    for key, env_name in ENV_FALLBACK_KEYS.items():
        value = config.get(key)
        if value is not None and str(value).strip():
            continue  # explicit config values keep precedence
        env_value = os.getenv(env_name, "").strip()
        if env_value:
            config[key] = env_value
            print(f"{key}: null or missing in config, using {env_name} from environment")


async def main() -> None:
    args = parse_args()
    load_dotenv(resolve_path(".env"))
    config = load_config(args.config)
    apply_env_fallbacks(config)
    missing = [
        key for key in ("kb_uuid", "answer_model_id", "dataset")
        if not str(config.get(key) or "").strip()
    ]
    if missing:
        raise SystemExit(
            "missing required config values: "
            + "; ".join(
                f"{key} (set the config key or env {ENV_FALLBACK_KEYS[key]})"
                for key in missing
            )
        )
    if not str(config.get("base_url") or "").strip():
        config["base_url"] = "http://localhost:9999"
    try:
        retrieval_mode, retrieval_routes, use_reranker, intent_routing = (
            experiment_options(config)
        )
    except ValueError as exc:
        raise SystemExit(f"invalid experiment config: {exc}")
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
    if retrieval_routes:
        print(f"retrieval_routes={'+'.join(retrieval_routes)}")
    if intent_routing:
        print("intent_routing=True（由后端意图识别决策检索路由）")
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
            request: dict[str, Any] = {
                "questionId": question_id,
                "question": row["question"],
                "answerModelId": int(config["answer_model_id"]),
                "temperature": float(config.get("temperature", 0.0)),
            }
            if intent_routing:
                # Intent mode must omit both retrievalMode and retrievalRoutes:
                # an empty-string retrievalMode would parse as HYBRID on the
                # backend and trip its mutual-exclusion guard. The backend's
                # intent recognizer decides the routes.
                request["intentRouting"] = True
            elif retrieval_routes:
                request["retrievalRoutes"] = list(retrieval_routes)
            else:
                request["retrievalMode"] = retrieval_mode
            request["useReranker"] = use_reranker
            request["retrievalOnly"] = retrieval_only
            request["includeQueryEmbedding"] = include_query_embedding
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
                            include_query_embedding, retrieval_routes, intent_routing,
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
                        hint = bm25_not_ready_hint(exc, bm25_requested="bm25" in retrieval_routes)
                        if hint:
                            last_error = f"{last_error}；{hint}"
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
