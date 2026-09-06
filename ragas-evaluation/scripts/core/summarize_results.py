from __future__ import annotations

import argparse
import json
import statistics
from typing import Any

import pandas as pd

from common import load_config, read_jsonl, resolve_path


def mean(values: list[float]) -> float | None:
    return statistics.fmean(values) if values else None


def summarize(rows: list[dict[str, Any]], metric_names: list[str]) -> dict[str, Any]:
    result: dict[str, Any] = {"samples": len(rows)}
    for metric in metric_names:
        values = [float(row["ragas"][metric]) for row in rows if row.get("ragas", {}).get(metric) is not None]
        result[metric] = mean(values)
        result[f"{metric}_valid_samples"] = len(values)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Build RAGAS CSV and grouped JSON summaries")
    parser.add_argument("--config", default="config.json")
    args = parser.parse_args()
    config = load_config(args.config)
    score_path = config.get("ragas_scores", "output/ragas_scores.jsonl")
    latest: dict[str, dict[str, Any]] = {}
    for row in read_jsonl(score_path):
        if row.get("ragas_status") == "success":
            latest[str(row["id"])] = row
    rows = list(latest.values())
    if not rows:
        raise SystemExit("No successful RAGAS scores found")
    metric_names = sorted({key for row in rows for key in row.get("ragas", {})})
    flattened = []
    for row in rows:
        flattened.append({
            "id": row.get("id"),
            "question": row.get("question"),
            "question_type": row.get("question_type"),
            "difficulty": row.get("difficulty"),
            "is_answerable": row.get("is_answerable"),
            **row.get("ragas", {}),
        })
    output_dir = resolve_path(config.get("report_dir", "output"))
    output_dir.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(flattened).to_csv(output_dir / "ragas_per_sample.csv", index=False, encoding="utf-8-sig")
    report: dict[str, Any] = {"overall": summarize(rows, metric_names)}
    for field in ("question_type", "difficulty", "is_answerable"):
        report[f"by_{field}"] = {}
        for value in sorted({str(row.get(field)) for row in rows}):
            report[f"by_{field}"][value] = summarize(
                [row for row in rows if str(row.get(field)) == value], metric_names)
    with (output_dir / "ragas_summary.json").open("w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    print(json.dumps(report["overall"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
