"""Build the final B0/E1-E4 (or custom) experiment comparison report.

Running without arguments reproduces the built-in B0/E1-E4 report exactly.

Custom experiment sets (server deployment example; each DIR must contain
predictions.jsonl and scores.jsonl, relative to --evaluation-root unless
absolute; typical pairs are N2->N3 for the BM25 increment, N3->N4 for the
rerank increment and N1->N4 for the overall improvement; every pair delta is
RIGHT - LEFT)::

    python analysis/comparison/scripts/final_experiment_summary.py \
        --experiment N1=experiments/current-system/n1-vector \
        --experiment N2=experiments/current-system/n2-vector-graph \
        --experiment N3=experiments/current-system/n3-three-route \
        --experiment N4=experiments/current-system/n4-three-route-rerank \
        --experiment N5=experiments/current-system/n5-vector-rerank \
        --pair N2:N3 \
        --pair N3:N4 \
        --pair N1:N4 \
        --output-dir analysis/comparison/results/current-system

Passing any --experiment replaces the built-in experiment set entirely.
Without --pair, a custom experiment set falls back to adjacent pairs in the
given order. --pair names must be defined by --experiment (or, when only
--pair is passed, by the built-in set).
"""

from __future__ import annotations

import argparse
import collections
import csv
import json
import math
import random
import statistics
from pathlib import Path
from typing import Any, Iterable


