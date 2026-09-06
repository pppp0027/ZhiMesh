from __future__ import annotations

import argparse
import collections
import csv
import json
import math
import re
import statistics
from pathlib import Path
from typing import Any

import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[3]
CORE_SCRIPTS = PROJECT_ROOT / "scripts" / "core"
if str(CORE_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(CORE_SCRIPTS))

from common import load_config, read_jsonl, resolve_path


CATEGORY_LABELS = {
    "A_graph_empty": "A：图检索无候选",
    "B_candidate_pool_miss": "B：候选池未命中参考文档",
    "C_ranking_selection_miss": "C：排序后未选中参考文档",
    "D_partial_reference_coverage": "D：只覆盖部分参考文档",
    "E_all_reference_docs_selected": "E：完整选中参考文档",
    "CONTROL_full_reference_coverage": "对照组：完整覆盖参考文档",
}


def category_label(value: str) -> str:
    return CATEGORY_LABELS.get(value, value)


def transition_label(value: str) -> str:
    before, separator, after = value.partition(" -> ")
    if not separator:
        return category_label(value)
    return f"{category_label(before)} → {category_label(after)}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Analyze graph retrieval-only diagnostic traces")
    parser.add_argument(
        "--config", default="analysis/graph-diagnostics/configs/e2-current.json"
    )
    return parser.parse_args()


