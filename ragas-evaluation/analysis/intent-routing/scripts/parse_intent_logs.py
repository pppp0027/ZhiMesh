"""Parse intent-routing decision lines out of ZhiMesh server logback logs.

Targets the prod file-appender pattern (no ANSI colors, no zone)::

    %d{yyyy-MM-dd HH:mm:ss.SSS} %level [%thread] %logger %msg

Timestamps carry no zone and are interpreted as server-local time
(Asia/Shanghai). Message formats (verified against the backend sources):

- ``Knowledge scope preflight: status=, score=, skip=, durationMs=, reason=``
  (KnowledgeScopePreflightGate; status ∈ RELATED/UNCERTAIN/UNRELATED/NOT_APPLICABLE)
- ``Intent routing: intent=, confidence=, margin=, routes=[..], fallback=, reason=``
  (IntentRoutingService; routes are UPPERCASE enum names, comma+space
  separated, possibly empty ``[]``; this is the *proposed* route set)
- ``Knowledge-base scope filtered before retrieval, originalKbCount:, retainedKbCount:, excludedKbCount:, reason:``
- ``Knowledge-base intent recognition and retrieval skipped by scope preflight, reason:``
- WARN: ``Intent recognition failed; keeping request baseline retrieval: ...``,
  ``Knowledge scope preflight failed open: ...``,
  ``Unable to check BM25 readiness...``

Reasons may contain commas, equals signs and Chinese text, so every pattern
anchors the tail with ``.*$`` instead of naive splitting. Lines without a
leading timestamp (stack-trace continuations, interleaved output) are skipped
outright and counted. Unparseable timestamped lines are silently ignored --
the log contains many unrelated INFO lines.

Coverage caveat (restated in the report header): the ``Intent routing`` INFO
line is only printed on the successful-recognition path. The explicit-route
bypass and the disabled / config-off paths never print it, so
intent-lines / preflight-lines is the share of traffic that actually entered
recognition; the gap must be read together with ZHIMESH_INTENT_ROUTING_ENABLED
and explicit-route traffic.

Standard library only; Python 3.10 compatible. Relative paths resolve
against ragas-evaluation ROOT.
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import re
import sys
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_OUTPUT_DIR = "analysis/intent-routing/results/runs/log-parse"
REPORT_FILENAME = "LOG_PARSE_REPORT.zh-CN.md"
SUMMARY_FILENAME = "log_intent_summary.json"
GLOB_MAGIC_CHARS = "*?["

LINE_RE = re.compile(
    r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"(?P<level>[A-Z]+)\s+\[(?P<thread>[^\]]*)\]\s+(?P<logger>\S+)\s+(?P<msg>.*)$"
)
PREFLIGHT_RE = re.compile(
    r"^Knowledge scope preflight: status=(?P<status>[A-Z_]+), "
    r"score=(?P<score>-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|null), "
    r"skip=(?P<skip>true|false), durationMs=(?P<duration>\d+), reason=(?P<reason>.*)$"
)
INTENT_RE = re.compile(
    r"^Intent routing: intent=(?P<intent>[A-Z_]+), "
    r"confidence=(?P<confidence>-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|null), "
    r"margin=(?P<margin>-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|null), "
    r"routes=\[(?P<routes>[^\]]*)\], fallback=(?P<fallback>true|false), "
    r"reason=(?P<reason>.*)$"
)
SCOPE_FILTERED_RE = re.compile(
    r"^Knowledge-base scope filtered before retrieval, originalKbCount:(?P<original>\d+), "
    r"retainedKbCount:(?P<retained>\d+), excludedKbCount:(?P<excluded>\d+), "
    r"reason:(?P<reason>.*)$"
)
SKIPPED_RE = re.compile(
    r"^Knowledge-base intent recognition and retrieval skipped by scope preflight, "
    r"reason:(?P<reason>.*)$"
)
DEDICATED_SKIPPED_RE = re.compile(
    r"^Dedicated knowledge-base retrieval skipped by scope preflight, "
    r"kbUuid:(?P<kb_uuid>\S+), reason:(?P<reason>.*)$"
)
WARN_RECOGNITION_RE = re.compile(
    r"^Intent recognition failed; keeping request baseline retrieval: (?P<detail>.*)$"
)
WARN_FAILOPEN_RE = re.compile(r"^Knowledge scope preflight failed open: (?P<detail>.*)$")
WARN_BM25_RE = re.compile(r"^Unable to check BM25 readiness.*")
ROUTE_ORDER = {"vector": 0, "graph": 1, "bm25": 2}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Parse intent-routing preflight/routing lines from ZhiMesh server logs",
    )
    parser.add_argument(
        "--log-file", action="append", required=True, metavar="PATH_OR_GLOB",
        help=(
            "Log file path or glob (repeatable); globs expand per value for rotated "
            "files like '/opt/zhimesh/logs/2026-09-*.log'. A literal path that does "
            "not exist, or a glob with no match, is a hard error. Relative paths "
            "resolve against ragas-evaluation ROOT."
        ),
    )
    parser.add_argument(
        "--since", metavar="YYYY-MM-DD",
        help="Keep lines with date >= since (inclusive, whole day; server-local Asia/Shanghai)",
    )
    parser.add_argument(
        "--until", metavar="YYYY-MM-DD",
        help="Keep lines with date <= until (inclusive, whole day; server-local Asia/Shanghai)",
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
    for name in ("since", "until"):
        value = getattr(args, name)
        if value is not None:
            try:
                date.fromisoformat(value)
            except ValueError:
                raise SystemExit(f"--{name} must be YYYY-MM-DD, got {value!r}")
    return args


def resolve(path: str | Path) -> Path:
    candidate = Path(path)
    return candidate if candidate.is_absolute() else ROOT / candidate


def expand_log_files(values: list[str]) -> list[Path]:
    """Expand every --log-file value; literal/glob misses are hard errors."""
    files: set[Path] = set()
    for value in values:
        if any(char in value for char in GLOB_MAGIC_CHARS):
            # Resolve relative globs against ROOT (same base as literal paths)
            # instead of the process CWD.
            matches = sorted(glob.glob(str(resolve(value))))
            if not matches:
                raise SystemExit(f"--log-file glob matched no files: {value!r}")
            files.update(Path(match) for match in matches)
        else:
            candidate = resolve(value)
            if not candidate.is_file():
                raise SystemExit(f"--log-file does not exist: {value!r}")
            files.add(candidate)
    return sorted(files)


def parse_routes(raw: str) -> set[str]:
    """Parse the bracket body of ``routes=[VECTOR, GRAPH]`` into lowercase names."""
    return {
        item.strip().lower()
        for item in raw.split(",")
        if item.strip()
    }


def routes_key(routes: set[str]) -> str:
    return "{" + ",".join(sorted(routes, key=lambda name: ROUTE_ORDER.get(name, 99))) + "}"


def percentile(values: list[float], p: float) -> float | None:
    """Linear-interpolation percentile (numpy 'linear' method): rank = p/100*(n-1)."""
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * p / 100.0
    low = math.floor(rank)
    high = math.ceil(rank)
    if low == high:
        return ordered[low]
    return ordered[low] + (rank - low) * (ordered[high] - ordered[low])


# ---------------------------------------------------------------------------
# Histograms (same bucket semantics as intent_routing_stats.py)
# ---------------------------------------------------------------------------

def confidence_bucket_spec(min_top: float) -> list[tuple[str, float | None, float | None, bool]]:
    edges = sorted({round(edge, 6) for edge in (0.0, 0.20, 0.40, 0.60, min_top, 0.85, 0.90, 0.95, 1.0)})
    buckets: list[tuple[str, float | None, float | None, bool]] = [("<0", None, None, False)]
    for index, (lo, hi) in enumerate(zip(edges, edges[1:])):
        last = index == len(edges) - 2
        label = f"[{lo:.2f},{hi:.2f}]" if last else f"[{lo:.2f},{hi:.2f})"
        buckets.append((label, lo, hi, last))
    return buckets


def margin_bucket_spec(min_margin: float) -> list[tuple[str, float | None, float | None, bool]]:
    edges = sorted({round(edge, 6) for edge in (0.0, min_margin, 0.10, 0.20, 0.40)})
    buckets: list[tuple[str, float | None, float | None, bool]] = [("<0", None, None, False)]
    for lo, hi in zip(edges, edges[1:]):
        buckets.append((f"[{lo:.2f},{hi:.2f})", lo, hi, False))
    buckets.append((f"[{edges[-1]:.2f},+∞)", edges[-1], None, False))
    return buckets


def histogram(values: list[float],
              spec: list[tuple[str, float | None, float | None, bool]]) -> dict[str, int]:
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
            counts[spec[-1][0]] += 1
    return counts


def top_reasons(reasons: Counter[str], limit: int = 10) -> dict[str, int]:
    return dict(sorted(reasons.items(), key=lambda kv: (-kv[1], kv[0]))[:limit])


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------

class LogStats:
    def __init__(self) -> None:
        self.total_lines = 0
        self.no_timestamp_lines = 0
        self.filtered_out_by_date = 0
        self.matched_lines = 0
        self.preflight_status: Counter[str] = Counter()
        self.preflight_skip: Counter[str] = Counter()
        self.preflight_durations: list[float] = []
        self.preflight_reasons: dict[str, Counter[str]] = {}
        self.intent_counts: Counter[str] = Counter()
        self.intent_fallback: Counter[str] = Counter()
        self.intent_confidence: list[float] = []
        self.intent_margin: list[float] = []
        self.intent_routes: Counter[str] = Counter()
        self.scope_filtered: list[dict[str, int]] = []
        self.scope_filtered_reasons: Counter[str] = Counter()
        self.skipped_reasons: Counter[str] = Counter()
        self.warn_samples: dict[str, list[str]] = {
            "intent_recognition_failed": [],
            "preflight_failed_open": [],
            "bm25_readiness_unavailable": [],
        }
        # Pre-seed every WARN category so the report table always shows all
        # three rows; main() converts this to a plain dict, where a missing
        # key would raise KeyError in render_report.
        self.warn_totals: Counter[str] = Counter({
            "intent_recognition_failed": 0,
            "preflight_failed_open": 0,
            "bm25_readiness_unavailable": 0,
        })
        self.parse_failures = 0

    def feed(self, line: str, since: date | None, until: date | None) -> None:
        self.total_lines += 1
        match = LINE_RE.match(line)
        if match is None:
            if line.startswith("20") and re.match(r"^\d{4}-\d{2}-\d{2} ", line):
                # Timestamped but malformed (unexpected level/thread layout): skip.
                self.parse_failures += 1
            else:
                # Stack-trace continuation / interleaved output: skip entirely.
                self.no_timestamp_lines += 1
            return
        try:
            ts = datetime.strptime(match.group("ts"), "%Y-%m-%d %H:%M:%S.%f")
        except ValueError:
            self.parse_failures += 1
            return
        if (since is not None and ts.date() < since) or (until is not None and ts.date() > until):
            self.filtered_out_by_date += 1
            return
        message = match.group("msg")
        if self.feed_preflight(message):
            return
        if self.feed_intent(message):
            return
        if self.feed_scope_filtered(message):
            return
        if self.feed_skipped(message):
            return
        if match.group("level") == "WARN":
            self.feed_warn(message, line.rstrip("\r\n"))

    def feed_preflight(self, message: str) -> bool:
        match = PREFLIGHT_RE.match(message)
        if match is None:
            return False
        status = match.group("status")
        self.preflight_status[status] += 1
        self.preflight_skip[match.group("skip")] += 1
        self.preflight_durations.append(float(match.group("duration")))
        self.preflight_reasons.setdefault(status, Counter())[match.group("reason")] += 1
        self.matched_lines += 1
        return True

    def feed_intent(self, message: str) -> bool:
        match = INTENT_RE.match(message)
        if match is None:
            return False
        self.intent_counts[match.group("intent").lower()] += 1
        self.intent_fallback[match.group("fallback")] += 1
        # "null" confidence/margin (null-tolerant regex) means the field was
        # absent — skip it without polluting the malformed-line counter.
        if match.group("confidence") != "null":
            self.intent_confidence.append(float(match.group("confidence")))
        if match.group("margin") != "null":
            self.intent_margin.append(float(match.group("margin")))
        self.intent_routes[routes_key(parse_routes(match.group("routes")))] += 1
        self.matched_lines += 1
        return True

    def feed_scope_filtered(self, message: str) -> bool:
        match = SCOPE_FILTERED_RE.match(message)
        if match is None:
            return False
        self.scope_filtered.append({
            "original": int(match.group("original")),
            "retained": int(match.group("retained")),
            "excluded": int(match.group("excluded")),
        })
        self.scope_filtered_reasons[match.group("reason")] += 1
        self.matched_lines += 1
        return True

    def feed_skipped(self, message: str) -> bool:
        match = SKIPPED_RE.match(message) or DEDICATED_SKIPPED_RE.match(message)
        if match is None:
            return False
        self.skipped_reasons[match.group("reason")] += 1
        self.matched_lines += 1
        return True

    def feed_warn(self, message: str, raw_line: str) -> None:
        if WARN_RECOGNITION_RE.match(message):
            key = "intent_recognition_failed"
        elif WARN_FAILOPEN_RE.match(message):
            key = "preflight_failed_open"
        elif WARN_BM25_RE.match(message):
            key = "bm25_readiness_unavailable"
        else:
            return
        self.warn_totals[key] += 1
        if len(self.warn_samples[key]) < 3:
            self.warn_samples[key].append(raw_line)
        self.matched_lines += 1


def build_summary(stats: LogStats, args: argparse.Namespace, files: list[Path]) -> dict[str, Any]:
    n_preflight = sum(stats.preflight_status.values())
    n_intent = sum(stats.intent_counts.values())
    n_filtered = len(stats.scope_filtered)
    n_skipped = sum(stats.skipped_reasons.values())

    def pctl(values: list[float], p: float) -> float | None:
        raw = percentile(values, p)
        return None if raw is None else round(raw, 2)

    def mean(values: list[float]) -> float | None:
        return round(sum(values) / len(values), 2) if values else None

    conf_spec = confidence_bucket_spec(args.min_top_score)
    margin_spec = margin_bucket_spec(args.min_score_margin)
    summary: dict[str, Any] = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "inputs": {
            "files": [str(path) for path in files],
            "since": args.since,
            "until": args.until,
            "date_filter_semantics": "closed interval, both days inclusive (server-local Asia/Shanghai)",
        },
        "thresholds": {
            "min_top_score": args.min_top_score,
            "min_score_margin": args.min_score_margin,
        },
        "line_counts": {
            "total_lines": stats.total_lines,
            "no_timestamp_lines_skipped": stats.no_timestamp_lines,
            "malformed_timestamped_lines_skipped": stats.parse_failures,
            "filtered_out_by_date": stats.filtered_out_by_date,
            "matched_target_lines": stats.matched_lines,
        },
        "preflight": {
            "count": n_preflight,
            "status_distribution": dict(sorted(stats.preflight_status.items())),
            "skip_rate": (
                stats.preflight_skip.get("true", 0) / n_preflight) if n_preflight else None,
            "duration_ms": {
                "method": "linear interpolation percentile (numpy 'linear'): rank = p/100*(n-1)",
                "p50": pctl(stats.preflight_durations, 50),
                "p95": pctl(stats.preflight_durations, 95),
                "mean": mean(stats.preflight_durations),
                "max": max(stats.preflight_durations) if stats.preflight_durations else None,
            },
            "reasons_uncertain_top10": top_reasons(stats.preflight_reasons.get("UNCERTAIN", Counter())),
            "reasons_unrelated_top10": top_reasons(stats.preflight_reasons.get("UNRELATED", Counter())),
        },
        "intent_routing": {
            "count": n_intent,
            "preflight_to_intent_ratio": (n_intent / n_preflight) if n_preflight else None,
            "intent_distribution": dict(sorted(stats.intent_counts.items())),
            "fallback_rate": (
                stats.intent_fallback.get("true", 0) / n_intent) if n_intent else None,
            "confidence_histogram": {
                "buckets": [label for label, *_ in conf_spec],
                **histogram(stats.intent_confidence, conf_spec),
            },
            "margin_histogram": {
                "buckets": [label for label, *_ in margin_spec],
                **histogram(stats.intent_margin, margin_spec),
            },
            "routes_set_distribution": dict(sorted(
                stats.intent_routes.items(), key=lambda kv: (-kv[1], kv[0]))),
            "note": "routes= in the log line is the PROPOSED set (plan.proposed()), "
                    "not the effective set echoed by the evaluation endpoint",
        },
        "scope_filtered": {
            "count": n_filtered,
            "original_kb_count": {
                "mean": mean([float(r["original"]) for r in stats.scope_filtered]),
                "p95": pctl([float(r["original"]) for r in stats.scope_filtered], 95),
            },
            "retained_kb_count": {
                "mean": mean([float(r["retained"]) for r in stats.scope_filtered]),
                "p95": pctl([float(r["retained"]) for r in stats.scope_filtered], 95),
            },
            "excluded_kb_count": {
                "mean": mean([float(r["excluded"]) for r in stats.scope_filtered]),
                "p95": pctl([float(r["excluded"]) for r in stats.scope_filtered], 95),
            },
            "excluded_positive_rate": (
                sum(1 for r in stats.scope_filtered if r["excluded"] > 0) / n_filtered
            ) if n_filtered else None,
            "reasons_top10": top_reasons(stats.scope_filtered_reasons),
        },
        "skipped_by_preflight": {
            "count": n_skipped,
            "reasons_top10": top_reasons(stats.skipped_reasons),
        },
        "warn": {
            "counts": dict(sorted(stats.warn_totals.items())),
            "samples": {key: list(lines) for key, lines in stats.warn_samples.items()},
        },
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


def fmt_pct(value: float | None) -> str:
    return "n/a" if value is None else f"{100 * value:.1f}%"


def fmt_optional(value: Any) -> str:
    return "n/a" if value is None else str(value)


def render_report(summary: dict[str, Any], warn_counts: dict[str, int]) -> str:
    lines_stats = summary["line_counts"]
    preflight = summary["preflight"]
    intent = summary["intent_routing"]
    scope = summary["scope_filtered"]
    skipped = summary["skipped_by_preflight"]
    parts: list[str] = []
    parts.append("# 意图路由日志解析报告")
    parts.append("")
    parts.append(f"- 生成时间（UTC）：{summary['generated_at']}")
    for path in summary["inputs"]["files"]:
        parts.append(f"- 输入：`{path}`")
    if summary["inputs"]["since"] or summary["inputs"]["until"]:
        parts.append(
            f"- 时间过滤：since={summary['inputs']['since'] or '(none)'}，"
            f"until={summary['inputs']['until'] or '(none)'}；"
            "闭区间 [since, until]，两端均含整日；时间戳按服务器本地 Asia/Shanghai 解释")
    parts.append(f"- 阈值参数：min-top-score={summary['thresholds']['min_top_score']:g}，"
                 f"min-score-margin={summary['thresholds']['min_score_margin']:g}")
    parts.append("")
    parts.append("## 覆盖率声明（解读前必读）")
    parts.append("")
    parts.append("- **Intent routing INFO 仅在识别成功路径打印**：explicit 显式路由旁路与 "
                 "disabled / 配置关闭路径不打印该行。")
    ratio = intent["preflight_to_intent_ratio"]
    parts.append(
        f"- 意图行数 / 前置门行数 = {intent['count']}/{preflight['count']} = "
        + (f"{100 * ratio:.1f}%" if ratio is not None else "n/a")
        + "，即进入意图识别的流量比例；差额需结合 ZHIMESH_INTENT_ROUTING_ENABLED "
          "开关状态与显式路由（retrievalMode/retrievalRoutes）流量解读。")
    parts.append("- 日志中的 `routes=` 是 **proposed** 路由集合（plan.proposed()），"
                 "与评测回显的 effectiveRoutes 在 fallback/baseline 场景可能不同。")
    parts.append("- **评测流量同样产生这两类行**：N6 意图实验（collect_predictions "
                 "intent_routing=true）与 curl 冒烟走同一条决策链路，会在 app.log 打出"
                 "格式完全相同的前置门/意图行。解析窗口应避开 N6 采集时段，或在解读时"
                 "从总量中扣除评测题数（121 题/组），否则得到的是「生产+评测」混合口径，"
                 "与 T7 评测报告数字无法直接对上。")
    parts.append("- 前置门 status 可能取 RELATED/UNCERTAIN/UNRELATED/NOT_APPLICABLE 四值；"
                 "评测端专用路径会把 UNRELATED 折叠为 UNCERTAIN（并集语义）。")
    parts.append(f"- 无时间戳行（堆栈续行/交错输出）直接跳过不解析：{lines_stats['no_timestamp_lines_skipped']} 行；"
                 f"时间戳异常行跳过 {lines_stats['malformed_timestamped_lines_skipped']} 行。")
    parts.append("")
    parts.append("## 行数总览")
    parts.append("")
    parts.append(md_table(
        ["指标", "行数"],
        [
            ["总行数", str(lines_stats["total_lines"])],
            ["命中目标行", str(lines_stats["matched_target_lines"])],
            ["被日期过滤剔除", str(lines_stats["filtered_out_by_date"])],
            ["无时间戳跳过", str(lines_stats["no_timestamp_lines_skipped"])],
        ]))
    parts.append("")
    parts.append("## 前置门状态分布")
    parts.append("")
    n_preflight = preflight["count"]
    parts.append(md_table(
        ["status", "行数", "占比"],
        [[status, str(count),
          fmt_pct(count / n_preflight if n_preflight else None)]
         for status, count in preflight["status_distribution"].items()]))
    duration = preflight["duration_ms"]
    parts.append("")
    parts.append(md_table(
        ["指标", "值"],
        [
            ["skip 率", fmt_pct(preflight["skip_rate"])],
            ["durationMs p50", fmt_optional(duration["p50"])],
            ["durationMs p95", fmt_optional(duration["p95"])],
            ["durationMs mean", fmt_optional(duration["mean"])],
            ["durationMs max", fmt_optional(duration["max"])],
        ]))
    parts.append(f"\n分位方法：{duration['method']}。")
    for key, title in (("reasons_uncertain_top10", "UNCERTAIN reason top10"),
                       ("reasons_unrelated_top10", "UNRELATED reason top10")):
        if preflight[key]:
            parts.append("")
            parts.append(f"### {title}")
            parts.append("")
            parts.append(md_table(
                ["reason", "次数"],
                [[reason, str(count)] for reason, count in preflight[key].items()]))
    parts.append("")
    parts.append("## 意图路由")
    parts.append("")
    n_intent = intent["count"]
    parts.append(md_table(
        ["intent", "行数", "占比"],
        [[name, str(count), fmt_pct(count / n_intent if n_intent else None)]
         for name, count in intent["intent_distribution"].items()]))
    parts.append("")
    parts.append(f"- fallback 率：{fmt_pct(intent['fallback_rate'])}")
    parts.append("")
    parts.append("### confidence 直方图（桶左闭右开，末桶闭）")
    parts.append("")
    parts.append(md_table(
        ["桶", "行数"],
        [[label, str(intent["confidence_histogram"][label])]
         for label in intent["confidence_histogram"]["buckets"]]))
    parts.append("")
    parts.append("### margin 直方图（桶左闭右开，末桶开到 +∞）")
    parts.append("")
    parts.append(md_table(
        ["桶", "行数"],
        [[label, str(intent["margin_histogram"][label])]
         for label in intent["margin_histogram"]["buckets"]]))
    parts.append("")
    parts.append("### routes 集合分布（proposed）")
    parts.append("")
    if intent["routes_set_distribution"]:
        parts.append(md_table(
            ["routes", "行数"],
            [[key, str(count)] for key, count in intent["routes_set_distribution"].items()]))
    else:
        parts.append("（无意图行）")
    parts.append("")
    parts.append("## 多库过滤（scope filtered）")
    parts.append("")
    parts.append(md_table(
        ["指标", "original", "retained", "excluded"],
        [
            ["mean", fmt_optional(scope["original_kb_count"]["mean"]),
             fmt_optional(scope["retained_kb_count"]["mean"]),
             fmt_optional(scope["excluded_kb_count"]["mean"])],
            ["p95", fmt_optional(scope["original_kb_count"]["p95"]),
             fmt_optional(scope["retained_kb_count"]["p95"]),
             fmt_optional(scope["excluded_kb_count"]["p95"])],
        ]))
    parts.append("")
    parts.append(f"- excluded>0 占比：{fmt_pct(scope['excluded_positive_rate'])}（样本 {scope['count']} 行）")
    if scope["reasons_top10"]:
        parts.append("")
        parts.append(md_table(
            ["reason", "次数"],
            [[reason, str(count)] for reason, count in scope["reasons_top10"].items()]))
    parts.append("")
    parts.append("## 前置门跳过（skipped by scope preflight）")
    parts.append("")
    parts.append(f"- 行数：{skipped['count']}")
    if skipped["reasons_top10"]:
        parts.append("")
        parts.append(md_table(
            ["reason", "次数"],
            [[reason, str(count)] for reason, count in skipped["reasons_top10"].items()]))
    parts.append("")
    parts.append("## WARN 汇总")
    parts.append("")
    parts.append(md_table(
        ["类别", "次数"],
        [[label, str(warn_counts[key])] for key, label in (
            ("intent_recognition_failed", "Intent recognition failed（识别失败回退基线）"),
            ("preflight_failed_open", "Knowledge scope preflight failed open（前置门失败放开）"),
            ("bm25_readiness_unavailable", "Unable to check BM25 readiness（BM25 就绪探测降级）"),
        )]))
    for key, title in (
        ("intent_recognition_failed", "Intent recognition failed 样例"),
        ("preflight_failed_open", "preflight failed open 样例"),
        ("bm25_readiness_unavailable", "BM25 readiness 降级样例"),
    ):
        samples = summary["warn"]["samples"][key]
        if samples:
            parts.append("")
            parts.append(f"### {title}（最多 3 条原文）")
            parts.append("")
            parts.extend(f"```text\n{sample}\n```" for sample in samples)
    parts.append("")
    return "\n".join(parts) + "\n"


def main() -> None:
    args = parse_args()
    files = expand_log_files(args.log_file)
    since = date.fromisoformat(args.since) if args.since else None
    until = date.fromisoformat(args.until) if args.until else None

    stats = LogStats()
    for path in files:
        with path.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:
                stats.feed(line, since, until)

    # WARN category counts must be unbounded; samples are capped at 3 in feed().
    summary = build_summary(stats, args, files)
    warn_counts = dict(sorted(stats.warn_totals.items()))

    output_dir = resolve(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    summary_path = output_dir / SUMMARY_FILENAME
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report_path = output_dir / REPORT_FILENAME
    report_path.write_text(render_report(summary, warn_counts), encoding="utf-8")

    print(f"files={len(files)}, total_lines={stats.total_lines}, "
          f"matched={stats.matched_lines}, "
          f"preflight={summary['preflight']['count']}, intent={summary['intent_routing']['count']}")
    print(f"ratio_intent_over_preflight={summary['intent_routing']['preflight_to_intent_ratio']}")
    print(f"wrote: {report_path}")
    print(f"wrote: {summary_path}")


if __name__ == "__main__":
    try:
        main()
    except SystemExit as exc:
        sys.exit(exc)