METRICS = [
    "llm_context_precision_with_reference",
    "context_recall",
    "faithfulness",
    "answer_relevancy",
    "answer_accuracy",
]
METRIC_LABELS = {
    "llm_context_precision_with_reference": "上下文精确率",
    "context_recall": "上下文召回率",
    "faithfulness": "事实忠实度",
    "answer_relevancy": "回答相关性",
    "answer_accuracy": "回答准确率",
    "retrieval_score": "检索综合分",
    "generation_score": "生成综合分",
    "overall_score": "总体综合分",
}
EXPERIMENTS = [
    {
        "id": "B0",
        "label": "B0-1000Token",
        "predictions": "experiments/before-optimization/results/b0-1000-token/predictions.jsonl",
        "scores": "experiments/before-optimization/results/b0-1000-token/scores.jsonl",
    },
    {
        "id": "E1",
        "label": "E1-Vector",
        "predictions": "experiments/before-optimization/results/e1-vector/predictions.jsonl",
        "scores": "experiments/before-optimization/results/e1-vector/scores.jsonl",
    },
    {
        "id": "E2",
        "label": "E2-Graph-Optimized",
        "predictions": "experiments/after-optimization/results/e2-graph-optimized/predictions.jsonl",
        "scores": "experiments/after-optimization/results/e2-graph-optimized/scores.jsonl",
    },
    {
        "id": "E3",
        "label": "E3-Hybrid-Final",
        "predictions": "experiments/after-optimization/results/e3-hybrid-final/predictions.jsonl",
        "scores": "experiments/after-optimization/results/e3-hybrid-final/scores.jsonl",
    },
    {
        "id": "E4",
        "label": "E4-Hybrid+Rerank-Final",
        "predictions": "experiments/after-optimization/results/e4-hybrid-rerank-final/predictions.jsonl",
        "scores": "experiments/after-optimization/results/e4-hybrid-rerank-final/scores.jsonl",
    },
]
DEFAULT_PAIRS = [
    ("B0", "E1"),
    ("E1", "E3"),
    ("E2", "E3"),
    ("E3", "E4"),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the final B0/E1-E4 experiment comparison")
    parser.add_argument(
        "--evaluation-root",
        type=Path,
        default=Path(__file__).resolve().parents[3],
        help="ragas-evaluation directory; defaults to the script directory",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("analysis/comparison/results/after-optimization"),
        help="output directory, relative to evaluation root unless absolute",
    )
    parser.add_argument("--bootstrap-iterations", type=int, default=2000)
    parser.add_argument("--seed", type=int, default=20260728)
    parser.add_argument(
        "--experiment",
        action="append",
        metavar="NAME=DIR",
        help=(
            "custom experiment NAME=DIR; DIR holds predictions.jsonl and scores.jsonl "
            "(relative to --evaluation-root unless absolute); repeatable. "
            "Passing any --experiment replaces the built-in B0/E1-E4 experiment set."
        ),
    )
    parser.add_argument(
        "--pair",
        action="append",
        metavar="LEFT:RIGHT",
        help=(
            "paired comparison LEFT:RIGHT with delta = RIGHT - LEFT; repeatable; "
            "names must be defined by --experiment or the built-in set. "
            "Without --pair, a custom experiment set falls back to adjacent pairs "
            "in the given order."
        ),
    )
    return parser.parse_args()


def parse_experiment_specs(values: list[str], root: Path) -> list[dict[str, str]]:
    specs: list[dict[str, str]] = []
    seen: set[str] = set()
    for value in values:
        if "=" not in value:
            raise SystemExit(f"Invalid --experiment {value!r}; expected NAME=DIR")
        name, directory = value.split("=", 1)
        name = name.strip()
        if not name:
            raise SystemExit(f"Invalid --experiment {value!r}; experiment name is empty")
        if name in seen:
            raise SystemExit(f"Duplicate --experiment name {name!r}")
        seen.add(name)
        directory_path = Path(directory)
        if not directory_path.is_absolute():
            directory_path = root / directory_path
        specs.append(
            {
                "id": name,
                "label": name,
                "predictions": str(directory_path / "predictions.jsonl"),
                "scores": str(directory_path / "scores.jsonl"),
            }
        )
    return specs


def parse_pairs(values: list[str], valid_ids: list[str]) -> list[tuple[str, str]]:
    known = set(valid_ids)
    pairs: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for value in values:
        if ":" not in value:
            raise SystemExit(f"Invalid --pair {value!r}; expected LEFT:RIGHT")
        left, right = (part.strip() for part in value.split(":", 1))
        for name in (left, right):
            if name not in known:
                raise SystemExit(
                    f"--pair references unknown experiment {name!r} in {value!r}; "
                    f"known experiments: {', '.join(valid_ids)}"
                )
        if left == right:
            raise SystemExit(f"Invalid --pair {value!r}; LEFT and RIGHT must differ")
        if (left, right) in seen:
            raise SystemExit(f"Duplicate --pair {value!r}")
        seen.add((left, right))
        pairs.append((left, right))
    return pairs


def read_jsonl(path: Path) -> tuple[list[dict[str, Any]], int]:
    rows: list[dict[str, Any]] = []
    invalid = 0
    with path.open(encoding="utf-8-sig") as handle:
        for line in handle:
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except (TypeError, ValueError):
                invalid += 1
    return rows, invalid


def finite(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def mean(values: Iterable[float]) -> float | None:
    data = list(values)
    return statistics.fmean(data) if data else None


def percentile(values: Iterable[float], probability: float) -> float | None:
    data = sorted(values)
    if not data:
        return None
    position = (len(data) - 1) * probability
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return data[lower]
    return data[lower] + (data[upper] - data[lower]) * (position - lower)


def bootstrap_ci(values: list[float], iterations: int, seed: int) -> list[float | None]:
    if not values:
        return [None, None]
    if len(values) == 1 or iterations <= 0:
        return [values[0], values[0]]
    rng = random.Random(seed)
    size = len(values)
    estimates = [statistics.fmean(rng.choice(values) for _ in range(size)) for _ in range(iterations)]
    return [percentile(estimates, 0.025), percentile(estimates, 0.975)]


def sample_scores(row: dict[str, Any]) -> dict[str, float] | None:
    ragas = row.get("ragas") or {}
    values = {metric: finite(ragas.get(metric)) for metric in METRICS}
    if any(value is None for value in values.values()):
        return None
    precision = values["llm_context_precision_with_reference"]
    recall = values["context_recall"]
    assert precision is not None and recall is not None
    retrieval = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
    generation = statistics.fmean(
        [
            values["faithfulness"],
            values["answer_relevancy"],
            values["answer_accuracy"],
        ]
    )
    result = {metric: float(values[metric]) for metric in METRICS}
    result["retrieval_score"] = retrieval
    result["generation_score"] = generation
    result["overall_score"] = 0.4 * retrieval + 0.6 * generation
    return result


def distribution(values: list[float]) -> dict[str, float | int | None]:
    return {
        "count": len(values),
        "mean": mean(values),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
    }


def most_common(values: Iterable[Any]) -> Any:
    data = [value for value in values if value is not None]
    return collections.Counter(data).most_common(1)[0][0] if data else None


def load_experiment(root: Path, spec: dict[str, str]) -> dict[str, Any]:
    prediction_rows, prediction_invalid = read_jsonl(root / spec["predictions"])
    score_rows, score_invalid = read_jsonl(root / spec["scores"])

    predictions: dict[str, dict[str, Any]] = {}
    for row in prediction_rows:
        if row.get("collection_status") == "success" and row.get("id") is not None:
            predictions[str(row["id"])] = row

    successful_scores: dict[str, dict[str, Any]] = {}
    latest_scores: dict[str, dict[str, Any]] = {}
    attempts = collections.Counter()
    for row in score_rows:
        if row.get("id") is None:
            continue
        question_id = str(row["id"])
        latest_scores[question_id] = row
        attempts[question_id] += 1
        if row.get("ragas_status") == "success" and sample_scores(row) is not None:
            successful_scores[question_id] = row

    pending_ids = [question_id for question_id in predictions if question_id not in successful_scores]
    missing_metrics = collections.Counter()
    for question_id in pending_ids:
        for metric in latest_scores.get(question_id, {}).get("ragas_missing_metrics") or []:
            missing_metrics[metric] += 1

    timing_keys = {
        "retrieval_ms": ("timingMs", "retrieval"),
        "generation_ms": ("timingMs", "generation"),
        "total_ms": ("timingMs", "total"),
        "collector_elapsed_ms": (None, "collector_elapsed_ms"),
        "input_tokens": ("usage", "inputTokens"),
        "output_tokens": ("usage", "outputTokens"),
        "total_tokens": ("usage", "totalTokens"),
    }
    engineering: dict[str, Any] = {}
    for output_key, (container, key) in timing_keys.items():
        values: list[float] = []
        for row in predictions.values():
            source = row.get(container) or {} if container else row
            value = finite(source.get(key))
            if value is not None:
                values.append(value)
        engineering[output_key] = distribution(values)

    rerank_rows = [row.get("rerank") or {} for row in predictions.values()]
    rerank_configured = sum(bool(row.get("configured")) for row in rerank_rows)
    rerank_successful = sum(bool(row.get("successful")) for row in rerank_rows)
    rerank_durations = [finite(row.get("durationMs")) for row in rerank_rows]
    engineering["rerank"] = {
        "configured_samples": rerank_configured,
        "successful_samples": rerank_successful,
        "success_rate": rerank_successful / rerank_configured if rerank_configured else None,
        "duration_ms": distribution([value for value in rerank_durations if value is not None]),
    }

    snapshots = [row.get("configSnapshot") or {} for row in predictions.values()]
    config_snapshot = {
        "retrieval_mode": most_common(snapshot.get("retrievalMode") for snapshot in snapshots),
        "use_reranker": most_common(snapshot.get("useReranker") for snapshot in snapshots),
        "chunk_max_tokens": most_common(snapshot.get("chunkMaxTokens") for snapshot in snapshots),
        "chunk_overlap": most_common(snapshot.get("chunkOverlap") for snapshot in snapshots),
        "answer_model": most_common(snapshot.get("answerModel") for snapshot in snapshots),
    }

    return {
        "id": spec["id"],
        "label": spec["label"],
        "paths": {"predictions": spec["predictions"], "scores": spec["scores"]},
        "prediction_lines": len(prediction_rows),
        "prediction_invalid_lines": prediction_invalid,
        "score_lines": len(score_rows),
        "score_invalid_lines": score_invalid,
        "prediction_count": len(predictions),
        "successful_score_count": len(successful_scores),
        "pending_count": len(pending_ids),
        "pending_ids": pending_ids,
        "missing_metrics": dict(missing_metrics),
        "attempt_count": dict(attempts),
        "predictions": predictions,
        "scores": successful_scores,
        "engineering": engineering,
        "config_snapshot": config_snapshot,
    }


def summarize_ids(experiment: dict[str, Any], question_ids: Iterable[str], iterations: int, seed: int) -> dict[str, Any]:
    ids = [question_id for question_id in question_ids if question_id in experiment["scores"]]
    per_sample = {
        question_id: sample_scores(experiment["scores"][question_id]) for question_id in ids
    }
    per_sample = {key: value for key, value in per_sample.items() if value is not None}
    metric_names = METRICS + ["retrieval_score", "generation_score", "overall_score"]
    result: dict[str, Any] = {"samples": len(per_sample), "metrics": {}}
    for index, metric in enumerate(metric_names):
        values = [scores[metric] for scores in per_sample.values()]
        result["metrics"][metric] = {
            "mean": mean(values),
            "ci95": bootstrap_ci(values, iterations, seed + index),
        }
    return result


def grouped_summary(experiment: dict[str, Any], field: str, iterations: int, seed: int) -> dict[str, Any]:
    groups: dict[str, list[str]] = collections.defaultdict(list)
    for question_id, row in experiment["scores"].items():
        groups[str(row.get(field))].append(question_id)
    return {
        value: summarize_ids(experiment, ids, iterations, seed + index * 100)
        for index, (value, ids) in enumerate(sorted(groups.items()))
    }


def paired_comparison(
    left: dict[str, Any], right: dict[str, Any], ids: list[str], iterations: int, seed: int
) -> dict[str, Any]:
    metric_names = METRICS + ["retrieval_score", "generation_score", "overall_score"]
    report: dict[str, Any] = {"left": left["id"], "right": right["id"], "samples": len(ids), "metrics": {}}
    for index, metric in enumerate(metric_names):
        deltas = []
        for question_id in ids:
            left_scores = sample_scores(left["scores"][question_id])
            right_scores = sample_scores(right["scores"][question_id])
            assert left_scores is not None and right_scores is not None
            deltas.append(right_scores[metric] - left_scores[metric])
        report["metrics"][metric] = {
            "mean_delta": mean(deltas),
            "ci95": bootstrap_ci(deltas, iterations, seed + index),
            "wins": sum(value > 1e-12 for value in deltas),
            "ties": sum(abs(value) <= 1e-12 for value in deltas),
            "losses": sum(value < -1e-12 for value in deltas),
        }
    return report


def fmt(value: Any, digits: int = 3) -> str:
    number = finite(value)
    return "—" if number is None else f"{number:.{digits}f}"


def build_markdown(report: dict[str, Any]) -> str:
    by_id = {experiment["id"]: experiment for experiment in report["experiments"]}
    e1 = by_id["E1"]
    e3 = by_id["E3"]
    e4 = by_id["E4"]
    e4_vs_e3_summary = report["paired_comparisons"]["E3_to_E4"]["metrics"]
    lines = [
        "# RAG 五组实验最终汇总报告",
        "",
        "## 结论摘要",
        "",
        f"- E4（融合检索 + 重排）在共同 {report['common_sample_count']} 题上的总体综合分最高："
        f"{fmt(e4['common']['metrics']['overall_score']['mean'])}。",
        f"- E4 相对 E3 的上下文精确率平均提高 "
        f"{fmt(e4_vs_e3_summary['llm_context_precision_with_reference']['mean_delta'])}，"
        f"总体综合分提高 {fmt(e4_vs_e3_summary['overall_score']['mean_delta'])}。",
        f"- E3 与 E1 的总体综合分接近（{fmt(e3['common']['metrics']['overall_score']['mean'])} 对 "
        f"{fmt(e1['common']['metrics']['overall_score']['mean'])}），但总延迟 P95 更高（"
        f"{fmt(e3['engineering']['total_ms']['p95'], 0)} ms 对 "
        f"{fmt(e1['engineering']['total_ms']['p95'], 0)} ms）。",
        "- 质量优先可选 E4；低延迟和链路简单优先可选 E1。纯图 E2 用作图谱能力基线更合适。",
        "",
        "## 数据完整性",
        "",
        "| 实验 | 回答数 | 完整评分 | 待补 | 无效行 | Chunk Tokens |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for experiment in report["experiments"]:
        lines.append(
            f"| {experiment['label']} | {experiment['prediction_count']} | "
            f"{experiment['successful_score_count']} | {experiment['pending_count']} | "
            f"{experiment['prediction_invalid_lines'] + experiment['score_invalid_lines']} | "
            f"{experiment['config_snapshot'].get('chunk_max_tokens') or '—'} |"
        )

    lines.extend(
        [
            "",
            "## 全部有效样本平均指标",
            "",
            "| 实验 | 上下文精确率 | 上下文召回率 | 事实忠实度 | 回答相关性 | 回答准确率 | 检索综合分 | 生成综合分 | 总体综合分 |",
            "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for experiment in report["experiments"]:
        metrics = experiment["overall"]["metrics"]
        lines.append(
            f"| {experiment['label']} | "
            + " | ".join(fmt(metrics[name]["mean"]) for name in METRICS + ["retrieval_score", "generation_score", "overall_score"])
            + " |"
        )

    lines.extend(
        [
            "",
            f"## 五组共同样本配对结果（N={report['common_sample_count']}）",
            "",
            "| 实验 | 上下文精确率 | 上下文召回率 | 事实忠实度 | 回答相关性 | 回答准确率 | 总体综合分 |",
            "|---|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for experiment in report["experiments"]:
        metrics = experiment["common"]["metrics"]
        names = METRICS + ["overall_score"]
        lines.append(
            f"| {experiment['label']} | " + " | ".join(fmt(metrics[name]["mean"]) for name in names) + " |"
        )

    e4_vs_e3 = report["paired_comparisons"]["E3_to_E4"]
    lines.extend(
        [
            "",
            "## E3 → E4 配对变化",
            "",
            "| 指标 | 平均变化 | 95% CI | 胜/平/负 |",
            "|---|---:|---:|---:|",
        ]
    )
    for name, values in e4_vs_e3["metrics"].items():
        ci = values["ci95"]
        lines.append(
            f"| {METRIC_LABELS[name]} | {fmt(values['mean_delta'])} | "
            f"[{fmt(ci[0])}, {fmt(ci[1])}] | {values['wins']}/{values['ties']}/{values['losses']} |"
        )

    lines.extend(
        [
            "",
            "## 工程指标",
            "",
            "| 实验 | 总延迟 P50(ms) | 总延迟 P95(ms) | 检索延迟 P95(ms) | 输入 Token 均值 | 重排成功率 |",
            "|---|---:|---:|---:|---:|---:|",
        ]
    )
    for experiment in report["experiments"]:
        engineering = experiment["engineering"]
        rerank_rate = engineering["rerank"]["success_rate"]
        lines.append(
            f"| {experiment['label']} | {fmt(engineering['total_ms']['p50'], 1)} | "
            f"{fmt(engineering['total_ms']['p95'], 1)} | {fmt(engineering['retrieval_ms']['p95'], 1)} | "
            f"{fmt(engineering['input_tokens']['mean'], 1)} | "
            f"{fmt(rerank_rate) if rerank_rate is not None else '—'} |"
        )

    lines.extend(
        [
            "",
            "## 综合分口径",
            "",
            "- `RetrievalScore = 2 × Precision × Recall / (Precision + Recall)`，逐题计算。",
            "- `GenerationScore = (Faithfulness + AnswerRelevancy + AnswerAccuracy) / 3`。",
            "- `OverallScore = 0.4 × RetrievalScore + 0.6 × GenerationScore`。",
            "- 以上三项是本项目自定义派生指标，不是 RAGAS 官方指标；正式报告应同时保留五项原始分数。",
            "- 主对比优先使用五组共同成功题目的配对结果，避免各组缺失题不同造成样本偏差。",
            "",
            "## 裁判平台一致性",
            "",
        ]
    )
    calibration = report.get("judge_calibration")
    if calibration:
        lines.extend(
            [
                "- E4 使用新平台的 `deepseek-v4-pro`，B0/E1～E3保留旧平台评分。",
                f"- 9条、45个指标点的一致性校准：`calibration_passed={str(bool(calibration.get('calibration_passed'))).lower()}`。",
                f"- 新旧平台整体 MAE：{fmt(calibration.get('overall_mae'), 4)}；AnswerAccuracy 完全一致率：{fmt(calibration.get('answer_accuracy_exact_agreement'), 4)}。",
                "- 校准支持工程实验中的连续使用，但不代表两个平台背后一定是完全相同的模型快照。",
            ]
        )
    else:
        lines.append("- 未发现裁判平台一致性校准报告；跨平台分数应谨慎比较。")
    lines.extend(
        [
            "",
            "## 完整性提醒",
            "",
        ]
    )
    incomplete = [experiment for experiment in report["experiments"] if experiment["pending_count"]]
    if incomplete:
        for experiment in incomplete:
            lines.append(
                f"- {experiment['label']} 仍有 {experiment['pending_count']} 题未取得完整指标："
                + ", ".join(experiment["pending_ids"])
                + "。"
            )
    else:
        lines.append("- 五组均已取得完整评分。")
    lines.append("")
    return "\n".join(lines)


GROUP_COUNT_LABELS = {2: "两组", 3: "三组", 4: "四组", 5: "五组", 6: "六组", 7: "七组", 8: "八组"}


def group_count_label(count: int) -> str:
    return GROUP_COUNT_LABELS.get(count, f"{count} 组")


def build_custom_markdown(report: dict[str, Any]) -> str:
    by_id = {experiment["id"]: experiment for experiment in report["experiments"]}
    count_label = group_count_label(len(report["experiments"]))
    pair_reports = report["paired_comparisons"]

    lines: list[str] = [
        f"# RAG {count_label}实验最终汇总报告",
        "",
        "## 结论摘要",
        "",
    ]
    scored = [
        experiment
        for experiment in report["experiments"]
        if experiment["common"]["metrics"]["overall_score"]["mean"] is not None
    ]
    if scored:
        best = max(
            scored, key=lambda item: item["common"]["metrics"]["overall_score"]["mean"]
        )
        lines.append(
            f"- {best['label']} 在共同 {report['common_sample_count']} 题上的总体综合分最高："
            f"{fmt(best['common']['metrics']['overall_score']['mean'])}。"
        )
    for pair in pair_reports.values():
        metrics = pair["metrics"]
        lines.append(
            f"- {by_id[pair['right']]['label']} 相对 {by_id[pair['left']]['label']}："
            f"上下文精确率平均变化 "
            f"{fmt(metrics['llm_context_precision_with_reference']['mean_delta'])}，"
            f"总体综合分平均变化 {fmt(metrics['overall_score']['mean_delta'])}"
            f"（N={pair['samples']}）。"
        )

    lines.extend(
        [
            "",
            "## 数据完整性",
            "",
            "| 实验 | 回答数 | 完整评分 | 待补 | 无效行 | Chunk Tokens |",
            "|---|---:|---:|---:|---:|---:|",
        ]
    )
    for experiment in report["experiments"]:
        lines.append(
            f"| {experiment['label']} | {experiment['prediction_count']} | "
            f"{experiment['successful_score_count']} | {experiment['pending_count']} | "
            f"{experiment['prediction_invalid_lines'] + experiment['score_invalid_lines']} | "
            f"{experiment['config_snapshot'].get('chunk_max_tokens') or '—'} |"
        )

    lines.extend(
        [
            "",
            "## 全部有效样本平均指标",
            "",
            "| 实验 | 上下文精确率 | 上下文召回率 | 事实忠实度 | 回答相关性 | 回答准确率 | 检索综合分 | 生成综合分 | 总体综合分 |",
            "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for experiment in report["experiments"]:
        metrics = experiment["overall"]["metrics"]
        lines.append(
            f"| {experiment['label']} | "
            + " | ".join(fmt(metrics[name]["mean"]) for name in METRICS + ["retrieval_score", "generation_score", "overall_score"])
            + " |"
        )

    lines.extend(
        [
            "",
            f"## {count_label}共同样本配对结果（N={report['common_sample_count']}）",
            "",
            "| 实验 | 上下文精确率 | 上下文召回率 | 事实忠实度 | 回答相关性 | 回答准确率 | 总体综合分 |",
            "|---|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for experiment in report["experiments"]:
        metrics = experiment["common"]["metrics"]
        names = METRICS + ["overall_score"]
        lines.append(
            f"| {experiment['label']} | " + " | ".join(fmt(metrics[name]["mean"]) for name in names) + " |"
        )

    for pair in pair_reports.values():
        lines.extend(
            [
                "",
                f"## {pair['left']} → {pair['right']} 配对变化",
                "",
                "| 指标 | 平均变化 | 95% CI | 胜/平/负 |",
                "|---|---:|---:|---:|",
            ]
        )
        for name, values in pair["metrics"].items():
            ci = values["ci95"]
            lines.append(
                f"| {METRIC_LABELS[name]} | {fmt(values['mean_delta'])} | "
                f"[{fmt(ci[0])}, {fmt(ci[1])}] | {values['wins']}/{values['ties']}/{values['losses']} |"
            )

    lines.extend(
        [
            "",
            "## 工程指标",
            "",
            "| 实验 | 总延迟 P50(ms) | 总延迟 P95(ms) | 检索延迟 P95(ms) | 输入 Token 均值 | 重排成功率 |",
            "|---|---:|---:|---:|---:|---:|",
        ]
    )
    for experiment in report["experiments"]:
        engineering = experiment["engineering"]
        rerank_rate = engineering["rerank"]["success_rate"]
        lines.append(
            f"| {experiment['label']} | {fmt(engineering['total_ms']['p50'], 1)} | "
            f"{fmt(engineering['total_ms']['p95'], 1)} | {fmt(engineering['retrieval_ms']['p95'], 1)} | "
            f"{fmt(engineering['input_tokens']['mean'], 1)} | "
            f"{fmt(rerank_rate) if rerank_rate is not None else '—'} |"
        )

    lines.extend(
        [
            "",
            "## 综合分口径",
            "",
            "- `RetrievalScore = 2 × Precision × Recall / (Precision + Recall)`，逐题计算。",
            "- `GenerationScore = (Faithfulness + AnswerRelevancy + AnswerAccuracy) / 3`。",
            "- `OverallScore = 0.4 × RetrievalScore + 0.6 × GenerationScore`。",
            "- 以上三项是本项目自定义派生指标，不是 RAGAS 官方指标；正式报告应同时保留五项原始分数。",
            "- 主对比优先使用全部实验共同成功题目的配对结果，避免各组缺失题不同造成样本偏差。",
            "",
            "## 裁判平台一致性",
            "",
        ]
    )
    calibration = report.get("judge_calibration")
    if calibration:
        lines.append(
            "- 发现历史裁判平台一致性校准报告（new-provider-20260728）；"
            "该报告针对内置 B0/E1-E4 实验，与本次自定义实验集无直接对应关系，仅供参考。"
        )
    else:
        lines.append("- 未发现裁判平台一致性校准报告；跨平台分数应谨慎比较。")
    lines.extend(
        [
            "",
            "## 完整性提醒",
            "",
        ]
    )
    incomplete = [experiment for experiment in report["experiments"] if experiment["pending_count"]]
    if incomplete:
        for experiment in incomplete:
            lines.append(
                f"- {experiment['label']} 仍有 {experiment['pending_count']} 题未取得完整指标："
                + ", ".join(experiment["pending_ids"])
                + "。"
            )
    else:
        lines.append(f"- {count_label}均已取得完整评分。")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    root = args.evaluation_root.expanduser().resolve()
    output_dir = args.output_dir.expanduser()
    if not output_dir.is_absolute():
        output_dir = root / output_dir
    output_dir = output_dir.resolve()

    custom_mode = bool(args.experiment or args.pair)
    experiment_specs = parse_experiment_specs(args.experiment, root) if args.experiment else EXPERIMENTS
    experiment_ids = [spec["id"] for spec in experiment_specs]

    if args.pair:
        pairs = parse_pairs(args.pair, experiment_ids)
    elif custom_mode:
        pairs = [
            (experiment_ids[index], experiment_ids[index + 1])
            for index in range(len(experiment_ids) - 1)
        ]
    else:
        pairs = DEFAULT_PAIRS

    experiments = [load_experiment(root, spec) for spec in experiment_specs]
    common_ids = sorted(set.intersection(*(set(experiment["scores"]) for experiment in experiments)))
    calibration_path = root / "analysis/judge-calibration/results/runs/new-provider-20260728/comparison-report.json"
    judge_calibration = (
        json.loads(calibration_path.read_text(encoding="utf-8")) if calibration_path.exists() else None
    )

    serializable_experiments = []
    for index, experiment in enumerate(experiments):
        overall = summarize_ids(
            experiment, experiment["scores"], args.bootstrap_iterations, args.seed + index * 1000
        )
        common = summarize_ids(
            experiment, common_ids, args.bootstrap_iterations, args.seed + index * 1000 + 500
        )
        serializable = {key: value for key, value in experiment.items() if key not in {"predictions", "scores"}}
        serializable["overall"] = overall
        serializable["common"] = common
        serializable["by_question_type"] = grouped_summary(
            experiment, "question_type", args.bootstrap_iterations, args.seed + index * 1000 + 600
        )
        serializable["by_difficulty"] = grouped_summary(
            experiment, "difficulty", args.bootstrap_iterations, args.seed + index * 1000 + 700
        )
        serializable["by_is_answerable"] = grouped_summary(
            experiment, "is_answerable", args.bootstrap_iterations, args.seed + index * 1000 + 800
        )
        serializable_experiments.append(serializable)

    by_id = {experiment["id"]: experiment for experiment in experiments}
    report = {
        "schema_version": 1,
        "evaluation_root": str(root),
        "bootstrap_iterations": args.bootstrap_iterations,
        "bootstrap_seed": args.seed,
        "composite_formula": {
            "retrieval_score": "harmonic_mean(context_precision, context_recall)",
            "generation_score": "mean(faithfulness, answer_relevancy, answer_accuracy)",
            "overall_score": "0.4 * retrieval_score + 0.6 * generation_score",
            "official_ragas_metric": False,
        },
        "common_sample_count": len(common_ids),
        "common_sample_ids": common_ids,
        "judge_calibration": judge_calibration,
        "experiments": serializable_experiments,
        "paired_comparisons": {
            f"{left_id}_to_{right_id}": paired_comparison(
                by_id[left_id],
                by_id[right_id],
                common_ids,
                args.bootstrap_iterations,
                args.seed + 10000 + 1000 * index,
            )
            for index, (left_id, right_id) in enumerate(pairs)
        },
    }

    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "final_experiment_summary.json"
    csv_path = output_dir / "final_experiment_summary.csv"
    markdown_path = output_dir / "FINAL_EXPERIMENT_REPORT.zh-CN.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    csv_fields = [
        "experiment_id",
        "label",
        "prediction_count",
        "successful_score_count",
        "pending_count",
        "common_samples",
        *METRICS,
        "retrieval_score",
        "generation_score",
        "overall_score",
        "total_ms_p50",
        "total_ms_p95",
        "retrieval_ms_p95",
        "input_tokens_mean",
        "output_tokens_mean",
        "rerank_success_rate",
    ]
    with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=csv_fields)
        writer.writeheader()
        for experiment in serializable_experiments:
            metrics = experiment["common"]["metrics"]
            engineering = experiment["engineering"]
            writer.writerow(
                {
                    "experiment_id": experiment["id"],
                    "label": experiment["label"],
                    "prediction_count": experiment["prediction_count"],
                    "successful_score_count": experiment["successful_score_count"],
                    "pending_count": experiment["pending_count"],
                    "common_samples": report["common_sample_count"],
                    **{name: metrics[name]["mean"] for name in METRICS + ["retrieval_score", "generation_score", "overall_score"]},
                    "total_ms_p50": engineering["total_ms"]["p50"],
                    "total_ms_p95": engineering["total_ms"]["p95"],
                    "retrieval_ms_p95": engineering["retrieval_ms"]["p95"],
                    "input_tokens_mean": engineering["input_tokens"]["mean"],
                    "output_tokens_mean": engineering["output_tokens"]["mean"],
                    "rerank_success_rate": engineering["rerank"]["success_rate"],
                }
            )

    markdown_path.write_text(
        build_custom_markdown(report) if custom_mode else build_markdown(report),
        encoding="utf-8",
    )
    print(f"experiments={len(experiments)} common_samples={len(common_ids)}")
    for experiment in serializable_experiments:
        overall_score = experiment["common"]["metrics"]["overall_score"]["mean"]
        print(
            f"{experiment['id']}: predictions={experiment['prediction_count']} "
            f"success={experiment['successful_score_count']} pending={experiment['pending_count']} "
            f"overall={fmt(overall_score)}"
        )
    print(f"json={json_path}")
    print(f"csv={csv_path}")
    print(f"markdown={markdown_path}")


if __name__ == "__main__":
    main()
