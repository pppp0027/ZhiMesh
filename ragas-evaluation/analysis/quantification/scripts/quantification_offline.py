from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[3]
RAGAS_METRICS = [
    "llm_context_precision_with_reference",
    "context_recall",
    "faithfulness",
    "answer_relevancy",
    "answer_accuracy",
]
DEFAULT_EXPERIMENTS = [
    "E1=experiments/before-optimization/results/e1-vector",
    "E2-OPT=experiments/after-optimization/results/e2-graph-optimized",
    "E3-FINAL=experiments/after-optimization/results/e3-hybrid-final",
    "E4-FINAL=experiments/after-optimization/results/e4-hybrid-rerank-final",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build dimension-1 blocking latency and dimension-3 cost/benefit metrics"
    )
    parser.add_argument(
        "--experiment",
        action="append",
        help="ID=experiment_directory; repeatable. Defaults to E1 plus optimized E2-E4.",
    )
    parser.add_argument(
        "--output-dir",
        default="analysis/quantification/results/runs/offline-final",
        help="Output directory relative to ragas-evaluation unless absolute",
    )
    return parser.parse_args()


def resolve(path: str | Path) -> Path:
    value = Path(path)
    return value if value.is_absolute() else ROOT / value


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8-sig") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSONL at {path}:{line_number}: {exc}") from exc
    return rows


def latest_by_id(rows: Iterable[dict[str, Any]], predicate) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        sample_id = row.get("id")
        if sample_id is not None and predicate(row):
            result[str(sample_id)] = row
    return result


