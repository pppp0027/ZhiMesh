"""Summarize intent-routing decision quality from the N6 predictions file.

Input: the N6 intent experiment ``predictions.jsonl`` (one JSON object per
line = flattened dataset row + flattened backend evaluation echo + collector
bookkeeping ``id/collection_status/collection_attempts/retrieval_only/
collector_elapsed_ms``). Failure rows carry ``collection_status == "error"``
plus ``collection_error`` and have no ``intent``/``configSnapshot``.

Outputs (written to ``--output-dir``):

- ``INTENT_ROUTING_REPORT.zh-CN.md``  human-readable decision-quality report
- ``intent_routing_summary.json``      machine-readable metrics (rates are fractions in [0, 1])
- ``intent_routing_per_sample.csv``   per-sample decision table (UTF-8 BOM for Excel)

Metric definitions (restated in the report header):

- Routing gray band    : ``intent == "uncertain"`` or ``fallback == true``.
- Preflight gray band  : ``intent.scopePreflight.status == "uncertain"``. The
  evaluation-only preflight path folds UNRELATED into UNCERTAIN, so on N6
  data this metric carries union semantics (评测端灰带含 UNRELATED 并集语义).
- Mis-routing baseline : single_document_fact / single_document_relation
  require ``{vector}``; cross_document / graph_multihop / temporal_comparison
  require ``{graph}``; unanswerable rows do not participate (their correct
  refusal rate ``P(empty effectiveRoutes | unanswerable)`` is reported
  instead).
- "True recognition" histogram subset: ``recognizer`` not in
  ``{disabled, fallback, explicit, unknown, None}``. Those rows carry a
  constant confidence of 0 / null and would pollute the distribution.
- Empty ``effectiveRoutes`` is a legal NO_RAG outcome (intent must equal
  ``no_rag``; the collector enforces this on the fly).

Standard library only; Python 3.10 compatible (no 3.11+ features). Run from
any working directory: relative paths resolve against ragas-evaluation ROOT.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_OUTPUT_DIR = "analysis/intent-routing/results/runs/n6-intent"
REPORT_FILENAME = "INTENT_ROUTING_REPORT.zh-CN.md"
SUMMARY_FILENAME = "intent_routing_summary.json"
CSV_FILENAME = "intent_routing_per_sample.csv"

RETRIEVAL_ROUTES = ("vector", "graph", "bm25")
REQUIRED_ROUTES: dict[str, set[str]] = {
    "single_document_fact": {"vector"},
    "single_document_relation": {"vector"},
    "cross_document": {"graph"},
    "graph_multihop": {"graph"},
    "temporal_comparison": {"graph"},
}
SINGLE_DOCUMENT_TYPES = ("single_document_fact", "single_document_relation")
NON_RECOGNIZERS = {"disabled", "fallback", "explicit", "unknown", None}
CSV_COLUMNS = [
    "id", "question_type", "is_answerable", "collection_status", "intent",
    "recognizer", "confidence", "margin", "fallback", "reason",
    "recognition_reason", "scope_status", "available_routes", "proposed_routes",
    "effective_routes", "routes_echo_mismatch", "required_missing",
    "route_count", "no_rag",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Summarize intent-routing decision quality from N6 predictions.jsonl",
    )
    parser.add_argument(
        "--predictions", required=True,
        help="N6 predictions.jsonl; relative paths resolve against ragas-evaluation ROOT",
    )
    parser.add_argument(
        "--dataset",
        help=(
            "Optional dataset jsonl (e.g. datasets/ragas_eval_dataset_final.jsonl); "
            "only used to fill question_type/is_answerable on rows missing them"
        ),
    )
    parser.add_argument(
        "--min-top-score", type=float, default=0.78,
        help="Confidence histogram bucket edge; mirrors the backend min-top-score threshold (default 0.78)",
    )
    parser.add_argument(
        "--min-score-margin", type=float, default=0.05,
        help="Margin histogram bucket edge; mirrors the backend min-score-margin threshold (default 0.05)",
    )
    parser.add_argument(
        "--output-dir", default=DEFAULT_OUTPUT_DIR,
        help=f"Output directory (default {DEFAULT_OUTPUT_DIR}); relative paths resolve against ROOT",
    )
    args = parser.parse_args()
    if not 0.0 < args.min_top_score < 1.0:
        raise SystemExit("--min-top-score must be within (0, 1)")
    if args.min_score_margin < 0.0:
        raise SystemExit("--min-score-margin must be >= 0")
    return args


def resolve(path: str | Path) -> Path:
    candidate = Path(path)
    return candidate if candidate.is_absolute() else ROOT / candidate


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8-sig") as handle:
        for line_number, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_number}: invalid JSON line: {exc}")
            if isinstance(row, dict):
                rows.append(row)
    return rows


def load_dataset_index(path: Path) -> dict[str, dict[str, Any]]:
    return {str(row.get("id")): row for row in read_jsonl(path) if row.get("id") is not None}


def rate(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def fmt_pct(value: float | None, numerator: int = 0, denominator: int = 0) -> str:
    if value is None:
        return "n/a"
    if denominator:
        return f"{100 * value:.1f}% ({numerator}/{denominator})"
    return f"{100 * value:.1f}%"


def fmt_float(value: float | None) -> str:
    return "" if value is None else f"{value:g}"


# ---------------------------------------------------------------------------
# Histograms (left-closed right-open buckets; the final bucket is closed /
# open-ended as documented per metric)
# ---------------------------------------------------------------------------

def confidence_bucket_spec(min_top: float) -> list[tuple[str, float | None, float | None, bool]]:
    """(label, lo, hi, hi_inclusive); lo=None marks the "<0" catch-all."""
    edges = sorted({round(edge, 6) for edge in (0.0, 0.20, 0.40, 0.60, min_top, 0.85, 0.90, 0.95, 1.0)})
    buckets: list[tuple[str, float | None, float | None, bool]] = [("<0", None, None, False)]
    for index, (lo, hi) in enumerate(zip(edges, edges[1:])):
        last = index == len(edges) - 2
        label = f"[{lo:.2f},{hi:.2f}]" if last else f"[{lo:.2f},{hi:.2f})"
        buckets.append((label, lo, hi, last))
    return buckets


def margin_bucket_spec(min_margin: float) -> list[tuple[str, float | None, float | None, bool]]:
    """(label, lo, hi, hi_inclusive); final bucket is [0.40,+∞)."""
    edges = sorted({round(edge, 6) for edge in (0.0, min_margin, 0.10, 0.20, 0.40)})
    buckets: list[tuple[str, float | None, float | None, bool]] = [("<0", None, None, False)]
    for index, (lo, hi) in enumerate(zip(edges, edges[1:])):
        last = index == len(edges) - 2
        buckets.append((f"[{lo:.2f},{hi:.2f})", lo, hi, False))
    buckets.append((f"[{edges[-1]:.2f},+∞)", edges[-1], None, False))
    return buckets


def histogram(values: list[float],
              spec: list[tuple[str, float | None, float | None, bool]]) -> dict[str, int]:
    """Count values into (label, lo, hi, hi_inclusive) buckets.

    Semantics per entry: ``lo is None and hi is None`` matches ``value < 0``
    (the leading catch-all); ``hi is None`` matches ``value >= lo`` (the
    trailing open-ended bucket); otherwise left-closed right-open, except a
    ``hi_inclusive`` entry which is left/right closed. Values above the last
    edge (e.g. confidence > 1.0) fall into the final bucket.
    """
    counts = {label: 0 for label, *_ in spec}
    for value in values:
        for label, lo, hi, hi_inclusive in spec:
            if lo is None and hi is None:
                matched = value < 0
            elif hi is None:
                matched = value >= lo  # type: ignore[operator]
            elif hi_inclusive:
                matched = lo <= value <= hi
            else:
                matched = lo <= value < hi
            if matched:
                counts[label] += 1
                break
        else:
            counts[spec[-1][0]] += 1  # value above the last edge
    return counts


# ---------------------------------------------------------------------------
# Per-sample extraction
# ---------------------------------------------------------------------------

class Sample:
    __slots__ = (
        "row", "row_id", "question_type", "is_answerable", "status", "intent",
        "recognizer", "confidence", "margin", "fallback", "reason",
        "recognition_reason", "scope_status", "available_routes",
        "proposed_routes", "effective_routes", "route_names",
        "routes_echo_mismatch", "required_missing", "route_count", "no_rag",
    )

    def __init__(self, row: dict[str, Any]) -> None:
        intent = row.get("intent") if isinstance(row.get("intent"), dict) else {}
        self.row = row
        self.row_id = str(row.get("id") or "")
        self.question_type = str(row.get("question_type") or "")
        self.is_answerable: bool | None = row.get("is_answerable")
        if not isinstance(self.is_answerable, bool):
            self.is_answerable = None
        self.status = str(row.get("collection_status") or "")
        self.intent = str(intent.get("intent") or "") or None
        self.recognizer = intent.get("recognizer")
        self.confidence = intent.get("confidence")
        if not isinstance(self.confidence, (int, float)):
            self.confidence = None
        self.margin = intent.get("margin")
        if not isinstance(self.margin, (int, float)):
            self.margin = None
        fallback = intent.get("fallback")
        self.fallback = fallback if isinstance(fallback, bool) else None
        self.reason = intent.get("reason")
        self.recognition_reason = intent.get("recognitionReason")
        preflight = intent.get("scopePreflight")
        self.scope_status = (
            str(preflight.get("status") or "") or None
            if isinstance(preflight, dict) else None
        )
        self.available_routes = normalized_routes(intent.get("availableRoutes"))
        self.proposed_routes = normalized_routes(intent.get("proposedRoutes"))
        self.effective_routes = normalized_routes(intent.get("effectiveRoutes"))
        route_rows = row.get("routes") if isinstance(row.get("routes"), list) else []
        self.route_names = {
            str(entry.get("route")) for entry in route_rows
            if isinstance(entry, dict) and entry.get("route")
        }
        self.routes_echo_mismatch = (
            self.route_names != set(self.effective_routes)
            if self.status == "success" else None
        )
        required = REQUIRED_ROUTES.get(self.question_type)
        if self.status == "success" and required is not None:
            self.required_missing = not required.issubset(set(self.effective_routes))
        else:
            self.required_missing = None
        self.route_count = len(self.effective_routes) if self.status == "success" else None
        self.no_rag = self.status == "success" and not self.effective_routes

    @property
    def routing_gray(self) -> bool:
        # intent == "uncertain" OR fallback == true (either signal alone counts).
        return self.intent == "uncertain" or self.fallback is True

    @property
    def true_recognition(self) -> bool:
        return self.status == "success" and self.recognizer not in NON_RECOGNIZERS

    def csv_row(self) -> list[str]:
        def flag(value: bool | None) -> str:
            return "" if value is None else ("true" if value else "false")

        return [
            self.row_id,
            self.question_type,
            "" if self.is_answerable is None else ("true" if self.is_answerable else "false"),
            self.status,
            self.intent or "",
            "" if self.recognizer is None else str(self.recognizer),
            fmt_float(self.confidence),
            fmt_float(self.margin),
            flag(self.fallback),
            "" if self.reason is None else str(self.reason),
            "" if self.recognition_reason is None else str(self.recognition_reason),
            self.scope_status or "",
            "+".join(self.available_routes),
            "+".join(self.proposed_routes),
            "+".join(self.effective_routes),
            flag(self.routes_echo_mismatch),
            flag(self.required_missing),
            "" if self.route_count is None else str(self.route_count),
            flag(self.no_rag),
        ]


def normalized_routes(raw: Any) -> list[str]:
    if not isinstance(raw, list):
        return []
    return [str(item).strip().lower() for item in raw if str(item).strip()]


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------

def build_summary(samples: list[Sample], args: argparse.Namespace,
                  predictions_path: Path, dataset_path: Path | None,
                  filled_count: int) -> dict[str, Any]:
    success = [s for s in samples if s.status == "success"]
    errors = [s for s in samples if s.status != "success"]
    summary: dict[str, Any] = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "inputs": {
            "predictions": str(predictions_path),
            "dataset": str(dataset_path) if dataset_path else None,
            "rows_filled_from_dataset": filled_count,
        },
        "thresholds": {
            "min_top_score": args.min_top_score,
            "min_score_margin": args.min_score_margin,
        },
        "collection": {
            "total": len(samples),
            "success": len(success),
            "error": len(errors),
            "error_rate": rate(len(errors), len(samples)),
            "error_by_question_type": dict(sorted(Counter(
                s.question_type or "(unknown)" for s in errors).items())),
            "error_examples": [
                {"id": s.row_id, "error": str(s.row.get("collection_error") or "")[:300]}
                for s in errors[:5]
            ],
        },
    }

    # (2) intent distribution + routing gray band
    intent_counts = Counter(
        s.intent or "(missing)" if s.status == "success" else "__error__"
        for s in samples
    )
    success_rows = [s for s in success]
    uncertain = [s for s in success_rows if s.intent == "uncertain"]
    fallback_rows = [s for s in success_rows if s.fallback is True]
    both = [s for s in success_rows if s.intent == "uncertain" and s.fallback is True]
    fallback_reasons = Counter(
        str(s.recognition_reason or "(empty)") for s in fallback_rows
    )
    summary["intent_distribution"] = dict(sorted(intent_counts.items()))
    routing_gray_rows = [s for s in success_rows if s.routing_gray]
    summary["gray_band"] = {
        "sample_count": len(success_rows),
        "routing_gray_count": len(routing_gray_rows),
        "routing_gray_rate": rate(len(routing_gray_rows), len(success_rows)),
        "uncertain_count": len(uncertain),
        "uncertain_rate": rate(len(uncertain), len(success_rows)),
        "fallback_count": len(fallback_rows),
        "fallback_rate": rate(len(fallback_rows), len(success_rows)),
        "intersection_count": len(both),
        "intersection_rate": rate(len(both), len(success_rows)),
        "fallback_recognition_reasons": dict(sorted(
            fallback_reasons.items(), key=lambda kv: (-kv[1], kv[0]))[:10]),
        "no_rag_count": sum(1 for s in success_rows if s.no_rag),
    }

    # (3) preflight status + 2x2 contingency
    scope_counts = Counter(
        s.scope_status or "(missing)" for s in success_rows
    )
    uncertain_scope = [s for s in success_rows if s.scope_status == "uncertain"]
    table: dict[str, dict[str, int]] = {}
    for gray_label, gray_match in (("gray", True), ("not_gray", False)):
        table[gray_label] = {
            col_label: sum(
                1 for s in success_rows
                if s.routing_gray == gray_match
                and (s.scope_status == "uncertain") == col_match
            )
            for col_label, col_match in (("uncertain", True), ("not_uncertain", False))
        }
        table[gray_label]["total"] = sum(table[gray_label].values())
    summary["preflight"] = {
        "sample_count": len(success_rows),
        "uncertain_rate": rate(len(uncertain_scope), len(success_rows)),
        "status_distribution": dict(sorted(scope_counts.items())),
        "routing_gray_x_preflight_gray": table,
    }

    # (4) confidence / margin histograms, all samples vs true recognition
    conf_spec = confidence_bucket_spec(args.min_top_score)
    margin_spec = margin_bucket_spec(args.min_score_margin)

    confidence_all = [
        s.confidence for s in success_rows if s.confidence is not None]
    confidence_true = [
        s.confidence for s in success_rows if s.true_recognition and s.confidence is not None]
    margin_all = [s.margin for s in success_rows if s.margin is not None]
    margin_true = [
        s.margin for s in success_rows if s.true_recognition and s.margin is not None]
    summary["confidence_histogram"] = {
        "buckets": [label for label, _, _, _ in conf_spec],
        "all_samples": histogram(confidence_all, conf_spec),
        "true_recognition": histogram(confidence_true, conf_spec),
        "null_confidence_all": sum(1 for s in success_rows if s.confidence is None),
        "null_confidence_true_recognition": sum(
            1 for s in success_rows if s.true_recognition and s.confidence is None),
        "true_recognition_sample_count": sum(1 for s in success_rows if s.true_recognition),
    }
    summary["margin_histogram"] = {
        "buckets": [label for label, _, _, _ in margin_spec],
        "all_samples": histogram(margin_all, margin_spec),
        "true_recognition": histogram(margin_true, margin_spec),
        "null_margin_all": sum(1 for s in success_rows if s.margin is None),
        "null_margin_true_recognition": sum(
            1 for s in success_rows if s.true_recognition and s.margin is None),
    }

    # (5) per-type routing matrix
    matrix: dict[str, Any] = {}
    for q_type in sorted({s.question_type or "(unknown)" for s in success_rows}):
        rows = [s for s in success_rows if (s.question_type or "(unknown)") == q_type]
        matrix[q_type] = {
            "count": len(rows),
            **{route: rate(sum(1 for s in rows if route in s.effective_routes), len(rows))
               for route in RETRIEVAL_ROUTES},
            "empty_effective_rate": rate(sum(1 for s in rows if not s.effective_routes), len(rows)),
        }
    summary["route_matrix_by_type"] = matrix

    # (6) mis-routing benchmark
    missing_by_type: dict[str, Any] = {}
    for q_type, required in REQUIRED_ROUTES.items():
        rows = [s for s in success_rows if s.question_type == q_type]
        if not rows:
            continue
        missing_rows = [s for s in rows if s.required_missing]
        missing_by_type[q_type] = {
            "required": sorted(required),
            "count": len(rows),
            "required_missing_count": len(missing_rows),
            "required_missing_rate": rate(len(missing_rows), len(rows)),
            "missing_ids": [s.row_id for s in missing_rows],
        }
    unanswerable = [s for s in success_rows if s.question_type == "unanswerable"]
    refused = [s for s in unanswerable if not s.effective_routes]
    miskill = [
        s for s in success_rows if not s.effective_routes and s.is_answerable is True]
    summary["misrouting"] = {
        "required_mapping": {k: sorted(v) for k, v in REQUIRED_ROUTES.items()},
        "unanswerable_participates": False,
        "required_missing_by_type": missing_by_type,
        "unanswerable_count": len(unanswerable),
        "unanswerable_correct_refusal_count": len(refused),
        "unanswerable_correct_refusal_rate": rate(len(refused), len(unanswerable)),
        "miskill_rate": rate(len(miskill), len(success_rows)),
        "miskill_ids": [s.row_id for s in miskill],
    }

    # (7) over-routing cost
    single_rows = [s for s in success_rows if s.question_type in SINGLE_DOCUMENT_TYPES]
    summary["overrouting"] = {
        "mean_effective_routes_by_type": {
            q_type: round(
                sum(s.route_count or 0 for s in success_rows
                    if (s.question_type or "(unknown)") == q_type)
                / max(1, sum(1 for s in success_rows
                             if (s.question_type or "(unknown)") == q_type)), 3)
            for q_type in matrix
        },
        "graph_in_effective_single_document_rate": rate(
            sum(1 for s in single_rows if "graph" in s.effective_routes), len(single_rows)),
        "bm25_in_effective_by_type": {
            q_type: matrix[q_type]["bm25"] for q_type in matrix
        },
    }

    # (8) consistency self-check
    mismatch_rows = [s for s in success_rows if s.routes_echo_mismatch]
    summary["consistency"] = {
        "routes_echo_mismatch_count": len(mismatch_rows),
        "expected_count": 0,
        "mismatch_ids": [s.row_id for s in mismatch_rows],
    }

    # (9) config drift segmentation
    segments = Counter()
    for s in success_rows:
        snapshot = s.row.get("configSnapshot")
        enabled = snapshot.get("bm25Enabled") if isinstance(snapshot, dict) else None
        if enabled is None:
            enabled_label = "missing"
        else:
            enabled_label = "true" if enabled else "false"
        available_label = "yes" if "bm25" in s.available_routes else "no"
        segments[f"bm25Enabled={enabled_label} × availableRoutes含bm25={available_label}"] += 1
    summary["config_drift"] = {
        "segments": dict(sorted(segments.items())),
        "cross_batch_drift_warning": len(segments) > 1,
    }
    return summary


# ---------------------------------------------------------------------------
# Markdown rendering
# ---------------------------------------------------------------------------

def md_table(headers: list[str], rows: list[list[str]]) -> str:
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    lines.extend("| " + " | ".join(row) + " |" for row in rows)
    return "\n".join(lines)


def render_report(summary: dict[str, Any], args: argparse.Namespace) -> str:
    collection = summary["collection"]
    gray = summary["gray_band"]
    preflight = summary["preflight"]
    conf = summary["confidence_histogram"]
    margin = summary["margin_histogram"]
    matrix = summary["route_matrix_by_type"]
    mis = summary["misrouting"]
    over = summary["overrouting"]
    consistency = summary["consistency"]
    drift = summary["config_drift"]
    n = gray["sample_count"]

    parts: list[str] = []
    parts.append("# 意图路由决策质量报告（N6）")
    parts.append("")
    parts.append(f"- 生成时间（UTC）：{summary['generated_at']}")
    parts.append(f"- 输入：`{summary['inputs']['predictions']}`")
    if summary["inputs"]["dataset"]:
        parts.append(
            f"- 补充数据集：`{summary['inputs']['dataset']}`"
            f"（为缺失行补 question_type/is_answerable，共补 {summary['inputs']['rows_filled_from_dataset']} 个字段）")
    parts.append(
        f"- 阈值参数：min-top-score={args.min_top_score:g}，"
        f"min-score-margin={args.min_score_margin:g}（直方图桶边界随其移动）")
    parts.append("")
    parts.append("## 前置条件与覆盖率声明")
    parts.append("")
    parts.append("- **ZHIMESH_INTENT_ROUTING_ENABLED 必须为 true**：关闭时后端返回 disabled 计划，"
                 "采集器会在校验层硬拒，本报告不会生成有效数据。")
    parts.append("- **BM25 索引就绪状态影响 availableRoutes**：任一知识库全文索引未就绪时决策空间收窄为 "
                 "vector+graph；跨批次混跑会产生口径漂移（见「配置口径漂移」小节）。")
    parts.append(f"- 覆盖率：决策类指标只统计 `collection_status == \"success\"` 的行（{n} 行）；"
                 f"采集失败行单列于「采集健康度」。")
    parts.append("- 前置门灰带为**并集语义**：评测端专用评估路径将 UNRELATED 折叠进 uncertain。")
    parts.append("- effectiveRoutes 为空是合法的 NO_RAG 场景（此时 intent 必为 no_rag）。")
    parts.append("- JSON 摘要中的比率均为 [0,1] 小数；本报告展示为百分比。")
    parts.append("")

    parts.append("## 采集健康度")
    parts.append("")
    parts.append(md_table(
        ["总行数", "success", "error", "失败率"],
        [[str(collection["total"]), str(collection["success"]), str(collection["error"]),
          fmt_pct(collection["error_rate"], collection["error"], collection["total"])]]))
    if collection["error_by_question_type"]:
        parts.append("")
        parts.append("失败行按题型分布：" + "、".join(
            f"{k}={v}" for k, v in collection["error_by_question_type"].items()))
    for example in collection["error_examples"]:
        parts.append(f"- 失败示例 `{example['id']}`：{example['error'][:200]}")
    parts.append("")

    parts.append("## 意图值分布（含 no_rag；error 行单列）")
    parts.append("")
    parts.append(md_table(
        ["intent", "行数"],
        [[k, str(v)] for k, v in summary["intent_distribution"].items()]))
    parts.append("")

    parts.append("## 路由灰带")
    parts.append("")
    parts.append("定义：`intent == \"uncertain\"` 或 `fallback == true`（任一命中即灰带）。")
    parts.append("")
    parts.append(md_table(
        ["指标", "值"],
        [
            ["路由灰带率", fmt_pct(gray["routing_gray_rate"], gray["routing_gray_count"], n)],
            ["uncertain 率", fmt_pct(gray["uncertain_rate"], gray["uncertain_count"], n)],
            ["fallback 率", fmt_pct(gray["fallback_rate"], gray["fallback_count"], n)],
            ["两者交集率", fmt_pct(gray["intersection_rate"], gray["intersection_count"], n)],
            ["NO_RAG 行数", str(gray["no_rag_count"])],
        ]))
    if gray["fallback_recognition_reasons"]:
        parts.append("")
        parts.append("fallback 行 recognitionReason 归因（top10）：")
        parts.append("")
        parts.append(md_table(
            ["recognitionReason", "行数"],
            [[k, str(v)] for k, v in gray["fallback_recognition_reasons"].items()]))
    parts.append("")

    parts.append("## 前置门三态与 2×2 联表")
    parts.append("")
    dist = preflight["status_distribution"]
    parts.append(md_table(
        ["scopePreflight.status", "行数", "占比"],
        [[k, str(v), fmt_pct(rate(v, n), v, n)] for k, v in dist.items()]))
    table = preflight["routing_gray_x_preflight_gray"]
    parts.append("")
    parts.append(md_table(
        ["", "前置门灰(uncertain)", "前置门非灰", "合计"],
        [
            ["路由灰", str(table["gray"]["uncertain"]), str(table["gray"]["not_uncertain"]),
             str(table["gray"]["total"])],
            ["路由非灰", str(table["not_gray"]["uncertain"]), str(table["not_gray"]["not_uncertain"]),
             str(table["not_gray"]["total"])],
        ]))
    missing_scope = dist.get("(missing)", 0)
    if missing_scope:
        parts.append("")
        parts.append(f"注：{missing_scope} 行缺 scopePreflight.status，2×2 表按「非灰」处理。")
    parts.append("")

    parts.append("## 置信度 / 边际直方图")
    parts.append("")
    parts.append("真实识别子集 = recognizer ∉ {disabled, fallback, explicit, unknown, 缺失}"
                 "（这些行 confidence 恒 0 会污染分布）。confidence 为 null 的行不入直方图，单独计数。")
    parts.append("")
    parts.append("### confidence")
    parts.append("")
    rows = [[label, str(conf["all_samples"][label]), str(conf["true_recognition"][label])]
            for label in conf["buckets"]]
    rows.append(["(null 不入图)",
                 str(conf["null_confidence_all"]),
                 str(conf["null_confidence_true_recognition"])])
    parts.append(md_table(["桶（左闭右开，末桶闭）", "全样本", "真实识别"], rows))
    parts.append("")
    parts.append("### margin")
    parts.append("")
    rows = [[label, str(margin["all_samples"][label]), str(margin["true_recognition"][label])]
            for label in margin["buckets"]]
    rows.append(["(null 不入图)",
                 str(margin["null_margin_all"]),
                 str(margin["null_margin_true_recognition"])])
    parts.append(md_table(["桶（左闭右开，末桶开到 +∞）", "全样本", "真实识别"], rows))
    parts.append("")

    parts.append("## 分题型路由矩阵")
    parts.append("")
    parts.append("P(route ∈ effectiveRoutes | question_type)，另列空路由率。")
    parts.append("")
    parts.append(md_table(
        ["question_type", "样本数", "vector", "graph", "bm25", "空路由"],
        [[t, str(matrix[t]["count"])] + [
            fmt_pct(matrix[t][route]) for route in RETRIEVAL_ROUTES
        ] + [fmt_pct(matrix[t]["empty_effective_rate"])] for t in matrix]))
    parts.append("")

    parts.append("## 误路由基准（required 映射）")
    parts.append("")
    parts.append("基准：single_document_fact / single_document_relation → {vector}；"
                 "cross_document / graph_multihop / temporal_comparison → {graph}；"
                 "**unanswerable 不参与误路由**（改报正确拒答率）。")
    parts.append("")
    rows = []
    for q_type, block in mis["required_missing_by_type"].items():
        rows.append([
            q_type, "{" + ",".join(block["required"]) + "}", str(block["count"]),
            fmt_pct(block["required_missing_rate"], block["required_missing_count"], block["count"]),
            "、".join(block["missing_ids"]) or "-",
        ])
    parts.append(md_table(["question_type", "required", "样本数", "必需路由缺失率", "缺失样本 id"], rows))
    parts.append("")
    parts.append(md_table(
        ["指标", "值"],
        [
            ["unanswerable 正确拒答率 P(空 \\| unanswerable)",
             fmt_pct(mis["unanswerable_correct_refusal_rate"],
                     mis["unanswerable_correct_refusal_count"], mis["unanswerable_count"])],
            ["误杀率 P(空 ∧ is_answerable)",
             fmt_pct(mis["miskill_rate"], len(mis["miskill_ids"]), n)],
            ["误杀 involved id", "、".join(mis["miskill_ids"]) or "-"],
        ]))
    parts.append("")

    parts.append("## 过度路由成本")
    parts.append("")
    single_rate = over["graph_in_effective_single_document_rate"]
    parts.append(md_table(
        ["指标", "值"],
        [
            ["P(graph ∈ effective \\| single_document_*)", fmt_pct(single_rate)],
        ] + [[f"mean(\\|effectiveRoutes\\| \\| {t})", str(v)]
             for t, v in over["mean_effective_routes_by_type"].items()]))
    parts.append("")

    parts.append("## 一致性自检（应为 0）")
    parts.append("")
    parts.append(md_table(
        ["指标", "值"],
        [
            ["routes[] 与 intent.effectiveRoutes 集合不一致行数",
             str(consistency["routes_echo_mismatch_count"])],
            ["不一致样本 id", "、".join(consistency["mismatch_ids"]) or "-"],
        ]))
    if consistency["routes_echo_mismatch_count"]:
        parts.append("")
        parts.append("**非 0 说明后端回显 bug，请优先排查。**")
    parts.append("")

    parts.append("## 配置口径漂移（跨批次警示）")
    parts.append("")
    parts.append(md_table(
        ["configSnapshot.bm25Enabled × availableRoutes 含 bm25", "行数"],
        [[k, str(v)] for k, v in drift["segments"].items()]))
    if drift["cross_batch_drift_warning"]:
        parts.append("")
        parts.append("**警告：出现多个分段，疑跨批次混跑（BM25 就绪状态变化），分题型指标不可直接平均。**")
    parts.append("")
    return "\n".join(parts) + "\n"


def main() -> None:
    args = parse_args()
    predictions_path = resolve(args.predictions)
    if not predictions_path.is_file():
        raise SystemExit(f"predictions file not found: {predictions_path}")
    rows = read_jsonl(predictions_path)
    if not rows:
        raise SystemExit(f"predictions file is empty: {predictions_path}")

    dataset_path = resolve(args.dataset) if args.dataset else None
    dataset_index: dict[str, dict[str, Any]] = {}
    if dataset_path is not None:
        if not dataset_path.is_file():
            raise SystemExit(f"dataset file not found: {dataset_path}")
        dataset_index = load_dataset_index(dataset_path)

    filled = 0
    for row in rows:
        row_id = str(row.get("id") or "")
        source = dataset_index.get(row_id)
        if not source:
            continue
        for key in ("question_type", "is_answerable"):
            if row.get(key) is None and source.get(key) is not None:
                row[key] = source[key]
                filled += 1

    samples = [Sample(row) for row in rows]
    summary = build_summary(samples, args, predictions_path, dataset_path, filled)

    output_dir = resolve(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    csv_path = output_dir / CSV_FILENAME
    with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(CSV_COLUMNS)
        for sample in samples:
            writer.writerow(sample.csv_row())

    summary_path = output_dir / SUMMARY_FILENAME
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    report_path = output_dir / REPORT_FILENAME
    report_path.write_text(render_report(summary, args), encoding="utf-8")

    print(f"samples={summary['collection']['total']} "
          f"(success={summary['collection']['success']}, error={summary['collection']['error']})")
    print(f"routing_gray_rate={summary['gray_band']['routing_gray_rate']}")
    print(f"echo_mismatch={summary['consistency']['routes_echo_mismatch_count']}")
    print(f"wrote: {report_path}")
    print(f"wrote: {summary_path}")
    print(f"wrote: {csv_path}")


if __name__ == "__main__":
    try:
        main()
    except SystemExit as exc:
        sys.exit(exc)
