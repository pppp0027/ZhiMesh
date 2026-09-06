from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

import numpy as np

from routing_common import read_jsonl
from validate_routing_dataset import validate


ACTIVE_HEADS = ("vectorSufficient", "graphRequired", "bm25Required")
PENDING_HEADS: tuple[str, ...] = ()


def l2_normalize(matrix: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    if np.any(norms == 0) or not np.all(np.isfinite(norms)):
        raise ValueError("embeddings must be finite non-zero vectors")
    return matrix / norms


def sigmoid(values: np.ndarray) -> np.ndarray:
    positive = values >= 0
    result = np.empty_like(values, dtype=np.float64)
    result[positive] = 1.0 / (1.0 + np.exp(-values[positive]))
    exponential = np.exp(values[~positive])
    result[~positive] = exponential / (1.0 + exponential)
    return result


def fit_logistic(x: np.ndarray, y: np.ndarray, epochs: int, learning_rate: float,
                 l2: float) -> tuple[np.ndarray, float]:
    if set(np.unique(y)) != {0.0, 1.0}:
        raise ValueError("training split must contain positive and negative labels")
    weights = np.zeros(x.shape[1], dtype=np.float64)
    positive_rate = float(np.mean(y))
    bias = float(np.log(positive_rate / (1.0 - positive_rate)))
    positives = max(1, int(np.sum(y == 1)))
    negatives = max(1, int(np.sum(y == 0)))
    sample_weights = np.where(y == 1, len(y) / (2 * positives), len(y) / (2 * negatives))
    for _ in range(epochs):
        probabilities = sigmoid(x @ weights + bias)
        errors = (probabilities - y) * sample_weights
        gradient = x.T @ errors / len(y) + l2 * weights
        bias_gradient = float(np.mean(errors))
        weights -= learning_rate * gradient
        bias -= learning_rate * bias_gradient
    if not np.all(np.isfinite(weights)) or not np.isfinite(bias):
        raise ValueError("training produced non-finite parameters")
    return weights, bias


def binary_metrics(y: np.ndarray, probabilities: np.ndarray, threshold: float) -> dict[str, float | int]:
    predicted = probabilities >= threshold
    true_positive = int(np.sum(predicted & (y == 1)))
    false_positive = int(np.sum(predicted & (y == 0)))
    false_negative = int(np.sum(~predicted & (y == 1)))
    precision = true_positive / (true_positive + false_positive) if true_positive + false_positive else 0.0
    recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else 0.0
    return {
        "rows": int(len(y)),
        "positiveRows": int(np.sum(y == 1)),
        "predictedPositiveRows": int(np.sum(predicted)),
        "precision": round(precision, 6),
        "recall": round(recall, 6),
        "coverage": round(float(np.mean(predicted)), 6),
    }


def choose_threshold(capability: str, y: np.ndarray, probabilities: np.ndarray,
                     min_vector_precision: float, min_graph_recall: float) -> tuple[float, dict[str, Any]]:
    candidates = sorted({0.0, 1.0, *[float(value) for value in np.linspace(0.01, 0.99, 99)]})
    scored = [(threshold, binary_metrics(y, probabilities, threshold)) for threshold in candidates]
    if capability == "vectorSufficient":
        eligible = [item for item in scored if item[1]["precision"] >= min_vector_precision
                    and item[1]["predictedPositiveRows"] > 0]
        if not eligible:
            threshold, metrics = 1.0, binary_metrics(y, probabilities, 1.0)
            return threshold, {**metrics, "thresholdGoalMet": False}
        threshold, metrics = max(eligible,
                                 key=lambda item: (item[1]["predictedPositiveRows"], item[0]))
    else:
        eligible = [item for item in scored if item[1]["recall"] >= min_graph_recall]
        if not eligible:
            threshold, metrics = 0.0, binary_metrics(y, probabilities, 0.0)
            return threshold, {**metrics, "thresholdGoalMet": False}
        threshold, metrics = max(eligible,
                                 key=lambda item: (item[1]["precision"], item[0]))
    return round(float(threshold), 6), {**metrics, "thresholdGoalMet": True}


def arrays(rows: list[dict[str, Any]], capability: str, split: str,
           allow_auto_suggested: bool) -> tuple[np.ndarray, np.ndarray]:
    selected = [
        row for row in rows
        if row.get("split") == split
        and isinstance((row.get("labels") or {}).get(capability), bool)
        and (row.get("labelStatus") == "REVIEWED" or allow_auto_suggested)
    ]
    if not selected:
        raise ValueError(f"no usable {split} rows for {capability}")
    x = l2_normalize(np.asarray([row["queryEmbedding"] for row in selected], dtype=np.float64))
    y = np.asarray([1.0 if row["labels"][capability] else 0.0 for row in selected])
    return x, y


def train_artifact(rows: list[dict[str, Any]], dataset_sha256: str,
                   model_version: str, allow_auto_suggested: bool = False,
                   epochs: int = 2000, learning_rate: float = 0.1, l2: float = 0.0001,
                   min_vector_precision: float = 0.98,
                   min_graph_recall: float = 0.98) -> tuple[dict[str, Any], dict[str, Any]]:
    errors = validate(rows, for_training=True, require_reviewed=not allow_auto_suggested)
    if errors:
        raise ValueError("\n".join(errors))
    dimensions = {len(row["queryEmbedding"]) for row in rows if row.get("queryEmbedding")}
    embedding_models = {str(row.get("embeddingModel")) for row in rows if row.get("embeddingModel")}
    if len(dimensions) != 1 or len(embedding_models) != 1:
        raise ValueError("training dataset must use one embedding model and dimension")
    dimension = dimensions.pop()
    embedding_model = embedding_models.pop()
    outputs: dict[str, Any] = {}
    report: dict[str, Any] = {"modelVersion": model_version, "heads": {}}
    for capability in ACTIVE_HEADS:
        train_x, train_y = arrays(rows, capability, "train", allow_auto_suggested)
        validation_x, validation_y = arrays(rows, capability, "validation", allow_auto_suggested)
        test_x, test_y = arrays(rows, capability, "test", allow_auto_suggested)
        weights, bias = fit_logistic(train_x, train_y, epochs, learning_rate, l2)
        validation_probabilities = sigmoid(validation_x @ weights + bias)
        threshold, validation_metrics = choose_threshold(
            capability, validation_y, validation_probabilities,
            min_vector_precision, min_graph_recall,
        )
        test_probabilities = sigmoid(test_x @ weights + bias)
        outputs[capability] = {
            "enabled": True,
            "weights": [round(float(value), 12) for value in weights],
            "bias": round(float(bias), 12),
            "threshold": threshold,
        }
        report["heads"][capability] = {
            "trainRows": int(len(train_y)),
            "validation": validation_metrics,
            "test": binary_metrics(test_y, test_probabilities, threshold),
        }
    for capability in PENDING_HEADS:
        outputs[capability] = {"enabled": False, "weights": [], "bias": 0.0, "threshold": 1.0}
    artifact = {
        "schemaVersion": 1,
        "modelVersion": model_version,
        "embeddingModel": embedding_model,
        "embeddingDimension": dimension,
        "normalization": "l2",
        "trainingDatasetSha256": dataset_sha256,
        "outputs": outputs,
    }
    return artifact, report


def main() -> None:
    parser = argparse.ArgumentParser(description="Train frozen-embedding Vector/Graph/BM25 routing heads")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--allow-auto-suggested", action="store_true",
                        help="Experimental only; production training requires REVIEWED labels")
    parser.add_argument("--epochs", type=int, default=2000)
    parser.add_argument("--learning-rate", type=float, default=0.1)
    parser.add_argument("--l2", type=float, default=0.0001)
    parser.add_argument("--min-vector-precision", type=float, default=0.98)
    parser.add_argument("--min-graph-recall", type=float, default=0.98)
    args = parser.parse_args()
    source_bytes = Path(args.input).read_bytes()
    dataset_sha256 = hashlib.sha256(source_bytes).hexdigest()
    rows = read_jsonl(args.input)
    artifact, report = train_artifact(
        rows, dataset_sha256, args.model_version, args.allow_auto_suggested,
        args.epochs, args.learning_rate, args.l2,
        args.min_vector_precision, args.min_graph_recall,
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(artifact, ensure_ascii=False, sort_keys=True,
                            separators=(",", ":")).encode("utf-8")
    output.write_bytes(serialized)
    artifact_sha256 = hashlib.sha256(serialized).hexdigest()
    output.with_suffix(output.suffix + ".sha256").write_text(
        artifact_sha256 + "\n", encoding="ascii"
    )
    output.with_suffix(output.suffix + ".report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"wrote classifier={output} sha256={artifact_sha256}")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