def percentile(values: list[float], probability: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * probability
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def normalize(text: Any) -> str:
    return re.sub(r"[\W_]+", "", str(text or "")).lower()


def evidence_matches(evidence: str, content: str) -> bool:
    expected = normalize(evidence)
    actual = normalize(content)
    if len(expected) < 8 or len(actual) < 8:
        return False
    return expected in actual or actual in expected


def classify(row: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    references = set(row.get("source_documents") or [])
    candidates = row.get("candidates") or []
    selected = [candidate for candidate in candidates if candidate.get("selected")]
    pool_sources = {
        name for candidate in candidates for name in candidate.get("sourceDocumentNames") or []
    }
    selected_sources = {
        name for candidate in selected for name in candidate.get("sourceDocumentNames") or []
    }
    graph_route = next(
        (route for route in row.get("routes") or [] if route.get("route") == "graph"), {}
    )
    if graph_route.get("status") == "empty" or not candidates:
        category = "A_graph_empty"
    elif not references.intersection(pool_sources):
        category = "B_candidate_pool_miss"
    elif not references.intersection(selected_sources):
        category = "C_ranking_selection_miss"
    elif not references.issubset(selected_sources):
        category = "D_partial_reference_coverage"
    else:
        category = "E_all_reference_docs_selected"

    evidence_rows = row.get("source_evidence") or []
    evidence_texts = [str(item.get("evidence") or "") for item in evidence_rows if item.get("evidence")]
    pool_contents = [str(candidate.get("content") or "") for candidate in candidates]
    selected_contents = [str(candidate.get("content") or "") for candidate in selected]
    pool_evidence_hits = sum(
        any(evidence_matches(evidence, content) for content in pool_contents) for evidence in evidence_texts
    )
    selected_evidence_hits = sum(
        any(evidence_matches(evidence, content) for content in selected_contents) for evidence in evidence_texts
    )
    return category, {
        "reference_document_count": len(references),
        "pool_reference_document_hits": len(references.intersection(pool_sources)),
        "selected_reference_document_hits": len(references.intersection(selected_sources)),
        "reference_evidence_count": len(evidence_texts),
        "pool_evidence_hits": pool_evidence_hits,
        "selected_evidence_hits": selected_evidence_hits,
        "candidate_count": len(candidates),
        "selected_count": len(selected),
        "graph_route_status": graph_route.get("status"),
        "graph_route_duration_ms": graph_route.get("durationMs"),
    }


def main() -> None:
    args = parse_args()
    config = load_config(args.config)
    manifest_path = resolve_path(config["diagnostic_manifest"])
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    expected_group: dict[str, str] = {}
    for group, definition in manifest["groups"].items():
        for question_id in definition["ids"]:
            if question_id in expected_group:
                raise SystemExit(f"Duplicate diagnostic id: {question_id}")
            expected_group[question_id] = group

    collected_rows = {}
    for row in read_jsonl(config["predictions"]):
        if row.get("collection_status") == "success":
            collected_rows[str(row["id"])] = row
    rows = {question_id: row for question_id, row in collected_rows.items()
            if question_id in expected_group}
    missing = sorted(set(expected_group) - set(rows))
    unexpected = sorted(set(collected_rows) - set(expected_group))
    if missing:
        raise SystemExit(f"Diagnostic run is incomplete; missing {len(missing)} ids: {', '.join(missing)}")

    observed_counts = collections.Counter()
    transitions = collections.Counter()
    by_question_type: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    trace_statuses = collections.Counter()
    per_sample = []
    route_durations: list[float] = []

    for question_id in sorted(expected_group):
        row = rows[question_id]
        observed, measurements = classify(row)
        baseline = expected_group[question_id]
        observed_counts[observed] += 1
        transitions[(baseline, observed)] += 1
        by_question_type[str(row.get("question_type"))][observed] += 1
        trace = row.get("graphTrace") or {}
        trace_statuses[str(trace.get("status"))] += 1
        duration = measurements.get("graph_route_duration_ms")
        if duration is not None:
            route_durations.append(float(duration))
        per_sample.append({
            "id": question_id,
            "baseline_group": baseline,
            "observed_group": observed,
            "question_type": row.get("question_type"),
            "difficulty": row.get("difficulty"),
            **measurements,
            "trace_status": trace.get("status"),
            "direct_entity_count": len(trace.get("directMatchedEntities") or []),
            "llm_entity_count": len(trace.get("llmExtractedEntities") or []),
            "anchor_count": len(trace.get("matchedAnchors") or []),
            "traversed_edge_count": len(trace.get("traversedEdges") or []),
            "resolved_source_segment_count": len(trace.get("resolvedSourceSegmentUuids") or []),
            "entity_extraction_error_type": trace.get("entityExtractionErrorType"),
            "source_resolution_error_type": trace.get("sourceResolutionErrorType"),
        })

    summary = {
        "schema_version": 1,
        "experiment_id": config.get("experiment_id"),
        "expected_samples": len(expected_group),
        "successful_samples": len(rows),
        "missing_ids": missing,
        "unexpected_ids": unexpected,
        "observed_groups": dict(observed_counts),
        "baseline_to_observed": {
            f"{baseline} -> {observed}": count
            for (baseline, observed), count in sorted(transitions.items())
        },
        "by_question_type": {key: dict(value) for key, value in sorted(by_question_type.items())},
        "trace_statuses": dict(trace_statuses),
        "graph_route_latency_ms": {
            "mean": statistics.fmean(route_durations) if route_durations else None,
            "p50": percentile(route_durations, 0.50),
            "p95": percentile(route_durations, 0.95),
        },
        "document_level": {
            "pool_any_hit_rate": statistics.fmean(
                1.0 if row["pool_reference_document_hits"] > 0 else 0.0 for row in per_sample
            ),
            "selected_any_hit_rate": statistics.fmean(
                1.0 if row["selected_reference_document_hits"] > 0 else 0.0 for row in per_sample
            ),
        },
        "evidence_level": {
            "pool_hit_rate": (
                sum(row["pool_evidence_hits"] for row in per_sample)
                / sum(row["reference_evidence_count"] for row in per_sample)
                if sum(row["reference_evidence_count"] for row in per_sample) else None
            ),
            "selected_hit_rate": (
                sum(row["selected_evidence_hits"] for row in per_sample)
                / sum(row["reference_evidence_count"] for row in per_sample)
                if sum(row["reference_evidence_count"] for row in per_sample) else None
            ),
            "matching_rule": "normalized exact containment; conservative for paraphrased graph relations",
        },
        "per_sample": per_sample,
    }

    report_dir = resolve_path(config["report_dir"])
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "graph_diagnostic_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    csv_fields = list(per_sample[0])
    with (report_dir / "graph_diagnostic_per_sample.csv").open(
        "w", encoding="utf-8-sig", newline=""
    ) as handle:
        writer = csv.DictWriter(handle, fieldnames=csv_fields)
        writer.writeheader()
        writer.writerows(per_sample)

    markdown = [
        "# 图谱检索专项诊断报告",
        "",
        f"- 诊断样本：{len(expected_group)}",
        f"- 成功采集：{len(rows)}",
        f"- 图检索延迟 P50：{summary['graph_route_latency_ms']['p50']:.1f} ms",
        f"- 图检索延迟 P95：{summary['graph_route_latency_ms']['p95']:.1f} ms",
        f"- 候选池文档命中率：{summary['document_level']['pool_any_hit_rate']:.4f}",
        f"- 最终选择文档命中率：{summary['document_level']['selected_any_hit_rate']:.4f}",
        "",
        "## 当前分类",
        "",
        "| 分类 | 数量 |",
        "|---|---:|",
    ]
    for group, count in sorted(observed_counts.items()):
        markdown.append(f"| {category_label(group)} | {count} |")
    markdown.extend(["", "## 基线到本次变化", "", "| 变化 | 数量 |", "|---|---:|"])
    for transition, count in summary["baseline_to_observed"].items():
        markdown.append(f"| {transition_label(transition)} | {count} |")
    markdown.extend([
        "",
        "## 说明",
        "",
        "- 文档级命中用于判断来源文档是否进入候选池或最终Top N。",
        "- 证据级命中采用规范化后的精确包含匹配，对图谱关系转述采取保守估计。",
        "- 本报告不保存或展示模型思维链，只记录可观测的检索决策与分数。",
        "",
    ])
    (report_dir / "GRAPH_DIAGNOSTIC_REPORT.zh-CN.md").write_text(
        "\n".join(markdown), encoding="utf-8"
    )
    print(f"samples={len(expected_group)} observed={len(rows)}")
    print("groups=" + json.dumps(dict(observed_counts), ensure_ascii=True, sort_keys=True))
    print(f"json={report_dir / 'graph_diagnostic_summary.json'}")
    print(f"csv={report_dir / 'graph_diagnostic_per_sample.csv'}")
    print(f"markdown={report_dir / 'GRAPH_DIAGNOSTIC_REPORT.zh-CN.md'}")


if __name__ == "__main__":
    main()
