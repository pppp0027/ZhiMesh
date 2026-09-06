from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path
from typing import Any

import pandas as pd

from common import load_config, read_jsonl, resolve_path


REFUSAL_MARKERS = ("无法确定", "无法回答", "没有足够", "未提供", "知识库中没有", "无相关信息")


def normalized_name(value: str) -> str:
    return Path(value.replace("\\", "/")).name.strip().lower()


def percentile(values: list[float], ratio: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * ratio
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    weight = index - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def metrics_for(row: dict[str, Any]) -> dict[str, Any]:
    success = row.get("collection_status") == "success"
    gold = [normalized_name(x) for x in row.get("source_documents", [])]
    retrieved = [normalized_name(x) for x in row.get("retrievedDocumentNames", [])]
    matches = set(gold).intersection(retrieved)
    reciprocal_rank = 0.0
    for rank, item in enumerate(retrieved, 1):
        if item in set(gold):
            reciprocal_rank = 1.0 / rank
            break
    answer = str(row.get("answer", ""))
    predicted_refusal = any(marker in answer for marker in REFUSAL_MARKERS)
    is_answerable = bool(row.get("is_answerable", True))
    return {
        "id": row.get("id"),
        "question_type": row.get("question_type"),
        "difficulty": row.get("difficulty"),
        "is_answerable": is_answerable,
        "success": success,
        "document_recall": len(matches) / len(set(gold)) if gold else None,
        "document_hit": bool(matches) if gold else None,
        "mrr": reciprocal_rank if gold else None,
        "refusal_correct": predicted_refusal == (not is_answerable),
        "total_latency_ms": (row.get("timingMs") or {}).get("total"),
        "retrieval_latency_ms": (row.get("timingMs") or {}).get("retrieval"),
        "generation_latency_ms": (row.get("timingMs") or {}).get("generation"),
        "vector_status": next((r.get("status") for r in row.get("routes", []) if r.get("route") == "vector"), None),
        "graph_status": next((r.get("status") for r in row.get("routes", []) if r.get("route") == "graph"), None),
    }


def summarize(records: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [row for row in records if row["success"]]
    scored = [row for row in successful if row["document_recall"] is not None]
    latencies = [float(row["total_latency_ms"]) for row in successful if row["total_latency_ms"] is not None]
    return {
        "samples": len(records),
        "successful": len(successful),
        "error_rate": 1 - len(successful) / len(records) if records else 0,
        "document_recall": statistics.fmean(row["document_recall"] for row in scored) if scored else None,
        "document_hit_rate": statistics.fmean(float(row["document_hit"]) for row in scored) if scored else None,
        "mrr": statistics.fmean(row["mrr"] for row in scored) if scored else None,
        "refusal_accuracy": statistics.fmean(float(row["refusal_correct"]) for row in successful) if successful else None,
        "average_latency_ms": statistics.fmean(latencies) if latencies else None,
        "p50_latency_ms": percentile(latencies, 0.50),
        "p95_latency_ms": percentile(latencies, 0.95),
        "vector_success_rate": statistics.fmean(float(row["vector_status"] == "success") for row in successful) if successful else None,
        "graph_success_rate": statistics.fmean(float(row["graph_status"] == "success") for row in successful) if successful else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="config.json")
    args = parser.parse_args()
    config = load_config(args.config)
    latest: dict[str, dict[str, Any]] = {}
    for row in read_jsonl(config.get("predictions", "output/ragas_predictions.jsonl")):
        latest[str(row.get("id"))] = row
    records = [metrics_for(row) for row in latest.values()]
    output_dir = resolve_path(config.get("report_dir", "output"))
    output_dir.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(records).to_csv(output_dir / "deterministic_per_sample.csv", index=False, encoding="utf-8-sig")
    summary: dict[str, Any] = {"overall": summarize(records), "by_question_type": {}, "by_difficulty": {}}
    for field, target in (("question_type", "by_question_type"), ("difficulty", "by_difficulty")):
        for value in sorted({str(row[field]) for row in records}):
            summary[target][value] = summarize([row for row in records if str(row[field]) == value])
    with (output_dir / "deterministic_summary.json").open("w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)
    print(json.dumps(summary["overall"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