def finite(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def nearest_rank(values: Iterable[float], probability: float) -> float | None:
    ordered = sorted(values)
    if not ordered:
        return None
    index = max(0, math.ceil(probability * len(ordered)) - 1)
    return ordered[index]


def distribution(values: Iterable[float]) -> dict[str, float | int | None]:
    data = [value for value in values if value is not None and math.isfinite(value)]
    return {
        "count": len(data),
        "mean": statistics.fmean(data) if data else None,
        "p50": nearest_rank(data, 0.50),
        "p90": nearest_rank(data, 0.90),
        "p95": nearest_rank(data, 0.95),
        "max": max(data) if data else None,
    }


def metadata_values(value: Any) -> set[str]:
    if value is None:
        return set()
    if isinstance(value, (list, tuple, set)):
        return {str(item) for item in value if item is not None}
    return {part.strip() for part in str(value).split(",") if part.strip()}


def candidate_doc_ids(candidate: dict[str, Any]) -> set[str]:
    return metadata_values(candidate.get("sourceDocumentIds"))


def route_values(routes: list[dict[str, Any]], route_name: str, field: str) -> list[float]:
    result: list[float] = []
    for route in routes:
        if str(route.get("route")) == route_name:
            value = finite(route.get(field))
            if value is not None:
                result.append(value)
    return result


def sample_row(
    experiment_id: str,
    prediction: dict[str, Any],
    score: dict[str, Any] | None,
) -> dict[str, Any]:
    candidates = prediction.get("candidates") or []
    routes = prediction.get("routes") or []
    selected = [candidate for candidate in candidates if candidate.get("selected") is True]
    raw_route_candidates = sum(
        int(finite(route.get("candidateCount")) or 0) for route in routes
    )
    merged_candidates = len(candidates)
    duplicate_candidates = max(0, raw_route_candidates - merged_candidates)
    candidate_tokens = sum(
        int(finite(candidate.get("tokenCount")) or 0) for candidate in candidates
    )
    selected_tokens = sum(
        int(finite(candidate.get("tokenCount")) or 0) for candidate in selected
    )
    source_documents = {str(item) for item in prediction.get("source_documents") or []}
    retrieved_names = {str(item) for item in prediction.get("retrievedDocumentNames") or []}
    ragas = (score or {}).get("ragas") or {}
    timing = prediction.get("timingMs") or {}
    usage = prediction.get("usage") or {}
    rerank = prediction.get("rerank") or {}
    vector_ms = route_values(routes, "vector", "durationMs")
    graph_ms = route_values(routes, "graph", "durationMs")
    return {
        "id": str(prediction.get("id")),
        "experiment_id": experiment_id,
        "question_type": prediction.get("question_type"),
        "difficulty": prediction.get("difficulty"),
        "is_answerable": prediction.get("is_answerable"),
        "collection_attempts": prediction.get("collection_attempts"),
        "retrieval_ms": finite(timing.get("retrieval")),
        "generation_ms": finite(timing.get("generation")),
        "total_ms": finite(timing.get("total")),
        "collector_elapsed_ms": finite(prediction.get("collector_elapsed_ms")),
        "network_client_overhead_ms": (
            finite(prediction.get("collector_elapsed_ms")) - finite(timing.get("total"))
            if finite(prediction.get("collector_elapsed_ms")) is not None
            and finite(timing.get("total")) is not None
            else None
        ),
        "vector_route_ms": vector_ms[0] if vector_ms else None,
        "graph_route_ms": graph_ms[0] if graph_ms else None,
        "raw_route_candidates": raw_route_candidates,
        "merged_candidates": merged_candidates,
        "duplicate_candidates": duplicate_candidates,
        "dedup_rate": (
            duplicate_candidates / raw_route_candidates if raw_route_candidates else None
        ),
        "selected_candidates": len(selected),
        "candidate_selection_rate": (
            len(selected) / merged_candidates if merged_candidates else None
        ),
        "candidate_tokens": candidate_tokens,
        "selected_context_tokens": selected_tokens,
        "context_compression_rate": (
            1 - selected_tokens / candidate_tokens if candidate_tokens else None
        ),
        "rerank_ms": finite(rerank.get("durationMs")),
        "rerank_configured": rerank.get("configured"),
        "rerank_success": rerank.get("successful"),
        "input_tokens": finite(usage.get("inputTokens")),
        "output_tokens": finite(usage.get("outputTokens")),
        "total_tokens": finite(usage.get("totalTokens")),
        "any_reference_source_hit": bool(source_documents & retrieved_names)
        if source_documents
        else None,
        "full_reference_source_hit": source_documents <= retrieved_names
        if source_documents
        else None,
        "ragas_status": (score or {}).get("ragas_status"),
        **{metric: finite(ragas.get(metric)) for metric in RAGAS_METRICS},
    }


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = list(rows[0]) if rows else []
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        if fieldnames:
            writer.writeheader()
            writer.writerows(rows)


def build_summary(experiment_id: str, rows: list[dict[str, Any]], raw_count: int) -> dict[str, Any]:
    numeric_fields = [
        "retrieval_ms",
        "generation_ms",
        "total_ms",
        "collector_elapsed_ms",
        "network_client_overhead_ms",
        "vector_route_ms",
        "graph_route_ms",
        "raw_route_candidates",
        "merged_candidates",
        "dedup_rate",
        "selected_candidates",
        "candidate_selection_rate",
        "candidate_tokens",
        "selected_context_tokens",
        "context_compression_rate",
        "rerank_ms",
        "input_tokens",
        "output_tokens",
        "total_tokens",
        *RAGAS_METRICS,
    ]
    summary: dict[str, Any] = {
        "experiment_id": experiment_id,
        "prediction_rows": raw_count,
        "unique_success_predictions": len(rows),
        "prediction_success_rate_against_121": len(rows) / 121,
        "retried_samples": sum((int(row.get("collection_attempts") or 1) > 1) for row in rows),
        "any_reference_source_hit_count": sum(
            row.get("any_reference_source_hit") is True for row in rows
        ),
        "full_reference_source_hit_count": sum(
            row.get("full_reference_source_hit") is True for row in rows
        ),
        "rerank_success_count": sum(row.get("rerank_success") is True for row in rows),
        "ragas_full_sample_count": sum(
            all(row.get(metric) is not None for metric in RAGAS_METRICS) for row in rows
        ),
    }
    for field in numeric_fields:
        summary[field] = distribution(
            float(row[field]) for row in rows if finite(row.get(field)) is not None
        )
    return summary


def flat_summary(summary: dict[str, Any]) -> dict[str, Any]:
    row: dict[str, Any] = {}
    for key, value in summary.items():
        if isinstance(value, dict):
            for stat, stat_value in value.items():
                row[f"{key}_{stat}"] = stat_value
        else:
            row[key] = value
    return row


def paired_differences(
    left_id: str,
    left_rows: dict[str, dict[str, Any]],
    right_id: str,
    right_rows: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    fields = [
        "retrieval_ms",
        "generation_ms",
        "total_ms",
        "selected_context_tokens",
        "input_tokens",
        "output_tokens",
        *RAGAS_METRICS,
    ]
    result: list[dict[str, Any]] = []
    for sample_id in sorted(set(left_rows) & set(right_rows)):
        left = left_rows[sample_id]
        right = right_rows[sample_id]
        row: dict[str, Any] = {
            "id": sample_id,
            "left_experiment": left_id,
            "right_experiment": right_id,
        }
        for field in fields:
            left_value = finite(left.get(field))
            right_value = finite(right.get(field))
            row[f"left_{field}"] = left_value
            row[f"right_{field}"] = right_value
            row[f"delta_{field}"] = (
                right_value - left_value
                if left_value is not None and right_value is not None
                else None
            )
        result.append(row)
    return result


def main() -> None:
    args = parse_args()
    specs = args.experiment or DEFAULT_EXPERIMENTS
    output_dir = resolve(args.output_dir)
    all_samples: list[dict[str, Any]] = []
    summaries: list[dict[str, Any]] = []
    indexed: dict[str, dict[str, dict[str, Any]]] = {}

    for spec in specs:
        if "=" not in spec:
            raise SystemExit(f"Invalid --experiment {spec!r}; expected ID=directory")
        experiment_id, directory_value = spec.split("=", 1)
        directory = resolve(directory_value)
        prediction_rows = read_jsonl(directory / "predictions.jsonl")
        score_rows = read_jsonl(directory / "scores.jsonl")
        predictions = latest_by_id(
            prediction_rows, lambda row: row.get("collection_status") == "success"
        )
        scores = latest_by_id(
            score_rows,
            lambda row: isinstance(row.get("ragas"), dict),
        )
        samples = [
            sample_row(experiment_id, prediction, scores.get(sample_id))
            for sample_id, prediction in sorted(predictions.items())
        ]
        indexed[experiment_id] = {row["id"]: row for row in samples}
        all_samples.extend(samples)
        summaries.append(build_summary(experiment_id, samples, len(prediction_rows)))

    write_csv(output_dir / "operational_metrics_per_sample.csv", all_samples)
    write_csv(
        output_dir / "operational_metrics_summary.csv",
        [flat_summary(summary) for summary in summaries],
    )
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "operational_metrics_summary.json").write_text(
        json.dumps(summaries, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    pairs = [
        ("E1", "E2-OPT"),
        ("E1", "E3-FINAL"),
        ("E3-FINAL", "E4-FINAL"),
        ("E1", "E4-FINAL"),
    ]
    for left_id, right_id in pairs:
        if left_id in indexed and right_id in indexed:
            write_csv(
                output_dir / f"{right_id.lower()}_minus_{left_id.lower()}.csv",
                paired_differences(
                    left_id, indexed[left_id], right_id, indexed[right_id]
                ),
            )
    print(f"experiments={len(summaries)}, samples={len(all_samples)}")
    print(f"output={output_dir}")


if __name__ == "__main__":
    main()
